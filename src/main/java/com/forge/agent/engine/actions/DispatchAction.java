package com.forge.agent.engine.actions;

import com.forge.agent.action.BaseAgentAction;
import com.forge.agent.event.StateEventBus;
import com.forge.agent.gateway.TaskDispatcher;
import com.forge.agent.metrics.AgentMetrics;
import com.forge.agent.mq.TaskMessage;
import com.forge.agent.state.MainState;
import com.forge.agent.state.SubSpec;
import com.forge.agent.state.SubTask;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 派发节点
 * 
 * 改进：
 * 1. 通过 TaskDispatcher 接口派发任务，不再硬编码 Redis
 * 2. 继承 BaseAgentAction 消除样板代码
 */
@Component
public class DispatchAction extends BaseAgentAction {

    private final TaskDispatcher taskDispatcher;

    public DispatchAction(AgentMetrics metrics, StateEventBus eventBus, TaskDispatcher taskDispatcher) {
        super(metrics, eventBus);
        this.taskDispatcher = taskDispatcher;
    }

    @Override
    protected MainState doExecute(MainState state) {
        List<SubTask> subTasks = state.getSubTasks();
        if (subTasks == null || subTasks.isEmpty()) {
            log.info("没有待派发的子任务");
            return state;
        }

        // 获取 Spec 映射（taskId -> SubSpec）
        Map<String, SubSpec> specMap = new java.util.HashMap<>();
        List<SubSpec> subSpecs = state.getSubSpecs();
        if (subSpecs != null) {
            specMap = subSpecs.stream()
                    .collect(Collectors.toMap(SubSpec::taskId, s -> s, (a, b) -> a));
        }

        // 筛选 PENDING 状态的子任务
        List<SubTask> pendingTasks = subTasks.stream()
                .filter(task -> "PENDING".equals(task.status()))
                .toList();

        if (pendingTasks.isEmpty()) {
            log.info("没有 PENDING 状态的子任务需要派发");
            return state;
        }

        int dispatched = 0;
        for (SubTask task : pendingTasks) {
            try {
                SubSpec spec = specMap.get(task.taskId());
                String specContent = spec != null ? spec.content() : "";

                TaskMessage message = new TaskMessage(
                        task.taskId(), task.name(), task.description(),
                        specContent, task.dependencies(), System.currentTimeMillis()
                );

                // 通过接口派发（不关心具体是 Redis/RabbitMQ/Kafka）
                taskDispatcher.dispatch(message);

                // 更新状态为 RUNNING
                updateTaskStatus(state, task.taskId(), "RUNNING");

                metrics().recordTaskDispatch();
                eventBus().fireTaskDispatched(state, task.taskId());
                dispatched++;

                log.info("子任务已派发: {} - {}", task.taskId(), task.name());

            } catch (Exception e) {
                log.error("子任务派发失败: {}", task.taskId(), e);
                updateTaskStatus(state, task.taskId(), "FAILED");
            }
        }

        log.info("派发完成，共派发 {} 个子任务", dispatched);
        return state;
    }

    private void updateTaskStatus(MainState state, String taskId, String status) {
        List<SubTask> tasks = state.getSubTasks();
        for (int i = 0; i < tasks.size(); i++) {
            SubTask task = tasks.get(i);
            if (task.taskId().equals(taskId)) {
                switch (status) {
                    case "RUNNING" -> tasks.set(i, task.markRunning());
                    case "FAILED" -> tasks.set(i, task.markFailed("派发失败"));
                }
                break;
            }
        }
    }
}
