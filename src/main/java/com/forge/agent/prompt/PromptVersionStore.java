package com.forge.agent.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Prompt 版本管理平台 — 集中管理、版本化、A/B 测试。
 *
 * 核心理念（来自 Anthropic Harness Engineering）：
 * "各节点的 Prompt 集中管理，版本化、A/B 测试。
 *  Prompt 中的表述会深刻塑造输出的'性格'——
 *  强调 'production-grade' 会让 Generator 增加防御性代码；
 *  强调 'rapid prototype' 会让它跳过最佳实践。"
 *
 * 能力：
 * 1. 版本化 — 每个 Prompt 变更都有版本号，可回滚
 * 2. A/B 测试 — 同一 action 可配置多个 Prompt 变体，按流量比例分配
 * 3. 项目覆盖 — 项目级 Prompt 可覆盖全局默认
 * 4. 效果追踪 — 记录每个变体的使用次数和效果指标
 */
@Component
public class PromptVersionStore {

    private static final Logger log = LoggerFactory.getLogger(PromptVersionStore.class);

    /** action → 版本列表（按版本号排序） */
    private final Map<String, List<PromptVersion>> versions = new ConcurrentHashMap<>();

    /** action → 当前活跃的 A/B 测试配置 */
    private final Map<String, AbTestConfig> abTests = new ConcurrentHashMap<>();

    /** 变体使用统计：variantId → 使用次数 */
    private final Map<String, Long> usageStats = new ConcurrentHashMap<>();

    // ==================== 版本管理 ====================

    /**
     * 发布新版本的 Prompt
     */
    public PromptVersion publish(String action, String systemPrompt, String userPrompt,
                                  String author, String changeNote) {
        List<PromptVersion> versionList = versions.computeIfAbsent(action, k -> new ArrayList<>());

        int nextVersion = versionList.stream()
                .mapToInt(PromptVersion::version)
                .max().orElse(0) + 1;

        PromptVersion pv = new PromptVersion(
                action + "-v" + nextVersion,
                action,
                nextVersion,
                systemPrompt,
                userPrompt,
                author,
                changeNote,
                Instant.now().toString(),
                true
        );

        // 将旧版本设为非活跃
        versionList.forEach(v -> v.active = false);
        versionList.add(pv);

        log.info("Prompt 已发布: {} v{} ({})", action, nextVersion, author);
        return pv;
    }

    /**
     * 获取当前活跃版本
     */
    public Optional<PromptVersion> getActive(String action) {
        List<PromptVersion> versionList = versions.get(action);
        if (versionList == null || versionList.isEmpty()) return Optional.empty();

        return versionList.stream()
                .filter(v -> v.active)
                .reduce((a, b) -> b); // 取最新版本
    }

    /**
     * 获取指定版本
     */
    public Optional<PromptVersion> getVersion(String action, int version) {
        List<PromptVersion> versionList = versions.get(action);
        if (versionList == null) return Optional.empty();

        return versionList.stream()
                .filter(v -> v.version() == version)
                .findFirst();
    }

    /**
     * 获取版本历史
     */
    public List<PromptVersion> getHistory(String action) {
        return versions.getOrDefault(action, List.of());
    }

    /**
     * 回滚到指定版本
     */
    public Optional<PromptVersion> rollback(String action, int targetVersion) {
        List<PromptVersion> versionList = versions.get(action);
        if (versionList == null) return Optional.empty();

        Optional<PromptVersion> target = versionList.stream()
                .filter(v -> v.version() == targetVersion)
                .findFirst();

        if (target.isPresent()) {
            versionList.forEach(v -> v.active = (v.version() == targetVersion));
            log.info("Prompt 已回滚: {} → v{}", action, targetVersion);
        }

        return target;
    }

    // ==================== A/B 测试 ====================

