package com.forge.agent.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Agent 全链路事件日志 — 存储 + 广播 + 查询。
 *
 * 核心理念（来自 Anthropic Harness Engineering）：
 * "Live Feed 是整个系统的'飞行记录仪'（Flight Recorder），
 *  实时捕捉并展示 Pipeline 执行过程中的每一个事件。"
 *
 * 设计要点：
 * 1. 环形缓冲区 — 内存中保留最近 N 条事件（默认 2000），避免 OOM
 * 2. SSE 广播 — 每个新事件实时推送到所有连接的 SSE 客户端
 * 3. 过滤查询 — 支持按 type/level/source/threadId 过滤
 * 4. 与 StateEventBus 集成 — 作为 StateEventListener 接收工作流事件
 */
@Component
public class AgentEventLog implements StateEventListener {

    private static final Logger log = LoggerFactory.getLogger(AgentEventLog.class);

    /** 环形缓冲区容量 */
    private static final int DEFAULT_CAPACITY = 2000;

    /** 事件存储（环形缓冲，最近的在后面） */
    private final Deque<AgentEvent> events = new ConcurrentLinkedDeque<>();
    private final int capacity;

    /** SSE 订阅者列表 */
    private final List<SseEmitter> sseEmitters = new CopyOnWriteArrayList<>();

    /** SSE 超时时间（毫秒） */
    private static final long SSE_TIMEOUT = 30 * 60 * 1000; // 30 分钟

    public AgentEventLog() {
        this(DEFAULT_CAPACITY);
    }

    public AgentEventLog(int capacity) {
        this.capacity = capacity;
    }

    // ==================== 事件记录 ====================

    /**
     * 记录事件（核心方法）
     * 1. 存入环形缓冲区
     * 2. 广播到所有 SSE 客户端
     */
    public void record(AgentEvent event) {
        // 存入环形缓冲区
        events.addLast(event);

        // 超出容量时移除最旧的
        while (events.size() > capacity) {
            events.pollFirst();
        }

        // 广播到 SSE 客户端
        broadcastToSse(event);

        // 同步打日志（方便排查）
        log.debug("Event: {}", event.toShortLog());
    }

    /**
     * 批量记录事件
     */
    public void recordAll(List<AgentEvent> events) {
        events.forEach(this::record);
    }

    // ==================== StateEventListener 集成 ====================

    /**
     * 接收 StateEventBus 的事件，转换为 AgentEvent 并记录
     */
    @Override
    public void onEvent(StateEvent stateEvent) {
        AgentEvent event = convertStateEvent(stateEvent);
        if (event != null) {
            record(event);
        }
    }

    // ==================== SSE 订阅 ====================

    /**
     * 注册 SSE 连接
     *
     * @return SseEmitter，客户端断开时自动移除
     */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        emitter.onCompletion(() -> {
            sseEmitters.remove(emitter);
            log.debug("SSE 客户端断开 (completion), 剩余: {}", sseEmitters.size());
        });
        emitter.onTimeout(() -> {
            sseEmitters.remove(emitter);
            log.debug("SSE 客户端断开 (timeout), 剩余: {}", sseEmitters.size());
        });
        emitter.onError(e -> {
            sseEmitters.remove(emitter);
            log.debug("SSE 客户端断开 (error), 剩余: {}", sseEmitters.size());
        });

        sseEmitters.add(emitter);
        log.info("SSE 客户端连接, 当前: {}", sseEmitters.size());

        return emitter;
    }

    /**
     * 获取当前 SSE 连接数
     */
    public int getSseClientCount() {
        return sseEmitters.size();
    }

    // ==================== 事件查询 ====================

    /**
     * 查询事件（支持过滤）
     *
     * @param type      事件类型过滤（可选）
     * @param level     事件级别过滤（可选）
     * @param source    来源过滤（可选，前缀匹配）
     * @param threadId  线程 ID 过滤（可选）
     * @param limit     返回数量限制
     * @return 匹配的事件列表（时间倒序）
     */
    public List<AgentEvent> query(AgentEvent.EventType type, AgentEvent.Level level,
                                   String source, String threadId, int limit) {
        return events.stream()
                .filter(e -> type == null || e.type() == type)
                .filter(e -> level == null || e.level() == level)
                .filter(e -> source == null || source.isBlank() || e.source().startsWith(source))
                .filter(e -> threadId == null || threadId.isBlank() || threadId.equals(e.threadId()))
                .sorted((a, b) -> b.timestamp().compareTo(a.timestamp())) // 最新在前
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 获取最近 N 条事件
     */
    public List<AgentEvent> recent(int limit) {
        return events.stream()
                .sorted((a, b) -> b.timestamp().compareTo(a.timestamp()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 获取指定 threadId 的所有事件
     */
    public List<AgentEvent> byThread(String threadId) {
        return events.stream()
                .filter(e -> threadId.equals(e.threadId()))
                .sorted(Comparator.comparing(AgentEvent::timestamp))
                .collect(Collectors.toList());
    }

    /**
     * 获取统计摘要
     */
    public Map<String, Object> stats() {
        Map<String, Long> byType = events.stream()
                .collect(Collectors.groupingBy(e -> e.type().name(), Collectors.counting()));
        Map<String, Long> byLevel = events.stream()
                .collect(Collectors.groupingBy(e -> e.level().name(), Collectors.counting()));
        Map<String, Long> bySource = events.stream()
                .collect(Collectors.groupingBy(AgentEvent::source, Collectors.counting()));

        return Map.of(
                "total", events.size(),
                "capacity", capacity,
                "sseClients", sseEmitters.size(),
                "byType", byType,
                "byLevel", byLevel,
                "bySource", bySource
        );
    }

    /**
     * 清空事件（测试用）
     */
    public void clear() {
        events.clear();
    }

    // ==================== 内部方法 ====================

    /**
     * 广播事件到所有 SSE 客户端
     */
    private void broadcastToSse(AgentEvent event) {
        if (sseEmitters.isEmpty()) return;

        List<SseEmitter> deadEmitters = new ArrayList<>();

        for (SseEmitter emitter : sseEmitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.type().name().toLowerCase())
                        .data(event.toSseData()));
            } catch (IOException e) {
                deadEmitters.add(emitter);
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }

        // 清理断开的连接
        sseEmitters.removeAll(deadEmitters);
    }

    /**
     * 将 StateEvent 转换为 AgentEvent
     */
    private AgentEvent convertStateEvent(StateEvent stateEvent) {
        String source = stateEvent.nodeName();
        String threadId = stateEvent.state() != null ? stateEvent.state().getCurrentNode() : null;

        return switch (stateEvent.type()) {
            case STARTED -> AgentEvent.nodeStart(source, threadId);
            case COMPLETED -> AgentEvent.nodeComplete(source, threadId, 0);
            case FAILED -> AgentEvent.nodeFail(source, threadId, stateEvent.error());
            case TASK_DISPATCHED -> AgentEvent.taskDispatch(
                    stateEvent.taskId(), stateEvent.taskId(), threadId);
            case TASK_COMPLETED -> AgentEvent.taskComplete(
                    stateEvent.taskId(), stateEvent.taskId(), threadId);
            case APPROVAL_REQUIRED -> AgentEvent.systemInfo(source, "等待审批: " + source, threadId);
        };
    }
}
