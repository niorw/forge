package com.forge.agent.engine.actions;

import com.forge.agent.action.BaseAgentAction;
import com.forge.agent.event.StateEventBus;
import com.forge.agent.llm.LlmGateway;
import com.forge.agent.metrics.AgentMetrics;
import com.forge.agent.state.MainState;
import com.forge.agent.state.SubTask;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试节点
 *
 * Tester Agent 根据验收标准为每个已完成的子任务生成并执行测试，
 * 将测试结果附加到子任务记录中。
 */
@Component
public class TestAction extends BaseAgentAction {

    private final LlmGateway llmGateway;

    public TestAction(AgentMetrics metrics, StateEventBus eventBus, LlmGateway llmGateway) {
        super(metrics, eventBus);
        this.llmGateway = llmGateway;
    }

    @Override
    protected MainState doExecute(MainState state) {
        List<SubTask> subTasks = state.getSubTasks();
        if (subTasks == null || subTasks.isEmpty()) {
            log.warn("没有子任务需要测试");
            return state;
        }

        List<SubTask> updatedTasks = new ArrayList<>();
        int testedCount = 0;

        for (SubTask subTask : subTasks) {
            // 只对已完成（SUCCESS）的子任务生成测试
            if (!"SUCCESS".equals(subTask.status()) || subTask.result() == null) {
                log.info("跳过未完成的子任务: {} (status={})", subTask.name(), subTask.status());
                updatedTasks.add(subTask);
                continue;
            }

            try {
                // 构造验收标准：使用任务描述作为默认验收标准
                String acceptanceCriteria = subTask.description() != null
                    ? subTask.description()
                    : "功能正确、边界条件覆盖、异常处理完善";

                log.info("为子任务 [{}] 生成测试...", subTask.name());
                String testReport = llmGateway.generateTest(subTask.result(), acceptanceCriteria);

                // 将测试报告追加到子任务结果中
                String enrichedResult = subTask.result() + "\n\n## 测试报告\n" + testReport;
                updatedTasks.add(subTask.markSuccess(enrichedResult));
                testedCount++;
                log.info("子任务 [{}] 测试完成", subTask.name());

            } catch (Exception e) {
                log.error("子任务 [{}] 测试生成失败", subTask.name(), e);
                updatedTasks.add(subTask.markFailed("测试生成失败: " + e.getMessage()));
            }
        }

        state.setSubTasks(updatedTasks);
        log.info("测试阶段完成，共测试 {} 个子任务", testedCount);
        return state;
    }
}