    /**
     * 创建 A/B 测试
     *
     * @param action   动作名
     * @param variants 变体列表（每个变体有版本号和流量比例）
     */
    public void createAbTest(String action, List<AbVariant> variants) {
        // 验证流量比例总和为 100
        double totalWeight = variants.stream().mapToDouble(AbVariant::weight).sum();
        if (Math.abs(totalWeight - 1.0) > 0.01) {
            throw new IllegalArgumentException("变体流量比例总和必须为 1.0，当前: " + totalWeight);
        }

        AbTestConfig config = new AbTestConfig(
                action + "-ab-" + System.currentTimeMillis(),
                action,
                variants,
                Instant.now().toString(),
                true
        );

        abTests.put(action, config);
        log.info("A/B 测试已创建: {} ({} 个变体)", action, variants.size());
    }

    /**
     * 根据 A/B 测试配置选择 Prompt 变体
     */
    public Optional<PromptVersion> getAbVariant(String action) {
        AbTestConfig config = abTests.get(action);
        if (config == null || !config.active()) {
            return getActive(action);
        }

        // 按权重随机选择变体
        double rand = Math.random();
        double cumulative = 0;
        for (AbVariant variant : config.variants()) {
            cumulative += variant.weight();
            if (rand <= cumulative) {
                usageStats.merge(variant.variantId(), 1L, Long::sum);
                return getVersion(action, variant.version());
            }
        }

        // 兜底：返回活跃版本
        return getActive(action);
    }

    /**
     * 停止 A/B 测试
     */
    public void stopAbTest(String action) {
        AbTestConfig config = abTests.get(action);
        if (config != null) {
            abTests.put(action, new AbTestConfig(
                    config.testId(), config.action(), config.variants(),
                    config.startedAt(), false
            ));
            log.info("A/B 测试已停止: {}", action);
        }
    }

    /**
     * 获取 A/B 测试使用统计
     */
    public Map<String, Object> getAbTestStats(String action) {
        AbTestConfig config = abTests.get(action);
        if (config == null) return Map.of();

        Map<String, Long> variantUsage = new HashMap<>();
        for (AbVariant v : config.variants()) {
            variantUsage.put(v.variantId(), usageStats.getOrDefault(v.variantId(), 0L));
        }

        return Map.of(
                "testId", config.testId(),
                "action", config.action(),
                "active", config.active(),
                "variants", config.variants(),
                "usage", variantUsage
        );
    }

    // ==================== 查询 ====================

    /**
     * 列出所有有版本管理的 action
     */
    public List<String> listActions() {
        return List.copyOf(versions.keySet());
    }

    /**
     * 列出所有活跃的 A/B 测试
     */
    public List<AbTestConfig> listActiveAbTests() {
        return abTests.values().stream()
                .filter(AbTestConfig::active)
                .collect(Collectors.toList());
    }

    // ==================== 数据结构 ====================

    /**
     * Prompt 版本
     */
    public static class PromptVersion {
        private final String versionId;
        private final String action;
        private final int version;
        private final String systemPrompt;
        private final String userPrompt;
        private final String author;
        private final String changeNote;
        private final String createdAt;
        boolean active;

        public PromptVersion(String versionId, String action, int version,
                              String systemPrompt, String userPrompt,
                              String author, String changeNote, String createdAt, boolean active) {
            this.versionId = versionId;
            this.action = action;
            this.version = version;
            this.systemPrompt = systemPrompt;
            this.userPrompt = userPrompt;
            this.author = author;
            this.changeNote = changeNote;
            this.createdAt = createdAt;
            this.active = active;
        }

        public String versionId() { return versionId; }
        public String action() { return action; }
        public int version() { return version; }
        public String systemPrompt() { return systemPrompt; }
        public String userPrompt() { return userPrompt; }
        public String author() { return author; }
        public String changeNote() { return changeNote; }
        public String createdAt() { return createdAt; }
        public boolean active() { return active; }
    }

    /**
     * A/B 测试配置
     */
    public record AbTestConfig(
            String testId,
            String action,
            List<AbVariant> variants,
            String startedAt,
            boolean active
    ) {}

    /**
     * A/B 测试变体
     */
    public record AbVariant(
            String variantId,
            int version,
            double weight,
            String description
    ) {}
}
