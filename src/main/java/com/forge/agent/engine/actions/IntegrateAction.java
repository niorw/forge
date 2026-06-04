package com.forge.agent.engine.actions;

import com.forge.agent.action.BaseAgentAction;
import com.forge.agent.event.StateEventBus;
import com.forge.agent.llm.LlmGateway;
import com.forge.agent.metrics.AgentMetrics;
import com.forge.agent.state.MainState;
import com.forge.agent.state.SubTask;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 集成验证节点
 * 
 * 改进：继承 BaseAgentAction 消除样板代码
 */
@Component
public class IntegrateAction extends BaseAgentAction {

    private final LlmGateway llmGateway;

    public IntegrateAction(AgentMetrics metrics, StateEventBus eventBus, LlmGateway llmGateway) {
        super(metrics, eventBus);
        this.llmGateway = llmGateway;
    }

    @Override
    protected MainState doExecute(MainState state) {
        List<SubTask> subTasks = state.getSubTasks();
        if (subTasks == null || subTasks.isEmpty()) {
            state.setError("没有子任务结果可集成");
            return state;
        }

        // 检查未完成任务
        List<SubTask> incompleteTasks = subTasks.stream()
                .filter(task -> !task.isCompleted())
                .toList();
        if (!incompleteTasks.isEmpty()) {
            state.setError("存在未完成的子任务: " +
                    incompleteTasks.stream().map(SubTask::name).collect(Collectors.joining(", ")));
            return state;
        }

        // 检查失败任务（警告但不阻断）
        long failedCount = subTasks.stream()
                .filter(task -> "FAILED".equals(task.status()))
                .count();
        if (failedCount > 0) {
            log.warn("存在 {} 个失败的子任务", failedCount);
        }

        // LLM 集成验证
        String resultsSummary = buildResultsSummary(subTasks);
        String finalResult = llmGateway.integrateResults(resultsSummary);

        state.setFinalResult(finalResult);
        state.setPhase(MainState.Phase.DONE.name());
        metrics().recordTaskComplete();

        log.info("集成验证完成");
        return state;
    }

    private String buildResultsSummary(List<SubTask> subTasks) {
        StringBuilder sb = new StringBuilder();
        for (SubTask task : subTasks) {
            sb.append(String.format("### %s (状态: %s)\n", task.name(), task.status()));
            if (task.result() != null) {
                sb.append(task.result()).append("\n\n");
            }
            if (task.error() != null) {
                sb.append("错误: ").append(task.error()).append("\n\n");
            }
        }
        return sb.toString();
    }
}
