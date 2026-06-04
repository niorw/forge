package com.forge.agent.tool;

import java.util.Map;

/**
 * 工具执行上下文，携带当前任务元信息。
 */
public record ToolContext(
        /** 当前任务 ID */
        String taskId,
        /** 当前线程/会话 ID */
        String threadId,
        /** 当前 Agent 角色 */
        String agentRole,
        /** 额外元数据 */
        Map<String, Object> metadata
) {

    public static ToolContext of(String taskId, String threadId, String agentRole) {
        return new ToolContext(taskId, threadId, agentRole, Map.of());
    }
}
