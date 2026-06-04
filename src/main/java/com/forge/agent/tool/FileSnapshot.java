package com.forge.agent.tool;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 文件快照 Diff 工具 — 精确识别 Agent 生成/修改了哪些文件。
 *
 * 核心理念（来自 Anthropic Harness Engineering）：
 * "在每个任务开始前，拍摄项目目录的完整快照（文件路径 + 大小 + 修改时间）。
 *  任务完成后再拍一次快照，通过 Diff 精确识别出哪些文件是本次生成/修改的。"
 *
 * 使用方式：
 *   FileSnapshot before = FileSnapshot.capture("/path/to/project");
 *   // ... Worker 执行代码生成 ...
 *   FileSnapshot after = FileSnapshot.capture("/path/to/project");
 *   FileSnapshot.Diff diff = before.diff(after);
 *   diff.created()  // 新增的文件
 *   diff.modified() // 修改的文件
 *   diff.deleted()  // 删除的文件
 */
public final class FileSnapshot {

    /** 文件元信息 */
    public record FileEntry(
            String relativePath,
            long size,
            long lastModified
    ) {}

    /** 快照内容：路径 → 文件元信息 */
    private final Map<String, FileEntry> entries;
    /** 快照时间戳 */
    private final long capturedAt;

    private FileSnapshot(Map<String, FileEntry> entries) {
        this.entries = Collections.unmodifiableMap(entries);
        this.capturedAt = System.currentTimeMillis();
    }

    // ==================== 快照拍摄 ====================

    /**
     * 拍摄目录快照
     *
     * @param directory 项目根目录
     * @return 快照对象
     */
    public static FileSnapshot capture(String directory) {
        return capture(directory, defaultExcludes());
    }

    /**
     * 拍摄目录快照（自定义排除规则）
     *
     * @param directory 项目根目录
     * @param excludePatterns 排除的路径前缀（如 target/, .git/, node_modules/）
     * @return 快照对象
     */
    public static FileSnapshot capture(String directory, Set<String> excludePatterns) {
        Map<String, FileEntry> entries = new LinkedHashMap<>();
        Path root = Path.of(directory);

        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return new FileSnapshot(entries);
        }

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String relativePath = root.relativize(file).toString();

                    // 检查排除规则
                    for (String pattern : excludePatterns) {
                        if (relativePath.startsWith(pattern) || relativePath.contains("/" + pattern)) {
                            return FileVisitResult.CONTINUE;
                        }
                    }

                    entries.put(relativePath, new FileEntry(
                            relativePath,
                            attrs.size(),
                            attrs.lastModifiedTime().toMillis()
                    ));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // 快照失败不阻塞主流程
        }

        return new FileSnapshot(entries);
    }

    // ==================== Diff 计算 ====================

    /**
     * 与另一个快照比较，计算差异
     *
     * @param other 另一个快照（通常是任务执行后拍摄的）
     * @return 差异报告
     */
    public Diff diff(FileSnapshot other) {
        Set<String> thisPaths = this.entries.keySet();
        Set<String> otherPaths = other.entries.keySet();

        // 新增文件：在 other 中但不在 this 中
        List<String> created = otherPaths.stream()
                .filter(p -> !thisPaths.contains(p))
                .collect(Collectors.toList());

        // 删除文件：在 this 中但不在 other 中
        List<String> deleted = thisPaths.stream()
                .filter(p -> !otherPaths.contains(p))
                .collect(Collectors.toList());

        // 修改文件：两边都有，但 size 或 lastModified 不同
        List<String> modified = thisPaths.stream()
                .filter(otherPaths::contains)
                .filter(p -> {
                    FileEntry before = this.entries.get(p);
                    FileEntry after = other.entries.get(p);
                    return before.size() != after.size() || before.lastModified() != after.lastModified();
                })
                .collect(Collectors.toList());

        return new Diff(created, modified, deleted, this.capturedAt, other.capturedAt);
    }

    // ==================== Diff 结果 ====================

    /**
     * 文件差异报告
     */
    public record Diff(
            /** 新增的文件（相对路径） */
            List<String> created,
            /** 修改的文件（相对路径） */
            List<String> modified,
            /** 删除的文件（相对路径） */
            List<String> deleted,
            /** 快照拍摄时间 */
            long beforeTimestamp,
            long afterTimestamp
    ) {
        /** 是否有任何变更 */
        public boolean hasChanges() {
            return !created.isEmpty() || !modified.isEmpty() || !deleted.isEmpty();
        }

        /** 变更文件总数 */
        public int totalChanges() {
            return created.size() + modified.size() + deleted.size();
        }

        /** 格式化为人类可读报告 */
        public String toReport() {
            if (!hasChanges()) {
                return "文件快照 Diff：无变更";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("文件快照 Diff：共 ").append(totalChanges()).append(" 个文件变更\n");

            if (!created.isEmpty()) {
                sb.append("\n  [新增] ").append(created.size()).append(" 个文件:\n");
                created.forEach(f -> sb.append("    + ").append(f).append("\n"));
            }
            if (!modified.isEmpty()) {
                sb.append("\n  [修改] ").append(modified.size()).append(" 个文件:\n");
                modified.forEach(f -> sb.append("    ~ ").append(f).append("\n"));
            }
            if (!deleted.isEmpty()) {
                sb.append("\n  [删除] ").append(deleted.size()).append(" 个文件:\n");
                deleted.forEach(f -> sb.append("    - ").append(f).append("\n"));
            }

            return sb.toString();
        }

        /** 合并所有变更文件路径 */
        public List<String> allChangedFiles() {
            List<String> all = new ArrayList<>();
            all.addAll(created);
            all.addAll(modified);
            all.addAll(deleted);
            return all;
        }
    }

    // ==================== 默认排除规则 ====================

    /** 默认排除目录 */
    private static Set<String> defaultExcludes() {
        return Set.of(
                "target/",
                ".git/",
                ".idea/",
                ".mvn/",
                "node_modules/",
                ".DS_Store",
                "*.class"
        );
    }
}
