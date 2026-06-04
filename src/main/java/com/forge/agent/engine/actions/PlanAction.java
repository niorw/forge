package com.forge.agent.engine.actions;

import com.forge.agent.action.BaseAgentAction;
import com.forge.agent.engine.DagEngine;
import com.forge.agent.event.AgentEvent;
import com.forge.agent.event.StateEventBus;
import com.forge.agent.llm.LlmGateway;
import com.forge.agent.metrics.AgentMetrics;
import com.forge.agent.spec.SpecManager;
import com.forge.agent.state.MainState;
import com.forge.agent.state.TaskNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * DAG 规划节点 — 支持驳回后增量修改。
 *
 * 核心改进（来自 Anthropic Harness Engineering）：
 * "Sprint 合约——驳回后不应该从零开始，而是在上一轮基础上修改。
 *  plan 节点拿到上一轮 DAG + 驳回原因，不是从零开始，而是在上一轮基础上修改。"
 *
 * 两种模式：
 * 1. 首次规划：从分析结果生成全新 DAG
 * 2. 增量修订：读取 previousDAGJson + rejectionReason，让 LLM 在上一轮基础上修改
 *
 * 修订策略：
 * - 最多修订 3 次，超过则标记失败
 * - 每次修订时，LLM 收到完整的上一轮 DAG + 驳回原因 + 分析结果
 * - LLM 被要求只修改被驳回指出的问题，保持其他部分不变
 */
@Component
public class PlanAction extends BaseAgentAction {

    private final LlmGateway llmGateway;
    private final DagEngine dagEngine;
    private final SpecManager specManager;

    /** 最大修订次数 */
    private static final int MAX_REVISIONS = 3;

    /** 增量修订的 Prompt 模板 */
    private static final String REVISION_PROMPT_TEMPLATE = """
            ## 任务：增量修订 DAG 规划
            
            ### 背景
            你之前的 DAG 规划被人工审批驳回了。这是第 %d 次修订。
            
            ### 驳回原因
            %s
            
            ### 上一轮 DAG 规划（JSON）
            %s
            
            ### 原始需求分析
            %s
            
            ### 修订要求
            1. **只修改被驳回指出的问题**，保持其他部分不变
            2. 如果驳回指出缺少任务，添加新任务
            3. 如果驳回指出任务不合理，修改该任务
            4. 如果驳回指出依赖错误，修正依赖关系
            5. 输出完整的修订后 DAG（不是增量 diff）
            
            ### 输出格式
            严格按 JSON 数组格式输出，与首次规划格式完全一致。
            只输出 JSON 数组，不要任何其他文字。
            """;

    public PlanAction(AgentMetrics metrics, StateEventBus eventBus,
                      LlmGateway llmGateway, DagEngine dagEngine, SpecManager specManager) {
        super(metrics, eventBus);
        this.llmGateway = llmGateway;
        this.dagEngine = dagEngine;
        this.specManager = specManager;
    }

    @Override
    protected MainState doExecute(MainState state) {
        // 清除上一轮的错误状态（驳回后重新规划时必须清除）
        state.setError(null);

        String analysis = state.getAnalysis();
        if (analysis == null || analysis.isBlank()) {
            state.setError("分析结果为空，无法进行DAG规划");
            return state;
        }

        // 加载所有可用 Spec 作为 Planner 的上下文
        Map<String, String> availableSpecs = specManager.loadAllSpecs();
        log.info("Planner 上下文: {} 个 Spec 文件", availableSpecs.size());

        List<TaskNode> taskDAG;

        if (state.isRevision() && state.getPreviousDAGJson() != null) {
            // ===== 增量修订模式 =====
            taskDAG = doRevision(state, analysis, availableSpecs);
        } else {
            // ===== 首次规划模式 =====
            taskDAG = doFirstPlan(state, analysis, availableSpecs);
        }

        if (taskDAG == null) {
            // 规划失败，error 已在子方法中设置
            return state;
        }

        // 校验 DAG
        List<String> errors = dagEngine.validateDAG(taskDAG);
        if (!errors.isEmpty()) {
            state.setError("DAG校验失败: " + String.join("; ", errors));
            return state;
        }

        state.setTaskDAG(taskDAG);

        // 统计 Spec 绑定情况
        long boundCount = taskDAG.stream().filter(TaskNode::hasSpec).count();
        log.info("DAG规划完成: {} 个节点, {} 个绑定了 Spec (修订次数: {})",
                taskDAG.size(), boundCount, state.getRevisionCount());

        // 清除驳回相关状态（规划成功）
        state.setRejectionReason(null);
        state.setPreviousDAGJson(null);

        return state;
    }

    /**
     * 首次规划 — 从分析结果生成全新 DAG
     */
    private List<TaskNode> doFirstPlan(MainState state, String analysis, Map<String, String> availableSpecs) {
        log.info("首次 DAG 规划...");
        return llmGateway.planDAG(analysis, availableSpecs);
    }

    /**
     * 增量修订 — 基于上一轮 DAG + 驳回原因修改
     */
    private List<TaskNode> doRevision(MainState state, String analysis, Map<String, String> availableSpecs) {
        int revisionCount = state.getRevisionCount();

        // 检查修订次数上限
        if (revisionCount >= MAX_REVISIONS) {
            state.setError("已达最大修订次数 (" + MAX_REVISIONS + ")，请人工介入");
            log.error("DAG 规划已达最大修订次数，停止重试");
            return null;
        }

        String rejectionReason = state.getRejectionReason() != null
                ? state.getRejectionReason() : "未说明原因";
        String previousDAG = state.getPreviousDAGJson();

        log.info("增量修订模式: 第 {} 次修订, 驳回原因: {}", revisionCount, rejectionReason);

        // 构建修订 Prompt
        String revisionPrompt = String.format(REVISION_PROMPT_TEMPLATE,
                revisionCount, rejectionReason, previousDAG, analysis);

        // 记录修订事件
        eventBus().fireNodeStarted(state, "plan-revision-" + revisionCount);

        // 调用 LLM 做增量修订
        String response = llmCall("plan-revision",
                () -> llmGateway.chatWithRole("planner", "plan", revisionPrompt));

        // 解析响应为 TaskNode 列表
        try {
            return com.forge.agent.llm.SpringAiLlmGateway.ResponseParser.parseTaskDAG(
                    response, new com.fasterxml.jackson.databind.ObjectMapper());
        } catch (Exception e) {
            state.setError("增量修订解析失败: " + e.getMessage());
            log.error("增量修订 DAG 解析失败", e);
            return null;
        }
    }
}
