package com.forge.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Skill 注册中心 —— Agent 知识注入的核心枢纽。
 *
 * 核心理念（来自 Anthropic Harness Engineering）：
 * "Skills 不是可执行工具，而是'知识注入'——在 LLM 调用前注入专业上下文。
 *  当 Generator 调用 Skill tdd 时，它实际上在读取一套经过精心编写的
 *  测试驱动开发最佳实践，而不是仅依赖模型内置知识。
 *  这将 Agent 的能力从'模型知道什么'扩展到'团队沉淀了什么'。"
 *
 * 三层能力区分：
 * - Skill：知识片段，注入到 Prompt（本类管理）
 * - Tool：可执行工具，进程内直接调用（ToolRegistry 管理）
 * - MCP：外部能力服务，跨进程 RPC 调用（McpToolBridge 桥接）
 *
 * Skill 文件格式（YAML frontmatter + Markdown 内容）：
 * <pre>
 * ---
 * name: code-review-checklist
 * description: 代码审查标准清单
 * category: checklist
 * roles:
 *   - reviewer
 *   - security-reviewer
 * ---
 *
 * # 代码审查清单
 * ## 维度一：接口一致性
 * - [ ] 方法签名是否与 Spec 完全一致
 * ...
 * </pre>
 */
@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    /** YAML frontmatter 分隔符 */
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n(.*)", Pattern.DOTALL);

    /** name → Skill */
    private final Map<String, Skill> skills = new ConcurrentHashMap<>();

    /**
     * Skill 记录
     *
     * @param name        唯一标识
     * @param description 描述
     * @param category    分类：process / convention / checklist / context
     * @param content     Markdown 内容
     * @param roles       适用的角色集合（"all" 表示所有角色）
     * @param version     版本号（从文件修改时间或 Git commit 生成）
     * @param source      来源路径
     */
    public record Skill(
            String name,
            String description,
            String category,
            String content,
            Set<String> roles,
            String version,
            String source
    ) {}

    // ==================== 加载 ====================

    /**
     * 从 classpath:skills/ 加载所有 .md 文件
     */
    public int loadFromClasspath() {
        int loaded = 0;
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:skills/*.md");

            for (Resource resource : resources) {
                try {
                    Skill skill = parseSkillFile(resource);
                    if (skill != null) {
                        skills.put(skill.name(), skill);
                        loaded++;
                        log.info("Skill 已加载: {} ({}, {} 字符)", skill.name(), skill.category(), skill.content().length());
                    }
                } catch (Exception e) {
                    log.warn("Skill 文件加载失败: {}", resource.getFilename(), e);
                }
            }
        } catch (IOException e) {
            log.warn("扫描 skills/ 目录失败: {}", e.getMessage());
        }

        log.info("Skill 加载完成: 共 {} 个", loaded);
        return loaded;
    }

    /**
     * 从外部目录加载 Skill（支持运行时热更新）
     */
    public int loadFromDirectory(String directoryPath) {
        java.io.File dir = new java.io.File(directoryPath);
        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("Skill 目录不存在: {}", directoryPath);
            return 0;
        }

        int loaded = 0;
        java.io.File[] files = dir.listFiles((d, name) -> name.endsWith(".md"));
        if (files == null) return 0;

        for (java.io.File file : files) {
            try {
                Skill skill = parseSkillFile(file);
                if (skill != null) {
                    skills.put(skill.name(), skill);
                    loaded++;
                    log.info("Skill 已加载: {} ({}, 来自外部目录)", skill.name(), skill.category());
                }
            } catch (Exception e) {
                log.warn("Skill 文件加载失败: {}", file.getName(), e);
            }
        }

        return loaded;
    }

    // ==================== 查询 ====================

    /**
     * 获取指定 Skill
     */
    public Optional<Skill> get(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    /**
     * 列出所有已加载的 Skill
     */
    public List<Skill> list() {
        return List.copyOf(skills.values());
    }

    /**
     * 按分类列出
     */
    public List<Skill> listByCategory(String category) {
        return skills.values().stream()
                .filter(s -> s.category().equals(category))
                .collect(Collectors.toList());
    }

    /**
     * 获取角色适用的所有 Skill 内容（注入到 Prompt）
     *
     * 核心方法：将角色相关的所有 Skill 知识拼接为一段文本，
     * 供 ContextBuilder 注入到 LLM 的 system/user prompt 中。
     *
     * @param role 角色名
     * @return 拼接后的 Skill 知识文本，无 Skill 时返回空串
     */
    public String getSkillsForRole(String role) {
        return skills.values().stream()
                .filter(s -> s.roles().contains(role) || s.roles().contains("all"))
                .sorted(Comparator.comparing(Skill::category).thenComparing(Skill::name))
                .map(s -> "### [" + s.category() + "] " + s.name() + "\n" + s.content())
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    /**
     * 获取角色适用的 Skill 名称列表（用于日志/调试）
     */
    public List<String> getSkillNamesForRole(String role) {
        return skills.values().stream()
                .filter(s -> s.roles().contains(role) || s.roles().contains("all"))
                .map(Skill::name)
                .collect(Collectors.toList());
    }

    /**
     * 已加载的 Skill 总数
     */
    public int size() {
        return skills.size();
    }

    // ==================== 解析 ====================

    /**
     * 从 Spring Resource 解析 Skill
     */
    private Skill parseSkillFile(Resource resource) throws IOException {
        String content;
        try (var reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            content = reader.lines().collect(Collectors.joining("\n"));
        }
        return parseSkillContent(content, resource.getFilename(), "classpath:skills/" + resource.getFilename());
    }

    /**
     * 从 File 解析 Skill
     */
    private Skill parseSkillFile(java.io.File file) throws IOException {
        String content = java.nio.file.Files.readString(file.toPath(), StandardCharsets.UTF_8);
        return parseSkillContent(content, file.getName(), file.getAbsolutePath());
    }

    /**
     * 解析 Skill 内容（YAML frontmatter + Markdown body）
     */
    private Skill parseSkillContent(String rawContent, String filename, String source) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(rawContent);
        if (!matcher.find()) {
            log.warn("Skill 文件缺少 YAML frontmatter: {}", filename);
            return null;
        }

        String frontmatter = matcher.group(1);
        String body = matcher.group(2).trim();

        // 简单解析 YAML frontmatter（不引入 YAML 库，避免依赖膨胀）
        String name = extractYamlValue(frontmatter, "name");
        String description = extractYamlValue(frontmatter, "description");
        String category = extractYamlValue(frontmatter, "category");
        Set<String> roles = extractYamlList(frontmatter, "roles");

        if (name == null || name.isBlank()) {
            name = filename.replace(".md", "");
        }
        if (category == null || category.isBlank()) {
            category = "general";
        }
        if (roles.isEmpty()) {
            roles = Set.of("all");
        }

        // 版本号：使用文件内容的 hash（用于变更检测）
        String version = String.valueOf(rawContent.hashCode());

        return new Skill(name, description, category, body, roles, version, source);
    }

    // ==================== YAML 简易解析 ====================

    private String extractYamlValue(String yaml, String key) {
        Pattern p = Pattern.compile("^" + key + ":\\s*(.+)$", Pattern.MULTILINE);
        Matcher m = p.matcher(yaml);
        if (m.find()) {
            String value = m.group(1).trim();
            // 去掉引号
            if ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            return value;
        }
        return null;
    }

    private Set<String> extractYamlList(String yaml, String key) {
        Pattern p = Pattern.compile("^" + key + ":\\s*\\n((?:\\s+-\\s+.+\\n?)+)", Pattern.MULTILINE);
        Matcher m = p.matcher(yaml);
        if (m.find()) {
            String listBlock = m.group(1);
            return Pattern.compile("\\n")
                    .splitAsStream(listBlock)
                    .map(line -> line.replaceAll("^\\s+-\\s+", "").trim())
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
        }
        return Set.of();
    }
}
