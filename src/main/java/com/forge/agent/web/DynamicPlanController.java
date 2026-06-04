package com.forge.agent.web;

import com.forge.agent.engine.DagEngine;
import com.forge.agent.engine.MainAgentGraph;
import com.forge.agent.state.MainState;
import com.forge.agent.state.SubTask;
import org.bsc.langgraph4j.RunnableConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 动态规划 REST API — 运行时插入/修改/删除 DAG 任务。
 *
 * 核心理念（来自 Anthropic Harness Engineering）：
 * "中途发现新依赖 → 动态插入新任务。
 *  某个任务失败 → 动态调整后续任务。
 *  人工追加需求 → 动态扩展 DAG。"
 *
 * 支持操作：
 * 1. INSERT  — 插入新任务（可指定依赖）
 * 2. REMOVE  — 移除未执行的任务
 * 3. RETRY   — 重试失败的任务
 * 4. REORDER — 修改任务优先级
 */
@RestController
@RequestMapping("/api/dynamic-plan")
public class DynamicPlanController {

    private static final Logger log = LoggerFactory.getLogger(DynamicPlanController.class);

    private final MainAgentGraph mainAgentGraph;
    private final DagEngine dagEngine;

    public DynamicPlanController(MainAgentGraph mainAgentGraph, DagEngine dagEngine) {
        this.mainAgentGraph = mainAgentGraph;
        this.dagEngine = dagEngine;
    }

    /**
     * 插入新任务到 DAG
     *
     * @param threadId 工作流线程 ID
     * @param request  任务信息（name, description, dependencies）
     */
    @PostMapping("/insert")
    public ResponseEntity<Map<String, Object>> insertTask(
            @RequestParam String threadId,
            @RequestBody InsertRequest request) {

        try {
            var config = RunnableConfig.builder().threadId(threadId).build();
            var snapshot = mainAgentGraph.getWorkflow().lastStateOf(config);

            if (snapshot.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            MainState state = snapshot.get().state();
            List<SubTask> tasks = state.getSubTasks();
            if (tasks == null) tasks = new ArrayList<>();

            // 生成任务 ID
            String taskId = request.taskId != null ? request.taskId
                    : "dynamic-" + UUID.randomUUID().toString().substring(0, 8);

            // 创建新子任务
            SubTask newTask = SubTask.create(
                    taskId,
                    request.name,
                    request.description != null ? request.description : "",
                    request.dependencies != null ? request.dependencies : List.of()
            );

            // 添加到任务列表
            List<SubTask> updated = new ArrayList<>(tasks);
            updated.add(newTask);
            state.setSubTasks(updated);

            log.info("动态插入任务: taskId={}, name={}, dependencies={}",
                    taskId, request.name, request.dependencies);

            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "taskId", taskId,
                    "name", request.name,
                    "totalTasks", updated.size(),
                    "message", "任务已插入，将在下一轮调度时执行"
            ));

        } catch (Exception e) {
            log.error("插入任务失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 移除未执行的任务
     *
     * @param threadId 工作流线程 ID
     * @param taskId   要移除的任务 ID
     */
    @DeleteMapping("/remove/{taskId}")
    public ResponseEntity<Map<String, Object>> removeTask(
            @RequestParam String threadId,
            @PathVariable String taskId) {

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

            // 只能移除 PENDING 状态的任务
            Optional<SubTask> target = tasks.stream()
                    .filter(t -> t.taskId().equals(taskId))
                    .findFirst();

            if (target.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            if (!"PENDING".equals(target.get().status())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "只能移除 PENDING 状态的任务，当前状态: " + target.get().status()
                ));
            }

            // 检查是否有其他任务依赖此任务
            List<String> dependents = tasks.stream()
                    .filter(t -> t.dependencies() != null && t.dependencies().contains(taskId))
                    .map(SubTask::taskId)
                    .toList();

            if (!dependents.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "任务 " + taskId + " 被以下任务依赖，无法移除",
                        "dependents", dependents
                ));
            }

            // 移除任务
            List<SubTask> updated = new ArrayList<>(tasks);
            updated.removeIf(t -> t.taskId().equals(taskId));
            state.setSubTasks(updated);

            log.info("动态移除任务: taskId={}", taskId);

            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "taskId", taskId,
                    "message", "任务已移除"
            ));

        } catch (Exception e) {
            log.error("移除任务失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 重试失败的任务
     *
     * @param threadId 工作流线程 ID
     * @param taskId   要重试的任务 ID
     */
    @PostMapping("/retry/{taskId}")
    public ResponseEntity<Map<String, Object>> retryTask(
            @RequestParam String threadId,
            @PathVariable String taskId) {

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
                    if (!"FAILED".equals(task.status()) && !"SUCCESS".equals(task.status())) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "只能重试 FAILED 或 SUCCESS 状态的任务"
                        ));
                    }
                    // 重置为 PENDING
                    updated.add(SubTask.create(taskId, task.name(), task.description(), task.dependencies()));
                    found = true;
                } else {
                    updated.add(task);
                }
            }

            if (!found) {
                return ResponseEntity.notFound().build();
            }

            state.setSubTasks(updated);
            log.info("动态重试任务: taskId={}", taskId);

            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "taskId", taskId,
                    "message", "任务已重置为 PENDING，将在下一轮调度时重新执行"
            ));

        } catch (Exception e) {
            log.error("重试任务失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 批量插入任务（人工追加需求场景）
     */
    @PostMapping("/batch-insert")
    public ResponseEntity<Map<String, Object>> batchInsert(
            @RequestParam String threadId,
            @RequestBody List<InsertRequest> requests) {

        try {
            var config = RunnableConfig.builder().threadId(threadId).build();
            var snapshot = mainAgentGraph.getWorkflow().lastStateOf(config);

            if (snapshot.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            MainState state = snapshot.get().state();
            List<SubTask> tasks = state.getSubTasks();
            if (tasks == null) tasks = new ArrayList<>();

            List<SubTask> updated = new ArrayList<>(tasks);
            List<String> insertedIds = new ArrayList<>();

            for (InsertRequest request : requests) {
                String taskId = request.taskId != null ? request.taskId
                        : "dynamic-" + UUID.randomUUID().toString().substring(0, 8);

                updated.add(SubTask.create(
                        taskId,
                        request.name,
                        request.description != null ? request.description : "",
                        request.dependencies != null ? request.dependencies : List.of()
                ));
                insertedIds.add(taskId);
            }

            state.setSubTasks(updated);
            log.info("批量插入 {} 个任务: {}", insertedIds.size(), insertedIds);

            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "insertedIds", insertedIds,
                    "totalTasks", updated.size()
            ));

        } catch (Exception e) {
            log.error("批量插入任务失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== 请求体 ====================

    public static class InsertRequest {
        public String taskId;
        public String name;
        public String description;
        public List<String> dependencies;
    }
}
