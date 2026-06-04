package com.forge.agent.engine;

import com.forge.agent.engine.actions.*;
import com.forge.agent.state.MainState;
import com.forge.agent.state.SubTask;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.MysqlSaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;

/**
 * LangGraph4j 主流程编排（完整 10 阶段 SDD 流程）
 *
 * 流程：
 * START → analyze → architecture → specAuthor
 *       → specReview (质量门：人审查 Spec)
 *       → plan → approval (质量门：人审批 DAG)
 *       → schedule → dispatch → collect (回环)
 *       → review → securityReview → test → integrationTest
 *       → accept (质量门：人验收)
 *       → integrate → END
 *
 * 四个 interruptsBefore 中断点：
 * 1. specReview     — 审查接口契约
 * 2. approval       — 审批 DAG 任务拆解
 * 3. accept         — 确认验收报告
 *
 * 质量门禁升级（来自 Harness Engineering）：
 * - review: 独立 Code Reviewer Agent，VERDICT 协议，工具权限隔离
 * - securityReview: 独立 Security Reviewer Agent，安全清单检查
 * - 两个审查节点任一 FAIL → 流水线标记失败
 */
@Component
public class MainAgentGraph {

    private static final Logger log = LoggerFactory.getLogger(MainAgentGraph.class);

    private final CompiledGraph<MainState> compiledWorkflow;

    public MainAgentGraph(
            AnalyzeAction analyzeAction,
            ArchitectureAction architectureAction,
            SpecAuthorAction specAuthorAction,
            SpecReviewAction specReviewAction,
            PlanAction planAction,
            ApprovalAction approvalAction,
            ScheduleAction scheduleAction,
            DispatchAction dispatchAction,
            CollectAction collectAction,
            ReviewAction reviewAction,
            SecurityReviewAction securityReviewAction,
            TestAction testAction,
            IntegrationTestAction integrationTestAction,
            AcceptAction acceptAction,
            IntegrateAction integrateAction,
            MysqlSaver mysqlSaver) {

        try {
            // ===== 定义 StateGraph =====
            StateGraph<MainState> graph = new StateGraph<>(MainState::new)

                    // ===== 阶段 1-3：规划与 Spec =====
                    .addNode("analyze", node_async(state -> {
                        analyzeAction.execute(state);
                        return Map.of();
                    }))
                    .addNode("architecture", node_async(state -> {
                        architectureAction.execute(state);
                        return Map.of();
                    }))
                    .addNode("specAuthor", node_async(state -> {
                        specAuthorAction.execute(state);
                        return Map.of();
                    }))
                    .addNode("specReview", node_async(state -> {
                        specReviewAction.execute(state);
                        return Map.of();
                    }))
                    .addNode("plan", node_async(state -> {
                        planAction.execute(state);
                        return Map.of();
                    }))
                    .addNode("approval", node_async(state -> {
                        approvalAction.execute(state);
                        return Map.of();
                    }))

                    // ===== 阶段 4-6：并行执行 =====
                    .addNode("schedule", node_async(state -> {
                        scheduleAction.execute(state);
                        return Map.of();
                    }))
                    .addNode("dispatch", node_async(state -> {
                        dispatchAction.execute(state);
                        return Map.of();
                    }))
                    .addNode("collect", node_async(state -> {
                        collectAction.execute(state);
                        return Map.of();
                    }))

                    // ===== 阶段 7：对抗性审查（双 Agent 独立审查） =====
                    .addNode("review", node_async(state -> {
                        reviewAction.execute(state);
                        return Map.of();
                    }))
                    .addNode("securityReview", node_async(state -> {
                        securityReviewAction.execute(state);
                        return Map.of();
                    }))

                    // ===== 阶段 8-10：测试、验收与集成 =====
                    .addNode("test", node_async(state -> {
                        testAction.execute(state);
                        return Map.of();
                    }))
                    .addNode("integrationTest", node_async(state -> {
                        integrationTestAction.execute(state);
                        return Map.of();
                    }))
                    .addNode("accept", node_async(state -> {
                        acceptAction.execute(state);
                        return Map.of();
                    }))
                    .addNode("integrate", node_async(state -> {
                        integrateAction.execute(state);
                        return Map.of();
                    }))

                    // ===== 串行边：阶段 1-3 =====
                    .addEdge(START, "analyze")
                    .addEdge("analyze", "architecture")
                    .addEdge("architecture", "specAuthor")
                    .addEdge("specAuthor", "specReview")
                    .addEdge("specReview", "plan")
                    .addEdge("plan", "approval")

                    // ===== 条件边：审批路由 =====
                    .addConditionalEdges("approval",
                            edge_async(state -> {
                                String decision = state.getApprovalStatus();
                                if (decision == null) decision = "PENDING";
                                log.info("审批决策路由: {}", decision);
                                return decision;
                            }),
                            Map.of(
                                    "APPROVED", "schedule",
                                    "REJECTED", "plan"
                            ))

                    // ===== 调度 → 派发 → 收集 =====
                    .addEdge("schedule", "dispatch")
                    .addEdge("dispatch", "collect")

                    // ===== 条件边：完成检测回环 =====
                    .addConditionalEdges("collect",
                            edge_async(state -> {
                                List<SubTask> tasks = state.getSubTasks();
                                boolean allDone = tasks != null && !tasks.isEmpty()
                                        && tasks.stream().allMatch(SubTask::isCompleted);
                                String decision = allDone ? "done" : "more";
                                log.info("收集完成检测: {} (总任务: {}, 已完成: {})",
                                        decision,
                                        tasks != null ? tasks.size() : 0,
                                        tasks != null ? tasks.stream().filter(SubTask::isCompleted).count() : 0);
                                return decision;
                            }),
                            Map.of(
                                    "done", "review",
                                    "more", "schedule"
                            ))

                    // ===== 审查链路：代码审查 → 安全审查 → 测试 =====
                    // review 和 securityReview 串行执行，各自独立给出 VERDICT
                    .addEdge("review", "securityReview")

                    // ===== 条件边：审查结果路由 =====
                    // 任一审查 FAIL → 跳转到 accept（标记审查未通过）
                    // 全部 PASS → 继续到 test
                    .addConditionalEdges("securityReview",
                            edge_async(state -> {
                                boolean passed = state.isReviewPassed();
                                String decision = passed ? "PASS" : "FAIL";
                                log.info("审查结果路由: {} (代码审查: {}, 安全审查: {})",
                                        decision,
                                        state.getCodeVerdict() != null ? state.getCodeVerdict().decision() : "N/A",
                                        state.getSecurityVerdict() != null ? state.getSecurityVerdict().decision() : "N/A");
                                return decision;
                            }),
                            Map.of(
                                    "PASS", "test",
                                    "FAIL", "accept"
                            ))

                    // ===== 测试 → 集成测试 → 验收 → 集成 → END =====
                    .addEdge("test", "integrationTest")
                    .addEdge("integrationTest", "accept")
                    .addEdge("accept", "integrate")
                    .addEdge("integrate", END);

            // ===== 编译：checkpoint + 三个中断点 =====
            CompileConfig compileConfig = CompileConfig.builder()
                    .checkpointSaver(mysqlSaver)
                    .releaseThread(false)
                    .interruptsBefore(Set.of("specReview", "approval", "accept"))
                    .build();

            this.compiledWorkflow = graph.compile(compileConfig);
            log.info("✅ LangGraph4j StateGraph 编译完成: 16 个节点, 3 个条件边, 3 个中断点");

        } catch (Exception e) {
            log.error("StateGraph 编译失败", e);
            throw new RuntimeException("StateGraph 编译失败: " + e.getMessage(), e);
        }
    }

