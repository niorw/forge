package com.forge.agent.web;

import com.forge.agent.engine.MainAgentGraph;
import com.forge.agent.state.MainState;
import com.forge.agent.state.SubTask;
import org.bsc.langgraph4j.RunnableConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Sprint Board REST API — 看板式任务管理接口。
 *
 * 核心理念（来自 Anthropic Harness Engineering）：
 * "Sprint Board 将 Planner 拆解出的子任务按状态分布在四列看板上，
 *  让每个任务的生命周期一目了然。所有任务经过 meta-plan，
 *  AI 自动判断任务复杂度，动态规划后续执行流程。"
 *
 * 四列状态流转：
 *   Inbox（待处理）→ In Progress（执行中）→ Review（审查中）→ Done（已完成）
 *
 * 任务卡片元信息：
 * - 优先级标签（HIGH / MEDIUM / LOW）
 * - 来源标签（auto = Planner 自动拆解）
 * - QA 状态标签（qa-failed = 未通过质量门禁）
 * - Verdict 标签（PASS / FAIL）
 */
@RestController
@RequestMapping("/api/sprint-board")
public class SprintBoardController {

    private static final Logger log = LoggerFactory.getLogger(SprintBoardController.class);

    private final MainAgentGraph mainAgentGraph;

    public SprintBoardController(MainAgentGraph mainAgentGraph) {
        this.mainAgentGraph = mainAgentGraph;
    }

