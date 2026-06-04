package com.forge.agent.event;

import com.forge.agent.state.MainState;

/**
 * 工作流状态事件
 */
public record StateEvent(
    MainState state,
    String nodeName,
    Type type,
    String error,
    String taskId
) {
    public enum Type {
        STARTED,
        COMPLETED,
        FAILED,
        APPROVAL_REQUIRED,
        TASK_DISPATCHED,
        TASK_COMPLETED
    }
}