    public CompiledGraph<MainState> getWorkflow() {
        return compiledWorkflow;
    }

    /**
     * 启动新的 Agent 执行
     */
    public MainState startExecution(String requirement, String threadId) {
        MainState initialState = new MainState();
        initialState.setRequirement(requirement);

        log.info("启动 Agent 执行: threadId={}, requirement={}",
                threadId,
                requirement.length() > 50 ? requirement.substring(0, 50) + "..." : requirement);

        var config = org.bsc.langgraph4j.RunnableConfig.builder()
                .threadId(threadId)
                .build();

        var result = compiledWorkflow.invoke(initialState.data(), config);
        return result.map(MainState::new).orElse(initialState);
    }

    /**
     * 审批后恢复执行
     */
    public MainState resumeAfterApproval(String threadId, boolean approved, String comment) {
        log.info("审批后恢复执行: threadId={}, approved={}", threadId, approved);

        var config = org.bsc.langgraph4j.RunnableConfig.builder()
                .threadId(threadId)
                .build();

        var lastSnapshot = compiledWorkflow.lastStateOf(config);
        if (lastSnapshot.isEmpty()) {
            throw new RuntimeException("未找到 checkpoint 状态: threadId=" + threadId);
        }

        MainState state = lastSnapshot.get().state();
        state.setApprovalStatus(approved ? "APPROVED" : "REJECTED");

        var result = compiledWorkflow.invoke(state.data(), config);
        return result.map(MainState::new).orElse(state);
    }
}
