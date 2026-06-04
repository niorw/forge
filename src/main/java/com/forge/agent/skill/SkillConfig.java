package com.forge.agent.skill;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Skill 自动加载配置
 *
 * 启动时自动从 classpath:skills/ 和外部目录加载 Skill 文件。
 * 外部目录优先级高于 classpath（支持运行时覆盖内置 Skill）。
 */
@Configuration
public class SkillConfig {

    private static final Logger log = LoggerFactory.getLogger(SkillConfig.class);

    private final SkillRegistry skillRegistry;
    private final SkillVersionManager versionManager;

    /** 外部 Skill 目录（可选，通过 application.yml 配置） */
    @Value("${agent.skills.external-dir:}")
    private String externalSkillDir;

    public SkillConfig(SkillRegistry skillRegistry, SkillVersionManager versionManager) {
        this.skillRegistry = skillRegistry;
        this.versionManager = versionManager;
    }

    @PostConstruct
    public void init() {
        // 1. 从 classpath:skills/ 加载内置 Skill
        int classpathLoaded = skillRegistry.loadFromClasspath();
        log.info("从 classpath 加载 {} 个 Skill", classpathLoaded);

        // 2. 从外部目录加载（覆盖同名 Skill）
        if (externalSkillDir != null && !externalSkillDir.isBlank()) {
            int externalLoaded = skillRegistry.loadFromDirectory(externalSkillDir);
            log.info("从外部目录加载 {} 个 Skill: {}", externalLoaded, externalSkillDir);
        }

        // 3. 输出加载摘要
        log.info("=== Skill 加载摘要 ===");
        skillRegistry.listByCategory("process").forEach(s ->
                log.info("  [process] {} — {}", s.name(), s.description()));
        skillRegistry.listByCategory("checklist").forEach(s ->
                log.info("  [checklist] {} — {}", s.name(), s.description()));
        skillRegistry.listByCategory("convention").forEach(s ->
                log.info("  [convention] {} — {}", s.name(), s.description()));
        skillRegistry.listByCategory("context").forEach(s ->
                log.info("  [context] {} — {}", s.name(), s.description()));
        log.info("共 {} 个 Skill 已就绪", skillRegistry.size());

        // 4. 启动热更新监控
        versionManager.startWatching();
        log.info("Skill 热更新监控已启动");
    }
}
