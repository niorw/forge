package com.forge.agent.llm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词注册中心
 * 
 * 职责：
 * - 集中管理所有 LLM 提示词模板
 * - 支持运行时替换提示词（A/B 测试、灰度发布）
 * - 支持按 action name 查找，避免硬编码在 LLM 客户端中
 * 
 * 设计模式：Registry + Strategy
 * 
 * 用法：
 *   promptRegistry.register("analyze", new PromptTemplate(systemPrompt, null));
 *   PromptTemplate pt = promptRegistry.get("analyze");
 *   String result = llmGateway.chat("analyze", pt.system(), pt.user());
 */
public class PromptRegistry {

    private final Map<String, PromptTemplate> prompts = new ConcurrentHashMap<>();

    /**
     * 注册提示词模板
     */
    public void register(String action, PromptTemplate template) {
        prompts.put(action, template);
    }

    /**
     * 获取提示词模板
     * 
     * @throws IllegalArgumentException 如果 action 未注册
     */
    public PromptTemplate get(String action) {
        PromptTemplate template = prompts.get(action);
        if (template == null) {
            throw new IllegalArgumentException("未注册的提示词模板: " + action);
        }
        return template;
    }

    /**
     * 获取系统提示词（便利方法）
     */
    public String systemPrompt(String action) {
        return get(action).system();
    }

    /**
     * 检查是否已注册
     */
    public boolean has(String action) {
        return prompts.containsKey(action);
    }

    /**
     * 提示词模板
     * 
     * @param system 系统提示词（角色定义、输出格式约束）
     * @param user   用户提示词模板（可用 {} 作为占位符，由调用方填充）
     */
    public record PromptTemplate(String system, String user) {}

    // ==================== 默认提示词注册 ====================

    /**
     * 创建包含默认提示词的注册中心
     * 可以被 application.yml 或数据库中的配置覆盖
     */
    public static PromptRegistry withDefaults() {
        PromptRegistry registry = new PromptRegistry();

        registry.register("analyze", new PromptTemplate(
            "你是一个资深的需求分析师。请分析用户需求，输出结构化的分析结果。" +
            "按以下格式输出：\n" +
            "1. 需求概述：一句话总结需求目标\n" +
            "2. 功能拆解：列出所有需要实现的功能点\n" +
            "3. 技术难点：列出可能的技术挑战\n" +
            "4. 依赖关系：功能点之间的依赖关系\n" +
            "5. 预估工作量：每个功能点的预估复杂度（高/中/低）",
            null
        ));

        registry.register("plan", new PromptTemplate(
            "你是一个资深的项目规划师。根据需求分析结果和可用的 Spec 文件，生成 DAG 任务规划。\n\n" +
            "严格按JSON数组格式输出，每个元素包含：\n" +
            "- nodeId: 唯一标识（英文短横线格式）\n" +
            "- name: 任务名称\n" +
            "- description: 任务描述\n" +
            "- dependencies: 依赖的节点ID数组（可为空数组）\n" +
            "- requiresApproval: 是否需要人工审批（boolean）\n" +
            "- priority: 优先级（1-10）\n" +
            "- specRef: 引用的 Spec 文件名（必须从可用 Spec 中选择，不能为 null）\n" +
            "- specScope: Spec 内的作用域（如方法名 'methods.refundOrder'，或 'all' 表示整个 Spec）\n" +
            "- acceptanceCriteria: 验收标准 ID 数组（从 Spec 的 acceptance_criteria 中选取）\n\n" +
            "重要规则：\n" +
            "1. 每个 Task 必须绑定至少一个 Spec\n" +
            "2. specRef 必须是提供的 Spec 文件名之一\n" +
            "3. acceptanceCriteria 必须是对应 Spec 中定义的 ID\n\n" +
            "只输出JSON数组，不要任何其他文字。",
            null
        ));

        registry.register("generate-spec", new PromptTemplate(
            "你是一个资深的技术架构师。请为以下任务生成详细的执行规格说明（Spec）。\n" +
            "必须包含以下章节（用 ## 标题）：\n" +
            "## 目标\n## 输入\n## 输出\n## 执行步骤\n## 验收标准\n## 约束条件",
            "任务名称：{}\n任务描述：{}\n任务类型：{}"
        ));

        registry.register("integrate", new PromptTemplate(
            "你是一个资深的技术架构师。请对所有子任务的执行结果进行集成验证。\n" +
            "按以下维度输出集成验证报告：\n" +
            "1. 执行摘要（任务数/成功/失败）\n" +
            "2. 功能完整性\n" +
            "3. 接口一致性\n" +
            "4. 数据一致性\n" +
            "5. 风险点和建议\n" +
            "6. 结论",
            "子任务结果汇总：\n{}"
        ));

        registry.register("child-agent", new PromptTemplate(
            "你是一个专业的执行Agent。请根据以下Spec执行任务，输出执行结果。\n" +
            "严格按照Spec中的步骤执行，输出每一步的结果。",
            "任务名称：{}\n任务描述：{}\n\n执行规格（Spec）：\n{}"
        ));

        registry.register("code-review", new PromptTemplate(
            "你是一个极度严格和怀疑的代码审查员。对每个函数都假设它有问题，直到证明它没问题。\n" +
            "不要被表面的代码整洁所迷惑，深入检查逻辑正确性。\n\n" +
            "审查维度：接口一致性、错误码完整性、边界条件、类型安全、测试有效性、架构合理性。\n\n" +
            "输出要求：\n" +
            "1. 逐项检查每个维度\n" +
            "2. 最后一行必须是：VERDICT: PASS 或 VERDICT: FAIL\n" +
            "3. 如果 FAIL，每个问题必须包含位置、实际行为、预期行为、根因分析",
            null
        ));

        registry.register("security-review", new PromptTemplate(
            "你是一个专业的安全审查员，负责检查代码的安全漏洞。\n" +
            "审查维度：SQL注入、越权访问、敏感信息泄露、超时熔断、输入校验、并发安全。\n\n" +
            "输出要求：\n" +
            "1. 逐项检查安全审查清单\n" +
            "2. 最后一行必须是：VERDICT: PASS 或 VERDICT: FAIL\n" +
            "3. 如果 FAIL，每个安全问题必须包含位置、实际行为、预期行为、根因分析\n" +
            "4. 宁可误报也不要漏报",
            null
        ));

        return registry;
    }
}
