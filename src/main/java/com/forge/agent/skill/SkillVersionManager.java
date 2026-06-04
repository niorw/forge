package com.forge.agent.skill;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Skill 版本管理器 — 支持热更新和 Git 存储。
 *
 * 核心能力：
 * 1. 版本追踪 — 每个 Skill 的版本历史（基于文件内容 hash）
 * 2. 热更新 — 监控外部目录，文件变更时自动重新加载
 * 3. Git 存储 — Skill 文件可存放在独立 Git 仓库，通过 pull 更新
 *
 * 版本策略：
 * - 内置 Skill（classpath）：启动时加载，运行时不更新
 * - 外部 Skill（external-dir）：启动时加载 + 文件监控热更新
 * - Git Skill（git-repo）：定时 pull + 热更新
 */
@Component
public class SkillVersionManager {

    private static final Logger log = LoggerFactory.getLogger(SkillVersionManager.class);

    private final SkillRegistry skillRegistry;

    /** 外部 Skill 目录 */
    @Value("${agent.skills.external-dir:}")
    private String externalSkillDir;

    /** Git Skill 仓库路径（可选） */
    @Value("${agent.skills.git-repo-path:}")
    private String gitRepoPath;

    /** 热更新间隔（秒） */
    @Value("${agent.skills.reload-interval-seconds:30}")
    private int reloadIntervalSeconds;

    /** 文件监控服务 */
    private WatchService watchService;
    private volatile boolean watching = false;

    /** 定时刷新服务（Git pull） */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "skill-reload");
        t.setDaemon(true);
        return t;
    });

    /** Skill 版本历史：skillName → versionHistory */
    private final Map<String, VersionHistory> versionHistory = new ConcurrentHashMap<>();

    /**
     * 版本历史记录
     */
    public record VersionHistory(
            String skillName,
            String currentVersion,
            String previousVersion,
            long lastUpdated,
            int reloadCount
    ) {}

    public SkillVersionManager(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    /**
     * 启动热更新监控
     */
    public void startWatching() {
        // 1. 启动文件目录监控
        if (externalSkillDir != null && !externalSkillDir.isBlank()) {
            startDirectoryWatch(externalSkillDir);
        }

        // 2. 启动定时 Git pull（如果配置了 Git 仓库路径）
        if (gitRepoPath != null && !gitRepoPath.isBlank()) {
            startGitPullScheduler();
        }
    }

    /**
     * 停止所有监控
     */
    @PreDestroy
    public void stop() {
        watching = false;
        scheduler.shutdownNow();
        if (watchService != null) {
            try { watchService.close(); } catch (IOException ignored) {}
        }
        log.info("Skill 版本管理器已停止");
    }

    /**
     * 手动触发重新加载（REST API 调用）
     *
     * @return 重新加载的 Skill 数量
     */
    public int reload() {
        int count = 0;

        // 重新加载外部目录
        if (externalSkillDir != null && !externalSkillDir.isBlank()) {
            count += skillRegistry.loadFromDirectory(externalSkillDir);
        }

        // 重新加载 Git 仓库
        if (gitRepoPath != null && !gitRepoPath.isBlank()) {
            count += skillRegistry.loadFromDirectory(gitRepoPath);
        }

        // 更新版本历史
        updateVersionHistory();

        log.info("Skill 手动重新加载完成: {} 个 Skill 已更新", count);
        return count;
    }

    /**
     * 执行 Git pull 更新 Skill 仓库
     *
     * @return pull 结果摘要
     */
    public String gitPull() {
        if (gitRepoPath == null || gitRepoPath.isBlank()) {
            return "未配置 Git Skill 仓库路径";
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("git", "pull");
            pb.directory(new java.io.File(gitRepoPath));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                // Git pull 成功，重新加载
                int reloaded = skillRegistry.loadFromDirectory(gitRepoPath);
                updateVersionHistory();
                return String.format("Git pull 成功: %s\n已重新加载 %d 个 Skill", output.trim(), reloaded);
            } else {
                return "Git pull 失败 (exitCode=" + exitCode + "): " + output.trim();
            }
        } catch (Exception e) {
            return "Git pull 异常: " + e.getMessage();
        }
    }

    /**
     * 获取所有 Skill 的版本历史
     */
    public Map<String, VersionHistory> getVersionHistory() {
        return Map.copyOf(versionHistory);
    }

    /**
     * 获取指定 Skill 的版本历史
     */
    public VersionHistory getVersionHistory(String skillName) {
        return versionHistory.get(skillName);
    }

    // ==================== 内部方法 ====================

    /**
     * 启动目录文件监控（热更新）
     */
    private void startDirectoryWatch(String directory) {
        Path dir = Path.of(directory);
        if (!Files.exists(dir)) {
            log.warn("Skill 外部目录不存在，跳过监控: {}", directory);
            return;
        }

        try {
            watchService = FileSystems.getDefault().newWatchService();
            dir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);

            watching = true;

            Thread watchThread = new Thread(() -> {
                log.info("Skill 文件监控已启动: {}", directory);
                while (watching) {
                    try {
                        WatchKey key = watchService.poll(5, TimeUnit.SECONDS);
                        if (key == null) continue;

                        for (WatchEvent<?> event : key.pollEvents()) {
                            Path changed = (Path) event.context();
                            if (changed.toString().endsWith(".md")) {
                                log.info("Skill 文件变更: {} ({})", changed, event.kind());
                                // 延迟 1 秒后重新加载（避免文件写入未完成）
                                scheduler.schedule(this::reload, 1, TimeUnit.SECONDS);
                                break;
                            }
                        }

                        if (!key.reset()) {
                            log.warn("WatchKey 失效，停止监控");
                            break;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                log.info("Skill 文件监控已停止");
            }, "skill-watcher");

            watchThread.setDaemon(true);
            watchThread.start();

        } catch (IOException e) {
            log.warn("启动 Skill 文件监控失败: {}", e.getMessage());
        }
    }

    /**
     * 启动定时 Git pull 调度器
     */
    private void startGitPullScheduler() {
        log.info("Skill Git 定时更新已启动: 间隔 {} 秒, 仓库 {}", reloadIntervalSeconds, gitRepoPath);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                String result = gitPull();
                log.debug("Skill Git 定时更新: {}", result.contains("\n") ? result.substring(0, result.indexOf("\n")) : result);
            } catch (Exception e) {
                log.warn("Skill Git 定时更新失败: {}", e.getMessage());
            }
        }, reloadIntervalSeconds, reloadIntervalSeconds, TimeUnit.SECONDS);
    }

    /**
     * 更新版本历史
     */
    private void updateVersionHistory() {
        for (SkillRegistry.Skill skill : skillRegistry.list()) {
            versionHistory.merge(skill.name(),
                    new VersionHistory(skill.name(), skill.version(), null, System.currentTimeMillis(), 1),
                    (old, latest) -> new VersionHistory(
                            skill.name(),
                            skill.version(),
                            old.currentVersion(),
                            System.currentTimeMillis(),
                            old.reloadCount() + 1
                    ));
        }
    }
}
