package com.forge.agent.web;

import com.forge.agent.event.AgentEvent;
import com.forge.agent.event.AgentEventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 事件流 REST + SSE 控制器
 *
 * 提供三个核心能力：
 * 1. SSE 实时事件流 — /api/events/stream（Live Feed 的数据源）
 * 2. 事件历史查询 — /api/events/history（带过滤器）
 * 3. 事件统计 — /api/events/stats
 *
 * SSE 使用方式：
 *   const es = new EventSource('/api/events/stream');
 *   es.addEventListener('tool_call', e => console.log(e.data));
 *   es.addEventListener('llm_response', e => console.log(e.data));
 *   es.addEventListener('node_complete', e => console.log(e.data));
 */
@RestController
@RequestMapping("/api/events")
public class EventStreamController {

    private static final Logger log = LoggerFactory.getLogger(EventStreamController.class);

    private final AgentEventLog eventLog;

    public EventStreamController(AgentEventLog eventLog) {
        this.eventLog = eventLog;
    }

    // ==================== SSE 实时事件流 ====================

    /**
     * SSE 实时事件流端点
     *
     * 客户端连接后，每个新事件都会实时推送。
     * 事件名称（event field）对应 AgentEvent.EventType 的小写形式。
     *
     * 连接方式：
     *   const es = new EventSource('/api/events/stream');
     *   es.onmessage = e => { const event = JSON.parse(e.data); ... };
     *
     * 或按事件类型监听：
     *   es.addEventListener('tool_call', e => { ... });
     *   es.addEventListener('llm_response', e => { ... });
     *   es.addEventListener('node_fail', e => { ... });
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        log.info("SSE 客户端连接请求");
        return eventLog.subscribe();
    }

    // ==================== 事件历史查询 ====================

    /**
     * 查询事件历史（带过滤器）
     *
     * @param type     事件类型过滤（可选）
     * @param level    事件级别过滤（可选）
     * @param source   来源过滤（可选，前缀匹配）
     * @param threadId 线程 ID 过滤（可选）
     * @param limit    返回数量限制（默认 100）
     */
    @GetMapping("/history")
    public ResponseEntity<List<AgentEvent>> history(
            @RequestParam(required = false) AgentEvent.EventType type,
            @RequestParam(required = false) AgentEvent.Level level,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String threadId,
            @RequestParam(defaultValue = "100") int limit) {

        List<AgentEvent> events = eventLog.query(type, level, source, threadId, limit);
        return ResponseEntity.ok(events);
    }

    /**
     * 获取最近 N 条事件
     */
    @GetMapping("/recent")
    public ResponseEntity<List<AgentEvent>> recent(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(eventLog.recent(limit));
    }

    /**
     * 获取指定 threadId 的事件时间线
     */
    @GetMapping("/thread/{threadId}")
    public ResponseEntity<List<AgentEvent>> byThread(@PathVariable String threadId) {
        return ResponseEntity.ok(eventLog.byThread(threadId));
    }

    // ==================== 事件统计 ====================

    /**
     * 获取事件统计摘要
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(eventLog.stats());
    }

    /**
     * 获取当前 SSE 连接数
     */
    @GetMapping("/sse-clients")
    public ResponseEntity<Map<String, Object>> sseClients() {
        return ResponseEntity.ok(Map.of(
                "count", eventLog.getSseClientCount()
        ));
    }
}
