package com.forge.agent.event;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 全链路事件记录 — Agent 可观测性的原子单元。
 *
 * 核心理念（来自 Anthropic Harness Engineering）：
 * "长时运行的任务不透明是最大的风险。如果你启动一个 6 小时的 Pipeline，
 *  却只能在最后看到结果，那么中途的任何偏差都会被放大为灾难性的浪费。
 *  Stream-JSON 将每个工具调用、思考过程、文本输出都作为独立的 JSON 事件流式输出。"
 *
 * 事件类型覆盖 Agent 执行的完整生命周期：
 * - 节点生命周期：NODE_START / NODE_COMPLETE / NODE_FAIL
 * - LLM 调用：LLM_REQUEST / LLM_RESPONSE / LLM_ERROR
 * - 工具调用：TOOL_CALL / TOOL_RESULT / TOOL_ERROR
 * - 任务流转：TASK_DISPATCH / TASK_COMPLETE / TASK_FAIL
 * - 审批流程：APPROVAL_WAIT / APPROVAL_DECISION
 * - 系统事件：SYSTEM_INFO / SYSTEM_WARN / SYSTEM_ERROR
 * - 管线事件：PIPELINE_START / PIPELINE_COMPLETE
 */
public record AgentEvent(
        /** 事件唯一 ID */
        String eventId,
        /** 事件时间戳（ISO-8601） */
        String timestamp,
        /** 事件类型 */
        EventType type,
        /** 事件来源（节点名/角色名） */
        String source,
        /** 事件级别 */
        Level level,
        /** 事件摘要（一句话） */
        String summary,
        /** 事件详情（可能很长） */
        String detail,
        /** 关联的 threadId */
        String threadId,
        /** 关联的 taskId */
        String taskId,
        /** 附加元数据（如 tool name, model, duration_ms, token_count） */
        Map<String, Object> metadata
) {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    // ==================== 事件类型 ====================

    public enum EventType {
        // 节点生命周期
        NODE_START("节点开始"),
        NODE_COMPLETE("节点完成"),
        NODE_FAIL("节点失败"),

        // LLM 调用
        LLM_REQUEST("LLM 请求"),
        LLM_RESPONSE("LLM 响应"),
        LLM_ERROR("LLM 错误"),

        // 工具调用
        TOOL_CALL("工具调用"),
        TOOL_RESULT("工具结果"),
        TOOL_ERROR("工具错误"),

        // 任务流转
        TASK_DISPATCH("任务派发"),
        TASK_COMPLETE("任务完成"),
        TASK_FAIL("任务失败"),

        // 审批流程
        APPROVAL_WAIT("等待审批"),
        APPROVAL_DECISION("审批决策"),

        // 审查
        REVIEW_START("审查开始"),
        REVIEW_VERDICT("审查裁决"),

        // 管线
        PIPELINE_START("管线启动"),
        PIPELINE_COMPLETE("管线完成"),

        // 系统
        SYSTEM_INFO("系统信息"),
        SYSTEM_WARN("系统警告"),
        SYSTEM_ERROR("系统错误");

        private final String description;
        EventType(String description) { this.description = description; }
        public String description() { return description; }
    }

    // ==================== 事件级别 ====================

    public enum Level {
        DEBUG, INFO, WARN, ERROR
    }

    // ==================== 工厂方法 ====================

    private static long counter = 0;

    private static String nextId() {
        return "evt-" + System.currentTimeMillis() + "-" + (++counter % 10000);
    }

    /** 节点开始 */
    public static AgentEvent nodeStart(String source, String threadId) {
        return new AgentEvent(nextId(), FORMATTER.format(Instant.now()),
                EventType.NODE_START, source, Level.INFO,
                "节点 [" + source + "] 开始执行", null, threadId, null, Map.of());
    }

    /** 节点完成 */
    public static AgentEvent nodeComplete(String source, String threadId, long durationMs) {
        return new AgentEvent(nextId(), FORMATTER.format(Instant.now()),
                EventType.NODE_COMPLETE, source, Level.INFO,
                "节点 [" + source + "] 执行完成 (" + durationMs + "ms)",
                null, threadId, null, Map.of("duration_ms", durationMs));
    }

    /** 节点失败 */
    public static AgentEvent nodeFail(String source, String threadId, String error) {
        return new AgentEvent(nextId(), FORMATTER.format(Instant.now()),
                EventType.NODE_FAIL, source, Level.ERROR,
                "节点 [" + source + "] 执行失败", error, threadId, null, Map.of());
    }

    /** LLM 请求 */
    public static AgentEvent llmRequest(String role, String action, String threadId, int estimatedTokens) {
        return new AgentEvent(nextId(), FORMATTER.format(Instant.now()),
                EventType.LLM_REQUEST, role, Level.INFO,
                "LLM 请求: " + role + "/" + action,
                null, threadId, null,
                Map.of("role", role, "action", action, "estimated_tokens", estimatedTokens));
    }

    /** LLM 响应 */
    public static AgentEvent llmResponse(String role, String action, String threadId,
                                          long durationMs, int responseLength) {
        return new AgentEvent(nextId(), FORMATTER.format(Instant.now()),
                EventType.LLM_RESPONSE, role, Level.INFO,
                "LLM 响应: " + role + "/" + action + " (" + durationMs + "ms, " + responseLength + " chars)",
                null, threadId, null,
                Map.of("role", role, "action", action, "duration_ms", durationMs, "response_length", responseLength));
    }

    /** LLM 错误 */
    public static AgentEvent llmError(String role, String action, String threadId, String error) {
        return new AgentEvent(nextId(), FORMATTER.format(Instant.now()),
                EventType.LLM_ERROR, role, Level.ERROR,
                "LLM 错误: " + role + "/" + action, error, threadId, null,
                Map.of("role", role, "action", action));
    }

    /** 工具调用 */
    public static AgentEvent toolCall(String toolName, String agentRole, String threadId,
                                       Map<String, Object> args) {
        return new AgentEvent(nextId(), FORMATTER.format(Instant.now()),
                EventType.TOOL_CALL, agentRole != null ? agentRole : "unknown", Level.INFO,
                "工具调用: " + toolName,
                args != null ? args.toString() : null, threadId, null,
                Map.of("tool_name", toolName, "args", args != null ? args.keySet() : List.of()));
    }

    /** 工具结果 */
    public static AgentEvent toolResult(String toolName, String agentRole, String threadId,
                                         boolean success, long durationMs) {
        return new AgentEvent(nextId(), FORMATTER.format(Instant.now()),
                EventType.TOOL_RESULT, agentRole != null ? agentRole : "unknown",
                success ? Level.INFO : Level.WARN,
                "工具结果: " + toolName + (success ? " 成功" : " 失败") + " (" + durationMs + "ms)",
                null, threadId, null,
                Map.of("tool_name", toolName, "success", success, "duration_ms", durationMs));
    }

    /** 工具错误 */
    public static AgentEvent toolError(String toolName, String agentRole, String threadId, String error) {
        return new AgentEvent(nextId(), FORMATTER.format(Instant.now()),
                EventType.TOOL_ERROR, agentRole != null ? agentRole : "unknown", Level.ERROR,
                "工具错误: " + toolName, error, threadId, null,
                Map.of("tool_name", toolName));
    }

    /** 任务派发 */
    public static AgentEvent taskDispatch(String taskId, String taskName, String threadId) {
        return new AgentEvent(nextId(), FORMATTER.format(Instant.now()),
                EventType.TASK_DISPATCH, "dispatch", Level.INFO,
                "任务派发: " + taskName, null, threadId, taskId, Map.of());
    }

    /** 任务完成 */
    public static AgentEvent taskComplete(String taskId, String taskName, String threadId) {
        return new AgentEvent(nextId(), FORMATTER.format(Instant.now()),
                EventType.TASK_COMPLETE, "collect", Level.INFO,
                "任务完成: " + taskName, null, threadId, taskId, Map.of());
    }

    /** 审查裁决 */
    public static AgentEvent reviewVerdict(String reviewer, String threadId, String verdict, String summary) {
        return new AgentEvent(nextId(), FORMATTER.format(Instant.now()),
                EventType.REVIEW_VERDICT, reviewer,
                "PASS".equals(verdict) ? Level.INFO : Level.WARN,
                "审查裁决: " + verdict + " — " + summary, null, threadId, null,
                Map.of("verdict", verdict));
    }

    /** 管线启动 */
    public static AgentEvent pipelineStart(String threadId, String requirement) {
        return new AgentEvent(nextId(), FORMATTER.format(Instant.now()),
                EventType.PIPELINE_START, "pipeline", Level.INFO,
                "管线启动: " + (requirement.length() > 80 ? requirement.substring(0, 80) + "..." : requirement),
                requirement, threadId, null, Map.of());
    }

    /** 管线完成 */
    public static AgentEvent pipelineComplete(String threadId, long totalDurationMs, int tasksTotal, int tasksSuccess) {
        return new AgentEvent(nextId(), FORMATTER.format(Instant.now()),
                EventType.PIPELINE_COMPLETE, "pipeline", Level.INFO,
                String.format("管线完成: %d/%d 任务成功 (%dms)", tasksSuccess, tasksTotal, totalDurationMs),
                null, threadId, null,
                Map.of("duration_ms", totalDurationMs, "tasks_total", tasksTotal, "tasks_success", tasksSuccess));
    }

    /** 系统信息 */
    public static AgentEvent systemInfo(String source, String message, String threadId) {
        return new AgentEvent(nextId(), FORMATTER.format(Instant.now()),
                EventType.SYSTEM_INFO, source, Level.INFO, message, null, threadId, null, Map.of());
    }

    /** 系统警告 */
    public static AgentEvent systemWarn(String source, String message, String threadId) {
        return new AgentEvent(nextId(), FORMATTER.format(Instant.now()),
                EventType.SYSTEM_WARN, source, Level.WARN, message, null, threadId, null, Map.of());
    }

    /** 系统错误 */
    public static AgentEvent systemError(String source, String message, String detail, String threadId) {
        return new AgentEvent(nextId(), FORMATTER.format(Instant.now()),
                EventType.SYSTEM_ERROR, source, Level.ERROR, message, detail, threadId, null, Map.of());
    }

    // ==================== 便利方法 ====================

    /** 转为 SSE 格式字符串 */
    public String toSseData() {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(this);
        } catch (Exception e) {
            return "{\"error\":\"serialize failed\"}";
        }
    }

    /** 获取短摘要（用于日志） */
    public String toShortLog() {
        return String.format("[%s] [%s] [%s] %s", timestamp, level, source, summary);
    }
}
