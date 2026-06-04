package com.forge.agent.engine.actions;

import com.forge.agent.action.BaseAgentAction;
import com.forge.agent.event.StateEventBus;
import com.forge.agent.llm.LlmGateway;
import com.forge.agent.metrics.AgentMetrics;
import com.forge.agent.state.MainState;
import com.forge.agent.state.SubTask;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 集成测试节点
 *
 * 收集所有已完成子任务的执行结果，调用 LLM 进行跨服务集成验证，
 * 确保各子服务之间的协作和数据流符合预期。
 */
@Component
public class IntegrationTestAction extends BaseAgentAction {

    private final LlmGateway llmGateway;

    public IntegrationTestAction(AgentMetrics metrics, StateEventBus eventBus, LlmGateway llmGateway) {
        super(metrics, eventBus);
        this.llmGateway = llmGateway;
    }

    @Override
    protected MainState doExecute(MainState state) {
        List<SubTask> subTasks = state.getSubTasks();
        if (subTasks == null || subTasks.isEmpty()) {
            state.setError("没有子任务结果可供集成测试");
            return state;
        }

        // 收集所有已完成子任务的结果
        Map<String, String> taskResults = new LinkedHashMap<>();
        for (SubTask subTask : subTasks) {
            if ("SUCCESS".equals(subTask.status()) && subTask.result() != null) {
                taskResults.put(subTask.name(), subTask.result());
            }
        }

        if (taskResults.isEmpty()) {
            state.setError("没有成功完成的子任务，无法进行集成测试");
            return state;
        }

        // 使用需求分析结果作为架构描述的上下文
        String architecture = state.getAnalysis() != null
            ? state.getAnalysis()
            : "未提供架构描述";

        log.info("开始集成测试，共 {} 个子任务结果待验证", taskResults.size());

        try {
            String integrationReport = llmGateway.integrationTest(taskResults, architecture);

            // 将集成测试报告设置为最终结果的一部分
            String existingResult = state.getFinalResult();
            String fullResult = (existingResult != null ? existingResult + "\n\n" : "")
                + "## 集成测试报告\n" + integrationReport;
            state.setFinalResult(fullResult);

            log.info("集成测试完成，报告长度: {}", integrationReport.length());
        } catch (Exception e) {
            log.error("集成测试执行失败", e);
            state.setError("集成测试失败: " + e.getMessage());
        }

        return state;
    }
}
