package com.forge.agent.context;

import com.forge.agent.skill.SkillRegistry;
import com.forge.agent.state.MainState;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 上下文组装器
 *
 * 核心职责：在每次 LLM 调用前，根据角色和动作组装完整的 prompt 上下文。
 *
 * 三层上下文来源：
 * 1. ContextStrategy — 角色特定的状态上下文（从 MainState 提取）
 * 2. SkillRegistry — 角色适用的知识注入（SDD规范/审查清单/业务上下文）
 * 3. PromptRegistry — 动作特定的提示词模板
 *
 * 参考 Hermes 的 prompt_builder.py 设计模式：
 * - 从多个数据源组装上下文（state, spec, history, skills）
 * - 按角色裁剪上下文（不同角色需要的信息不同）
 * - 上下文有长度限制，超长时自动截断最早的
 *
 * 使用方式：
 * <pre>
 *   PromptContext ctx = contextBuilder.buildWithContext("planner", "planDAG", mainState);
 *   llmGateway.chatWithRole("planner", "planDAG", ctx.userPrompt());
 * </pre>
 */
@Component
public class ContextBuilder {

    /** 默认最大字符数 */
    private static final int DEFAULT_MAX_CHARS = 8000;

    /** 角色 → 策略映射，由 Spring 自动注入所有 ContextStrategy 实现 */
    private final Map<String, ContextStrategy> strategyMap;

    /** Skill 注册中心（知识注入） */
    private final SkillRegistry skillRegistry;

    /** 全局最大字符数限制（可配置） */
    private final int maxChars;

    /**
     * 构造注入：Spring 自动收集所有 ContextStrategy @Component + SkillRegistry
     */
    public ContextBuilder(List<ContextStrategy> strategies, SkillRegistry skillRegistry) {
        this(strategies, skillRegistry, DEFAULT_MAX_CHARS);
    }

    public ContextBuilder(List<ContextStrategy> strategies, SkillRegistry skillRegistry, int maxChars) {
        Assert.notEmpty(strategies, "至少需要一个 ContextStrategy 实现");
        this.skillRegistry = skillRegistry;
        this.maxChars = maxChars;
        this.strategyMap = strategies.stream()
            .collect(Collectors.toMap(ContextStrategy::getRole, Function.identity()));
    }

    /**
     * 构建 system prompt（角色人设 + 行为指令 + Skill 知识注入）
     *
     * @param role   角色名（planner/architect/reviewer/worker）
     * @param action 当前动作名
     * @return system prompt（含 Skill 知识）
     */
    public String buildSystemPrompt(String role, String action) {
        ContextStrategy strategy = getStrategy(role);
        String baseSystemPrompt = strategy.buildSystemPrompt(action);

        // 注入角色适用的 Skill 知识
        String skillKnowledge = skillRegistry.getSkillsForRole(role);
        if (skillKnowledge.isEmpty()) {
            return baseSystemPrompt;
        }

        return baseSystemPrompt + "\n\n---\n\n## 参考知识（Skill）\n\n"
                + "以下是团队沉淀的专业知识，请在执行任务时参考：\n\n"
                + skillKnowledge;
    }

    /**
     * 构建 user prompt（从 state 中提取角色所需的上下文，并做长度裁剪）
     *
     * @param state  主状态
     * @param role   角色名
     * @param action 当前动作名
     * @return 经过长度裁剪的 user prompt
     */
    public String buildUserPrompt(MainState state, String role, String action) {
        ContextStrategy strategy = getStrategy(role);
        String raw = strategy.buildUserPrompt(state, action);

        // 按策略级别的 maxTokens 裁剪（1 token ≈ 2 中文字符）
        int strategyMaxChars = strategy.getMaxTokens() * 2;
        // 取全局限制和策略限制的较小值
        int effectiveMax = Math.min(maxChars, strategyMaxChars);

        return enforceCharLimit(raw, effectiveMax);
    }

    /**
     * 一步组装完整上下文（system + user + token 估算 + Skill 注入）
     *
     * @param role   角色名
     * @param action 当前动作名
     * @param state  主状态
     * @return PromptContext record，包含 systemPrompt、userPrompt、estimatedTokens
     */
    public PromptContext buildWithContext(String role, String action, MainState state) {
        String systemPrompt = buildSystemPrompt(role, action);
        String userPrompt = buildUserPrompt(state, role, action);

        // 粗估 token 数：中文约 1 字 = 2 token，英文约 4 字符 = 1 token
        int estimatedTokens = estimateTokens(systemPrompt) + estimateTokens(userPrompt);

        return new PromptContext(systemPrompt, userPrompt, estimatedTokens);
    }

    /**
     * 获取所有已注册的角色名
     */
    public java.util.Set<String> getRegisteredRoles() {
        return strategyMap.keySet();
    }

    // ==================== 内部方法 ====================

    private ContextStrategy getStrategy(String role) {
        ContextStrategy strategy = strategyMap.get(role);
        if (strategy == null) {
            throw new IllegalArgumentException(
                "未知角色: " + role + "，已注册角色: " + strategyMap.keySet());
        }
        return strategy;
    }

    /**
     * 强制字符数限制，超长时从头部截断（保留最新的上下文）
     */
    private String enforceCharLimit(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        // 从头部截断，保留尾部（最新的上下文通常在末尾）
        return "[前面的上下文因长度限制已省略...]\n" + text.substring(text.length() - maxChars);
    }

    /**
     * 粗估 token 数
     * 策略：中文字符按 2 token，其他按 0.25 token/字符
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int chineseChars = 0;
        int otherChars = 0;
        for (char c : text.toCharArray()) {
            if (Character.toString(c).matches("[\\u4e00-\\u9fff]")) {
                chineseChars++;
            } else {
                otherChars++;
            }
        }
        return chineseChars * 2 + otherChars / 4;
    }

    // ==================== PromptContext Record ====================

    /**
     * 组装好的上下文
     *
     * @param systemPrompt    系统提示词（含 Skill 知识注入）
     * @param userPrompt      用户提示词（含状态上下文）
     * @param estimatedTokens 预估 token 数
     */
    public record PromptContext(
        String systemPrompt,
        String userPrompt,
        int estimatedTokens
    ) {}
}
