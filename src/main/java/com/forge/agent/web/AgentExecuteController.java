package com.forge.agent.web;

import com.forge.agent.engine.MainAgentGraph;
import com.forge.agent.spec.SpecManager;
import com.forge.agent.state.MainState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 执行入口 Controller
 *
 * 触发链路：
 *   POST /api/agent/execute
 *   → MainAgentGraph.startExecution()
 *   → LangGraph4j StateGraph 开始执行
 *   → 到 approval 节点自动中断（interruptsBefore）
 *   → 返回 threadId，前端/飞书轮询审批状态
 *
 * 审批后恢复：
 *   POST /api/approval/{threadId}/approve
 *   → MainAgentGraph.resumeAfterApproval()
 *   → 从 checkpoint 继续执行
 */
@RestController
@RequestMapping("/api/agent")
public class AgentExecuteController {

    private static final Logger log = LoggerFactory.getLogger(AgentExecuteController.class);

    private final MainAgentGraph mainAgentGraph;
    private final SpecManager specManager;

    /** 已启动的任务：threadId → 启动时间 */
    private final Map<String, Long> runningTasks = new ConcurrentHashMap<>();

    public AgentExecuteController(MainAgentGraph mainAgentGraph, SpecManager specManager) {
        this.mainAgentGraph = mainAgentGraph;
        this.specManager = specManager;
    }

    /**
     * 启动 Agent 执行
     *
     * 请求示例：
     * POST /api/agent/execute
     * {
     *   "requirement": "开发 CMC 资管服务和 COF 资金网关",
     *   "specs": ["cmc-spec.md", "cof-spec.md"]
     * }
     *
     * 响应：
     * {
     *   "threadId": "task-1717382400000",
     *   "status": "RUNNING",
     *   "message": "Agent 已启动，等待审批"
     * }
     */
    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> execute(@RequestBody ExecuteRequest request) {

        // 生成 threadId
        String threadId = "task-" + System.currentTimeMillis();

        log.info("=== Agent 执行启动 ===");
        log.info("threadId: {}", threadId);
        log.info("requirement: {}", request.requirement());
        log.info("specs: {}", request.specs());

        // 读取 Spec 内容
        Map<String, String> specContents = new LinkedHashMap<>();
        for (String specFile : request.specs()) {
            String content = specManager.loadSpec(specFile);
            if (content != null) {
                specContents.put(specFile, content);
                log.info("加载 Spec: {} ({}字)", specFile, content.length());
            } else {
                log.warn("Spec 文件不存在: {}", specFile);
            }
        }

        // 组装初始状态
        MainState initialState = new MainState();
        initialState.setRequirement(request.requirement());

        runningTasks.put(threadId, System.currentTimeMillis());

        try {
            // 启动执行（到 approval 节点会自动中断）
            MainState result = mainAgentGraph.startExecution(request.requirement(), threadId);

            String status = "RUNNING";
            String message = "Agent 已启动，等待审批";

            if ("APPROVED".equals(result.getApprovalStatus())) {
                status = "COMPLETED";
                message = "Agent 执行完成";
            } else if ("WAITING_APPROVAL".equals(result.getApprovalStatus())) {
                status = "WAITING_APPROVAL";
                message = "等待人工审批，请访问 /api/approval/pending 查看";
            }

            return ResponseEntity.ok(Map.of(
                    "threadId", threadId,
                    "status", status,
                    "message", message,
                    "requirement", request.requirement(),
                    "specCount", specContents.size()
            ));

        } catch (Exception e) {
            log.error("Agent 执行失败: threadId={}", threadId, e);
            runningTasks.remove(threadId);
            return ResponseEntity.internalServerError().body(Map.of(
                    "threadId", threadId,
                    "status", "FAILED",
                    "message", "执行失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 查询任务状态
     */
    @GetMapping("/status/{threadId}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String threadId) {

        var config = org.bsc.langgraph4j.RunnableConfig.builder()
                .threadId(threadId)
                .build();

        var snapshot = mainAgentGraph.getWorkflow().lastStateOf(config);

        if (snapshot.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        MainState state = snapshot.get().state();
        String node = snapshot.get().node();
        String next = snapshot.get().next();

        return ResponseEntity.ok(Map.of(
                "threadId", threadId,
                "currentNode", node != null ? node : "",
                "nextNode", next != null ? next : "",
                "approvalStatus", state.getApprovalStatus() != null ? state.getApprovalStatus() : "",
                "phase", state.getPhase() != null ? state.getPhase() : "",
                "subTaskCount", state.getSubTasks() != null ? state.getSubTasks().size() : 0
        ));
    }

    /**
     * 列出所有运行中的任务
     */
    @GetMapping("/tasks")
    public ResponseEntity<Map<String, Object>> listTasks() {
        return ResponseEntity.ok(Map.of(
                "running", runningTasks.size(),
                "tasks", runningTasks
        ));
    }

    /**
     * 请求体
     */
    public record ExecuteRequest(
            String requirement,
            List<String> specs
    ) {}
}