    /**
     * 获取看板数据（按列分组）
     *
     * @param threadId 工作流线程 ID（必需）
     * @return 四列任务数据
     */
    @GetMapping
    public ResponseEntity<SprintBoard> getBoard(@RequestParam String threadId) {
        try {
            var config = RunnableConfig.builder().threadId(threadId).build();
            var snapshot = mainAgentGraph.getWorkflow().lastStateOf(config);

            if (snapshot.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            MainState state = snapshot.get().state();
            SprintBoard board = buildBoard(state, threadId);
            return ResponseEntity.ok(board);

        } catch (Exception e) {
            log.error("获取看板数据失败: threadId={}", threadId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 获取所有活跃的看板列表
     */
    @GetMapping("/list")
    public ResponseEntity<List<BoardSummary>> listBoards() {
        // 从 checkpoint 中获取所有活跃的 threadId
        // 这里简化实现，实际应该从 MysqlSaver 查询
        List<BoardSummary> boards = new ArrayList<>();
        return ResponseEntity.ok(boards);
    }

    /**
     * 更新任务状态（手动拖拽）
     *
     * @param threadId 工作流线程 ID
     * @param taskId   任务 ID
     * @param newStatus 新状态（INBOX/IN_PROGRESS/REVIEW/DONE）
     */
    @PutMapping("/task/{taskId}/status")
    public ResponseEntity<Map<String, Object>> updateTaskStatus(
            @RequestParam String threadId,
            @PathVariable String taskId,
            @RequestBody Map<String, String> body) {

        String newStatus = body.get("status");
        if (newStatus == null || newStatus.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "status 不能为空"));
        }

        try {
            var config = RunnableConfig.builder().threadId(threadId).build();
            var snapshot = mainAgentGraph.getWorkflow().lastStateOf(config);

            if (snapshot.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            MainState state = snapshot.get().state();
            List<SubTask> tasks = state.getSubTasks();

            if (tasks == null) {
                return ResponseEntity.notFound().build();
            }

            boolean found = false;
            List<SubTask> updated = new ArrayList<>();
            for (SubTask task : tasks) {
                if (task.taskId().equals(taskId)) {
                    found = true;
                    // 根据新状态更新任务
                    updated.add(switch (newStatus) {
                        case "IN_PROGRESS" -> task.markRunning();
                        case "DONE" -> task.markSuccess(task.result() != null ? task.result() : "手动标记完成");
                        default -> task;
                    });
                } else {
                    updated.add(task);
                }
            }

            if (!found) {
                return ResponseEntity.notFound().build();
            }

            state.setSubTasks(updated);
            log.info("任务状态已更新: taskId={} → {}", taskId, newStatus);

            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "taskId", taskId,
                    "newStatus", newStatus
            ));

        } catch (Exception e) {
            log.error("更新任务状态失败: taskId={}", taskId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== 内部方法 ====================

    private SprintBoard buildBoard(MainState state, String threadId) {
        List<SubTask> tasks = state.getSubTasks();
        List<TaskCard> allCards = new ArrayList<>();

        // 将 SubTask 转换为 TaskCard
        if (tasks != null) {
            for (SubTask task : tasks) {
                allCards.add(toTaskCard(task, state));
            }
        }

        // 按状态分列
        List<TaskCard> inbox = allCards.stream()
                .filter(c -> "PENDING".equals(c.status()))
                .collect(Collectors.toList());
        List<TaskCard> inProgress = allCards.stream()
                .filter(c -> "RUNNING".equals(c.status()))
                .collect(Collectors.toList());
        List<TaskCard> review = allCards.stream()
                .filter(c -> "SUCCESS".equals(c.status()) && c.verdict() == null)
                .collect(Collectors.toList());
        List<TaskCard> done = allCards.stream()
                .filter(c -> c.verdict() != null && "PASS".equals(c.verdict()))
                .collect(Collectors.toList());
        List<TaskCard> failed = allCards.stream()
                .filter(c -> "FAILED".equals(c.status()) ||
                        (c.verdict() != null && "FAIL".equals(c.verdict())))
                .collect(Collectors.toList());

        // 管线状态
        String pipelineStatus = determinePipelineStatus(state);

        return new SprintBoard(
                threadId,
                state.getRequirement() != null ? state.getRequirement() : "",
                pipelineStatus,
                state.getRevisionCount(),
                allCards.size(),
                inbox, inProgress, review, done, failed
        );
    }

    private TaskCard toTaskCard(SubTask task, MainState state) {
        // 确定看板列状态
        String columnStatus = switch (task.status()) {
            case "PENDING" -> "INBOX";
            case "RUNNING" -> "IN_PROGRESS";
            case "SUCCESS" -> "REVIEW";
            case "FAILED" -> "FAILED";
            default -> "INBOX";
        };

        // 确定优先级标签
        String priority = "MEDIUM"; // 默认中优先级

        // 确定 Verdict（如果有审查结果）
        String verdict = null;
        if (state.getCodeVerdict() != null && "SUCCESS".equals(task.status())) {
            verdict = state.getCodeVerdict().isPass() ? "PASS" : "FAIL";
        }

        return new TaskCard(
                task.taskId(),
                task.name(),
                task.description(),
                task.status(),
                columnStatus,
                priority,
                "auto",  // 来源：Planner 自动拆解
                verdict,
                task.error(),
                task.fileDiff() != null,
                task.dependencies()
        );
    }

    private String determinePipelineStatus(MainState state) {
        if (state.getError() != null) return "ERROR";
        String phase = state.getPhase();
        if (phase == null) return "IDLE";
        return switch (phase) {
            case "INIT", "ANALYZE", "ARCHITECTURE", "SPEC_AUTHOR", "SPEC_REVIEW",
                 "PLAN", "APPROVAL" -> "PLANNING";
            case "SCHEDULE", "DISPATCH", "COLLECT" -> "RUNNING";
            case "REVIEW", "SECURITY_REVIEW", "TEST", "INTEGRATION_TEST" -> "REVIEWING";
            case "ACCEPT", "INTEGRATE" -> "FINISHING";
            case "DONE" -> "COMPLETE";
            case "ERROR" -> "ERROR";
            default -> "IDLE";
        };
    }

    // ==================== 数据结构 ====================

    public record SprintBoard(
            String threadId,
            String requirement,
            String pipelineStatus,
            int revisionCount,
            int totalTasks,
            List<TaskCard> inbox,
            List<TaskCard> inProgress,
            List<TaskCard> review,
            List<TaskCard> done,
            List<TaskCard> failed
    ) {}

    public record TaskCard(
            String taskId,
            String name,
            String description,
            String status,
            String columnStatus,
            String priority,
            String source,
            String verdict,
            String error,
            boolean hasDiff,
            List<String> dependencies
    ) {}

    public record BoardSummary(
            String threadId,
            String requirement,
            String pipelineStatus,
            int totalTasks,
            long createdAt
    ) {}
}
