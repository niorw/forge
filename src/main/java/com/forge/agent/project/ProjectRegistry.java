package com.forge.agent.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 多项目注册中心 — Agent 平台化的核心枢纽。
 *
 * 核心理念（来自 Anthropic Harness Engineering）：
 * "同一个 Agent 平台服务多个微服务项目，
 *  每个项目独立的 Spec 目录 + 配置，共享 Worker 池。"
 *
 * 项目级隔离：
 * - 每个项目有独立的 Spec 存储路径
 * - 每个项目有独立的 Prompt 配置（可覆盖全局默认）
 * - 每个项目有独立的 LLM 模型配置（可覆盖全局默认）
 * - 每个项目有独立的 Skill 集合（可叠加全局 Skill）
 * - 共享 Worker 池和基础设施（Redis/MySQL/MCP）
 *
 * 使用方式：
 *   projectRegistry.register(new Project("cmc", "资管系统", ...));
 *   Project project = projectRegistry.get("cmc");
 *   String specPath = project.specStoragePath();
 */
@Component
public class ProjectRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProjectRegistry.class);

    /** projectId → Project */
    private final Map<String, Project> projects = new ConcurrentHashMap<>();

    /** 默认项目 ID（当请求未指定项目时使用） */
    private String defaultProjectId;

    // ==================== 注册 ====================

    /**
     * 注册项目
     */
    public void register(Project project) {
        projects.put(project.projectId(), project);
        log.info("项目已注册: {} ({}) — Spec: {}", project.projectId(), project.name(), project.specStoragePath());
    }

    /**
     * 批量注册
     */
    public void registerAll(List<Project> projectList) {
        projectList.forEach(this::register);
    }

    /**
     * 设置默认项目
     */
    public void setDefaultProject(String projectId) {
        if (!projects.containsKey(projectId)) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
        this.defaultProjectId = projectId;
    }

    // ==================== 查询 ====================

    /**
     * 获取项目
     */
    public Optional<Project> get(String projectId) {
        return Optional.ofNullable(projects.get(projectId));
    }

    /**
     * 获取默认项目
     */
    public Optional<Project> getDefault() {
        if (defaultProjectId != null) {
            return get(defaultProjectId);
        }
        return projects.values().stream().findFirst();
    }

    /**
     * 列出所有项目
     */
    public List<Project> list() {
        return List.copyOf(projects.values());
    }

    /**
     * 按标签筛选项目
     */
    public List<Project> listByTag(String tag) {
        return projects.values().stream()
                .filter(p -> p.tags() != null && p.tags().contains(tag))
                .collect(Collectors.toList());
    }

    /**
     * 项目数量
     */
    public int size() {
        return projects.size();
    }

    /**
     * 项目是否存在
     */
    public boolean exists(String projectId) {
        return projects.containsKey(projectId);
    }

    // ==================== 项目记录 ====================

    /**
     * 项目定义
     *
     * @param projectId         项目唯一标识（如 cmc, cof, lsc）
     * @param name              项目名称（如 资管系统）
     * @param description       项目描述
     * @param specStoragePath   Spec 存储路径
     * @param promptOverrides   Prompt 覆盖配置（action → system prompt）
     * @param modelOverrides    LLM 模型覆盖配置（role → model name）
     * @param extraSkills       额外 Skill 列表（叠加到全局 Skill）
     * @param tags              标签（用于分组筛选）
     * @param owner             项目负责人
     * @param enabled           是否启用
     */
    public record Project(
            String projectId,
            String name,
            String description,
            String specStoragePath,
            Map<String, String> promptOverrides,
            Map<String, String> modelOverrides,
            List<String> extraSkills,
            List<String> tags,
            String owner,
            boolean enabled
    ) {
        /**
         * 简单构造器（使用默认配置）
         */
        public static Project of(String projectId, String name, String specStoragePath) {
            return new Project(projectId, name, null, specStoragePath,
                    Map.of(), Map.of(), List.of(), List.of(), null, true);
        }

        /**
         * 获取角色对应的模型覆盖（如有）
         */
        public String getModelOverride(String role) {
            return modelOverrides != null ? modelOverrides.get(role) : null;
        }

        /**
         * 获取动作对应的 Prompt 覆盖（如有）
         */
        public String getPromptOverride(String action) {
            return promptOverrides != null ? promptOverrides.get(action) : null;
        }
    }
}
