package com.forge.agent.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
 * 审计服务 — 记录和查询审计日志。
 *
 * 设计原则：
 * 1. 只追加不删除 — 审计日志不可篡改
 * 2. 内存环形缓冲 + 日志文件双写
 * 3. 支持按 actor/action/projectId/threadId 过滤查询
 */
@Component
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int CAPACITY = 5000;
    private final Deque<AuditLog> logs = new ConcurrentLinkedDeque<>();

    /** 记录审计日志 */
    public void record(AuditLog auditLog) {
        logs.addLast(auditLog);
        while (logs.size() > CAPACITY) logs.pollFirst();
        // 同时写入日志文件（不可篡改）
        log.info("AUDIT: [{}] [{}] actor={} target={} detail={}",
                auditLog.action(), auditLog.timestamp(), auditLog.actor(),
                auditLog.target(), auditLog.detail());
    }

    /** 查询审计日志 */
    public List<AuditLog> query(AuditLog.AuditAction action, String actor,
                                 String projectId, String threadId, int limit) {
        return logs.stream()
                .filter(e -> action == null || e.action() == action)
                .filter(e -> actor == null || actor.isBlank() || e.actor().equals(actor))
                .filter(e -> projectId == null || projectId.isBlank() || projectId.equals(e.projectId()))
                .filter(e -> threadId == null || threadId.isBlank() || threadId.equals(e.threadId()))
                .sorted((a, b) -> b.timestamp().compareTo(a.timestamp()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /** 最近 N 条 */
    public List<AuditLog> recent(int limit) {
        return logs.stream()
                .sorted((a, b) -> b.timestamp().compareTo(a.timestamp()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /** 统计 */
    public Map<String, Object> stats() {
        Map<String, Long> byAction = logs.stream()
                .collect(Collectors.groupingBy(e -> e.action().name(), Collectors.counting()));
        Map<String, Long> byActor = logs.stream()
                .collect(Collectors.groupingBy(AuditLog::actor, Collectors.counting()));
        return Map.of("total", logs.size(), "byAction", byAction, "byActor", byActor);
    }
}
