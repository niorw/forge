package com.forge.agent.engine.actions;

import com.forge.agent.action.BaseAgentAction;
import com.forge.agent.event.StateEventBus;
import com.forge.agent.metrics.AgentMetrics;
import com.forge.agent.state.MainState;
import com.forge.agent.state.SubTask;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 收集节点
 * 
 * 改进：
 * 1. 继承 BaseAgentAction 消除样板代码
 * 2. 结果读取策略可进一步抽象为 TaskResultReader 接口（此处保留 Redis 实现作为默认）
 */
@Component
public class CollectAction extends BaseAgentAction {

    private final StringRedisTemplate redisTemplate;

    public CollectAction(AgentMetrics metrics, StateEventBus eventBus, StringRedisTemplate redisTemplate) {
        super(metrics, eventBus);
        this.redisTemplate = redisTemplate;
    }

    @Override
    protected MainState doExecute(MainState state) {
        List<SubTask> subTasks = state.getSubTasks();
        if (subTasks == null || subTasks.isEmpty()) {
            log.info("没有子任务需要收集结果");
            return state;
        }

        int collectedCount = 0;

        for (int i = 0; i < subTasks.size(); i++) {
            SubTask task = subTasks.get(i);

            if (!"RUNNING".equals(task.status())) {
                continue;
            }

            Map<String, String> result = readTaskResult(task.taskId());

            if (result != null && !result.isEmpty()) {
                String status = result.get("status");
                String taskResult = result.get("result");
                String error = result.get("error");

                if ("SUCCESS".equals(status)) {
                    subTasks.set(i, task.markSuccess(taskResult));
                    metrics().recordTaskComplete();
                    eventBus().fireTaskCompleted(state, task.taskId());
                    collectedCount++;
                    log.info("收集到成功结果: {} - {}", task.taskId(), task.name());
                } else if ("FAILED".equals(status)) {
                    subTasks.set(i, task.markFailed(error));
                    metrics().recordTaskComplete();
                    collectedCount++;
                    log.info("收集到失败结果: {} - {} - {}", task.taskId(), task.name(), error);
                }
            }
        }

        long completedCount = subTasks.stream().filter(SubTask::isCompleted).count();
        log.info("收集完成，本次收集 {} 个结果，总体进度: {}/{}", collectedCount, completedCount, subTasks.size());

        return state;
    }

    /**
     * 从 Redis Hash 读取子任务结果
     */
    private Map<String, String> readTaskResult(String taskId) {
        String key = "agent:task:result:" + taskId;
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);

        if (entries.isEmpty()) {
            return null;
        }

        Map<String, String> result = new java.util.HashMap<>();
        entries.forEach((k, v) -> result.put(k.toString(), v.toString()));
        return result;
    }
}
