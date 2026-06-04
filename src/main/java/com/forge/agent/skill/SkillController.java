package com.forge.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Skill 管理 REST API
 *
 * 提供 Skill 的查询、重新加载、Git 更新等管理能力。
 * 用于前端 Dashboard 展示和运维管理。
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private static final Logger log = LoggerFactory.getLogger(SkillController.class);

    private final SkillRegistry skillRegistry;
    private final SkillVersionManager versionManager;

    public SkillController(SkillRegistry skillRegistry, SkillVersionManager versionManager) {
        this.skillRegistry = skillRegistry;
        this.versionManager = versionManager;
    }

    /**
     * 列出所有已加载的 Skill
     */
    @GetMapping
    public ResponseEntity<List<SkillInfo>> listSkills(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String role) {

        List<SkillRegistry.Skill> skills;

        if (category != null && !category.isBlank()) {
            skills = skillRegistry.listByCategory(category);
        } else if (role != null && !role.isBlank()) {
            skills = skillRegistry.list().stream()
                    .filter(s -> s.roles().contains(role) || s.roles().contains("all"))
                    .collect(Collectors.toList());
        } else {
            skills = skillRegistry.list();
        }

        List<SkillInfo> result = skills.stream()
                .map(this::toSkillInfo)
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    /**
     * 获取指定 Skill 详情
     */
    @GetMapping("/{name}")
    public ResponseEntity<SkillInfo> getSkill(@PathVariable String name) {
        return skillRegistry.get(name)
                .map(skill -> ResponseEntity.ok(toSkillInfo(skill)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 获取指定 Skill 的完整内容（含 Markdown）
     */
    @GetMapping("/{name}/content")
    public ResponseEntity<SkillContent> getSkillContent(@PathVariable String name) {
        return skillRegistry.get(name)
                .map(skill -> ResponseEntity.ok(new SkillContent(
                        skill.name(),
                        skill.content(),
                        skill.source(),
                        skill.version()
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 获取角色适用的 Skill 列表
     */
    @GetMapping("/role/{role}")
    public ResponseEntity<RoleSkills> getSkillsForRole(@PathVariable String role) {
        List<String> skillNames = skillRegistry.getSkillNamesForRole(role);
        String combinedContent = skillRegistry.getSkillsForRole(role);

        return ResponseEntity.ok(new RoleSkills(
                role,
                skillNames,
                combinedContent.length(),
                skillNames.size()
        ));
    }

    /**
     * 手动重新加载所有 Skill
     */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reload() {
        int reloaded = versionManager.reload();
        log.info("Skill 手动重新加载: {} 个已更新", reloaded);

        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "reloaded", reloaded,
                "total", skillRegistry.size(),
                "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * 执行 Git pull 更新 Skill 仓库
     */
    @PostMapping("/git-pull")
    public ResponseEntity<Map<String, Object>> gitPull() {
        String result = versionManager.gitPull();
        log.info("Skill Git pull: {}", result.contains("\n") ? result.substring(0, result.indexOf("\n")) : result);

        return ResponseEntity.ok(Map.of(
                "status", result.contains("成功") ? "ok" : "error",
                "message", result,
                "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * 获取 Skill 版本历史
     */
    @GetMapping("/versions")
    public ResponseEntity<Map<String, SkillVersionManager.VersionHistory>> getVersionHistory() {
        return ResponseEntity.ok(versionManager.getVersionHistory());
    }

    /**
     * 获取 Skill 统计摘要
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        List<SkillRegistry.Skill> all = skillRegistry.list();

        Map<String, Long> byCategory = all.stream()
                .collect(Collectors.groupingBy(SkillRegistry.Skill::category, Collectors.counting()));

        Map<String, Long> byRole = all.stream()
                .flatMap(s -> s.roles().stream())
                .collect(Collectors.groupingBy(r -> r, Collectors.counting()));

        return ResponseEntity.ok(Map.of(
                "total", all.size(),
                "byCategory", byCategory,
                "byRole", byRole,
                "totalContentLength", all.stream().mapToLong(s -> s.content().length()).sum()
        ));
    }

    // ==================== 内部 record ====================

    private SkillInfo toSkillInfo(SkillRegistry.Skill skill) {
        return new SkillInfo(
                skill.name(),
                skill.description(),
                skill.category(),
                skill.roles(),
                skill.content().length(),
                skill.version(),
                skill.source()
        );
    }

    public record SkillInfo(
            String name,
            String description,
            String category,
            java.util.Set<String> roles,
            int contentLength,
            String version,
            String source
    ) {}

    public record SkillContent(
            String name,
            String content,
            String source,
            String version
    ) {}

    public record RoleSkills(
            String role,
            List<String> skillNames,
            int totalContentLength,
            int skillCount
    ) {}
}
