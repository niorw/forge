package com.forge.agent.llm;

import com.forge.agent.state.TaskNode;
import com.forge.agent.mq.TaskMessage;

import java.util.List;
import java.util.Map;

/**
 * LLM 网关接口
 *
 * 所有方法都支持角色感知：不同角色自动路由到不同模型
 * 角色：planner(分析/规划)、architect(架构/Spec)、reviewer(审查)、worker(代码生成)
 */
public interface LlmGateway {

    String chat(String action, String userPrompt);
    String chat(String action, String systemPrompt, String userPrompt);

    /**
     * 角色感知的 LLM 调用（自动路由到对应模型）
     *
     * @param role   角色：planner/architect/reviewer/worker
     * @param action 动作名（用于获取 prompt 模板）
     * @param userPrompt 用户输入
     * @return LLM 响应
     */
    String chatWithRole(String role, String action, String userPrompt);

    String analyzeRequirement(String requirement);

    /**
     * DAG 规划（带 Spec 上下文）
     * 
     * @param analysis 需求分析结果
     * @param availableSpecs 可用的 Spec Map<文件名, 内容>，Planner 据此绑定 Spec 到 Task
     * @return TaskNode 列表（每个 Task 带 specRef/specScope/acceptanceCriteria）
     */
    List<TaskNode> planDAG(String analysis, Map<String, String> availableSpecs);

    String generateSpec(TaskNode taskNode);
    String integrateResults(String resultsSummary);
    String executeChildAgent(TaskMessage message);

    /**
     * 根据代码和验收标准生成测试用例
     *
     * @param code              子任务生成的代码/实现
     * @param acceptanceCriteria 验收标准描述
     * @return 测试报告（含测试用例、执行结果、覆盖率等）
     */
    String generateTest(String code, String acceptanceCriteria);

    /**
     * 跨服务集成测试
     *
     * @param taskResults   各子任务的执行结果 Map<任务名, 结果>
     * @param architecture  架构描述/需求分析上下文
     * @return 集成测试报告（含接口一致性、数据流验证、端到端场景等）
     */
    String integrationTest(Map<String, String> taskResults, String architecture);

    /**
     * 架构设计
     *
     * @param requirement 原始需求描述
     * @param analysis    需求分析结果
     * @return 架构设计方案（服务拆分、依赖图、技术选型）
     */
    String designArchitecture(String requirement, String analysis);

    /**
     * 为子任务生成详细 Spec
     *
     * @param taskDescription 任务描述
     * @param architecture    架构设计上下文
     * @param specContent     已有的 Spec 内容（可能为空）
     * @return 生成的 SubSpec 内容（YAML/Markdown 格式）
     */
    String generateSubSpec(String taskDescription, String architecture, String specContent);

    /**
     * 审查代码
     *
     * @param code              待审查的代码
     * @param specContent       对应的 Spec 内容
     * @param acceptanceCriteria 验收标准
     * @return 审查结果
     */
    String reviewCode(String code, String specContent, String acceptanceCriteria);
}
