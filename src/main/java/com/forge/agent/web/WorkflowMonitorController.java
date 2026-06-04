package com.forge.agent.web;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Agent 工作流实时监控 Controller
 * <p>
 * 提供 REST API 查询任务状态，以及 SSE 实时推送节点状态变化。
 * 使用内存 ConcurrentHashMap 存储（生产环境应替换为 DB）。
 */
@RestController
@RequestMapping("/api/workflow")
public class WorkflowMonitorController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowMonitorController.class);

    // ==================== 数据结构 ====================

    /** 工作流整体状态 */
    public record WorkflowStatus(
            String taskId,
            String requirement,
            String status,           // RUNNING / COMPLETED / FAILED / WAITING_APPROVAL
            List<NodeStatus> nodes,
            long startedAt,
            long elapsedMs
    ) {}

    /** 单个 DAG 节点状态 */
    public record NodeStatus(
            String id,               // 节点ID
            String name,             // 节点显示名
            String type,             // analyze/plan/reviewSpec/approval/schedule/dispatch/collect/integrate/worker
            String status,           // PENDING / RUNNING / COMPLETED / FAILED / BLOCKED / WAITING
            List<String> dependencies,  // 依赖的节点ID
            long startedAt,
            long completedAt,
            String result,           // 执行结果摘要
            Integer tokensUsed,      // Token消耗
            Integer costCents        // 费用(分)
    ) {}

    /** SSE 推送的事件数据 */
    public record NodeEvent(
            String taskId,
            String nodeId,
            String status,
            Integer tokensUsed,
            Integer costCents,
            String result
    ) {}

    // ==================== 存储 ====================

    /** 任务状态存储 (taskId -> WorkflowStatus) */
    private final ConcurrentHashMap<String, WorkflowStatus> taskStore = new ConcurrentHashMap<>();

    /** SSE 连接列表 (taskId -> List<SseEmitter>) */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    // ==================== REST API ====================

    /**
     * 获取指定任务的完整工作流状态
     */
    @GetMapping("/status/{taskId}")
    public WorkflowStatus getStatus(@PathVariable String taskId) {
        WorkflowStatus status = taskStore.get(taskId);
        if (status == null) {
            throw new NoSuchElementException("任务不存在: " + taskId);
        }
        return status;
    }

    /**
     * SSE 实时推送节点状态变化
     * 前端通过 EventSource 连接此端点，接收实时更新
     */
    @GetMapping(value = "/stream/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String taskId) {
        // 超时设为 0 表示不超时（长连接）
        SseEmitter emitter = new SseEmitter(0L);

        emitters.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        // 连接关闭时移除
        emitter.onCompletion(() -> {
            List<SseEmitter> list = emitters.get(taskId);
            if (list != null) list.remove(emitter);
            log.debug("SSE 连接已关闭: taskId={}", taskId);
        });
        emitter.onTimeout(() -> {
            List<SseEmitter> list = emitters.get(taskId);
            if (list != null) list.remove(emitter);
            log.debug("SSE 连接超时: taskId={}", taskId);
        });
        emitter.onError(e -> {
            List<SseEmitter> list = emitters.get(taskId);
            if (list != null) list.remove(emitter);
            log.debug("SSE 连接异常: taskId={}", taskId, e);
        });

        log.info("新的 SSE 订阅: taskId={}", taskId);
        return emitter;
    }

    /**
     * 获取所有任务的历史列表
     */
    @GetMapping("/history")
    public List<WorkflowStatus> history() {
        return taskStore.values().stream()
                .sorted(Comparator.comparingLong(WorkflowStatus::startedAt).reversed())
                .toList();
    }

    // ==================== 内部方法：节点状态更新 & SSE 推送 ====================

    /**
     * 更新节点状态并推送 SSE 事件（供 WorkflowEventBus 调用）
     */
    public void updateNodeStatus(String taskId, NodeEvent event) {
        WorkflowStatus ws = taskStore.get(taskId);
        if (ws == null) return;

        // 更新内存中的节点状态
        List<NodeStatus> updatedNodes = ws.nodes().stream().map(n -> {
            if (n.id().equals(event.nodeId())) {
                long now = System.currentTimeMillis();
                long completedAt = "COMPLETED".equals(event.status()) || "FAILED".equals(event.status()) ? now : 0;
                return new NodeStatus(
                        n.id(), n.name(), n.type(), event.status(), n.dependencies(),
                        n.startedAt() == 0 && "RUNNING".equals(event.status()) ? now : n.startedAt(),
                        completedAt,
                        event.result() != null ? event.result() : n.result(),
                        event.tokensUsed() != null ? event.tokensUsed() : n.tokensUsed(),
                        event.costCents() != null ? event.costCents() : n.costCents()
                );
            }
            return n;
        }).toList();

        // 推断整体任务状态
        String overallStatus = inferOverallStatus(updatedNodes);

        WorkflowStatus updated = new WorkflowStatus(
                ws.taskId(), ws.requirement(), overallStatus, updatedNodes,
                ws.startedAt(), System.currentTimeMillis() - ws.startedAt()
        );
        taskStore.put(taskId, updated);

        // SSE 推送
        pushSse(taskId, event);
    }

    /**
     * 推送 SSE 事件到所有订阅者
     */
    private void pushSse(String taskId, NodeEvent event) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(taskId);
        if (list == null) return;

        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name("node-update")
                        .data(event, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        list.removeAll(dead);
    }

    /**
     * 根据节点状态推断整体任务状态
     */
    private String inferOverallStatus(List<NodeStatus> nodes) {
        boolean anyFailed = nodes.stream().anyMatch(n -> "FAILED".equals(n.status()));
        if (anyFailed) return "FAILED";

        boolean allCompleted = nodes.stream().allMatch(n -> "COMPLETED".equals(n.status()));
        if (allCompleted) return "COMPLETED";

        boolean waitingApproval = nodes.stream().anyMatch(n -> "WAITING".equals(n.status()) && "approval".equals(n.type()));
        if (waitingApproval) return "WAITING_APPROVAL";

        return "RUNNING";
    }

    // ==================== Demo 数据初始化 ====================

    /**
     * 初始化 Demo 任务数据，展示一个完整的多层 DAG 执行过程
     * <p>
     * DAG 分层:
     * 第1层: analyze -> plan -> reviewSpec -> approval (串行)
     * 第2层: schedule -> dispatch (串行)
     * 第3层: worker-define-interface
     * 第4层: worker-order-impl, worker-payment-adapter (并行)
     * 第5层: worker-integration-test
     * 第6层: collect -> integrate
     */
    @PostConstruct
    public void initDemo() {
        long now = System.currentTimeMillis();

        List<NodeStatus> nodes = List.of(
                // ---- 第1层: 分析 & 规划（已完成） ----
                new NodeStatus("analyze", "需求分析", "analyze", "COMPLETED",
                        List.of(), now - 120_000, now - 105_000,
                        "识别出3个核心子任务：订单服务、支付适配器、接口定义", 1280, 8),
                new NodeStatus("plan", "任务规划", "plan", "COMPLETED",
                        List.of("analyze"), now - 105_000, now - 88_000,
                        "生成 DAG：6 个节点，2 条并行路径", 2048, 12),
                new NodeStatus("reviewSpec", "Spec 审查", "reviewSpec", "COMPLETED",
                        List.of("plan"), now - 88_000, now - 72_000,
                        "Spec 校验通过，覆盖 3 个子系统接口规范", 3200, 18),
                new NodeStatus("approval", "人工审批", "approval", "COMPLETED",
                        List.of("reviewSpec"), now - 72_000, now - 60_000,
                        "审批通过 - 张三确认于 14:32", 0, 0),

                // ---- 第2层: 调度（已完成 + 运行中） ----
                new NodeStatus("schedule", "资源调度", "schedule", "COMPLETED",
                        List.of("approval"), now - 60_000, now - 52_000,
                        "分配 3 个 Worker 槽位，预留 8192 token 预算", 512, 3),
                new NodeStatus("dispatch", "任务分发", "dispatch", "COMPLETED",
                        List.of("schedule"), now - 52_000, now - 48_000,
                        "已将子任务推送到 Redis Stream", 256, 2),

                // ---- 第3层: Worker 接口定义（已完成） ----
                new NodeStatus("worker-define-interface", "定义接口规范", "worker", "COMPLETED",
                        List.of("dispatch"), now - 48_000, now - 35_000,
                        "生成 OpenAPI 3.0 Spec: /orders, /payments, /interfaces", 4096, 24),

                // ---- 第4层: 并行 Worker（一个完成，一个运行中） ----
                new NodeStatus("worker-order-impl", "订单服务实现", "worker", "COMPLETED",
                        List.of("worker-define-interface"), now - 35_000, now - 18_000,
                        "生成 3 个 Controller + 5 个 Service + 8 个 Entity", 6144, 36),
                new NodeStatus("worker-payment-adapter", "支付适配器实现", "worker", "RUNNING",
                        List.of("worker-define-interface"), now - 35_000, 0,
                        "正在对接银联支付网关...", 3200, 18),

                // ---- 第5层: 集成测试（阻塞，等待上游完成） ----
                new NodeStatus("worker-integration-test", "集成测试", "worker", "BLOCKED",
                        List.of("worker-order-impl", "worker-payment-adapter"), 0, 0,
                        "等待 worker-payment-adapter 完成", 0, 0),

                // ---- 第6层: 收尾（等待中） ----
                new NodeStatus("collect", "结果收集", "collect", "PENDING",
                        List.of("worker-integration-test"), 0, 0, null, 0, 0),
                new NodeStatus("integrate", "集成合并", "integrate", "PENDING",
                        List.of("collect"), 0, 0, null, 0, 0)
        );

        WorkflowStatus demo = new WorkflowStatus(
                "demo-task-001",
                "实现订单-支付一体化微服务（含接口定义、业务实现、集成测试）",
                "RUNNING",
                nodes,
                now - 120_000,
                120_000
        );

        taskStore.put(demo.taskId(), demo);
        log.info("✅ Demo 任务初始化完成: {}", demo.taskId());
    }
}
