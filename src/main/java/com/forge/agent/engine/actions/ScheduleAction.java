package com.forge.agent.engine.actions;

import com.forge.agent.action.BaseAgentAction;
import com.forge.agent.engine.DagEngine;
import com.forge.agent.event.StateEventBus;
import com.forge.agent.metrics.AgentMetrics;
import com.forge.agent.state.MainState;
import com.forge.agent.state.SubTask;
import com.forge.agent.state.TaskNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 调度节点
 * 
 * 改进：
 * 1. DAG 就绪节点判断委托给 DagEngine
 * 2. 继承 BaseAgentAction 消除样板代码
 */
@Component
public class ScheduleAction extends BaseAgentAction {

    private final DagEngine dagEngine;

    public ScheduleAction(AgentMetrics metrics, StateEventBus eventBus, DagEngine dagEngine) {
        super(metrics, eventBus);
        this.dagEngine = dagEngine;
    }

    @Override
    protected MainState doExecute(MainState state) {
        List<TaskNode> taskDAG = state.getTaskDAG();
        if (taskDAG == null || taskDAG.isEmpty()) {
            log.info("DAG任务图为空，无需调度");
            return state;
        }

        // 获取已完成的任务ID集合
        Set<String> completedIds = getCompletedTaskIds(state);

        // 委托 DagEngine 获取就绪节点
        List<TaskNode> readyNodes = dagEngine.getReadyNodes(taskDAG, completedIds);

        if (readyNodes.isEmpty()) {
            log.info("当前没有就绪的任务节点");
            return state;
        }

        // 将就绪节点转换为 SubTask（避免重复添加）
        List<SubTask> existingTasks = state.getSubTasks();
        Set<String> existingTaskIds = existingTasks.stream()
                .map(SubTask::taskId)
                .collect(Collectors.toSet());

        int newCount = 0;
        for (TaskNode readyNode : readyNodes) {
            if (!existingTaskIds.contains(readyNode.nodeId())) {
                SubTask subTask = SubTask.create(
                        readyNode.nodeId(),
                        readyNode.name(),
                        readyNode.description(),
                        readyNode.dependencies() != null ? readyNode.dependencies() : List.of()
                );
                state.addSubTask(subTask);
                newCount++;
                log.info("调度就绪任务: {} - {}", subTask.taskId(), subTask.name());
            }
        }

        log.info("调度完成，新增 {} 个就绪任务，已完成 {} 个任务", newCount, completedIds.size());
        return state;
    }

    private Set<String> getCompletedTaskIds(MainState state) {
        return state.getSubTasks().stream()
                .filter(SubTask::isCompleted)
                .map(SubTask::taskId)
                .collect(Collectors.toSet());
    }
}
