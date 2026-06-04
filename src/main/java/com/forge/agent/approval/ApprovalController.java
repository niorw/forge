package com.forge.agent.approval;

import com.forge.agent.engine.actions.ApprovalAction;
import com.forge.agent.state.MainState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 审批 REST API 控制器
 * 
 * 改进：
 * 1. 使用 ApprovalAction.handleCallback() 替代直接操作 state
 * 2. 审批状态使用枚举（通过 MainState.ApprovalStatus）
 */
@RestController
@RequestMapping("/api/approval")
public class ApprovalController {

    private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);

    private final ApprovalAction approvalAction;
    private final Map<String, MainState> pendingStates = new ConcurrentHashMap<>();

    public ApprovalController(ApprovalAction approvalAction) {
        this.approvalAction = approvalAction;
    }

    public void registerPendingApproval(String sessionId, MainState state) {
        pendingStates.put(sessionId, state);
        log.info("审批请求已注册: sessionId={}", sessionId);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(@RequestParam String sessionId) {
        MainState state = pendingStates.get(sessionId);
        if (state == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "approvalStatus", state.getApprovalStatus(),
                "phase", state.getPhase(),
                "currentNode", state.getCurrentNode() != null ? state.getCurrentNode() : ""
        ));
    }

    @PostMapping("/approve")
    public ResponseEntity<Map<String, String>> approve(
            @RequestParam String sessionId,
            @RequestBody(required = false) Map<String, String> body) {

        String comment = body != null ? body.getOrDefault("comment", "审批通过") : "审批通过";
        log.info("收到审批通过请求: sessionId={}, comment={}", sessionId, comment);

        MainState state = pendingStates.get(sessionId);
        if (state == null) return ResponseEntity.notFound().build();

        // 委托给 ApprovalAction 处理回调
        approvalAction.handleCallback(state, true, comment);

        return ResponseEntity.ok(Map.of(
                "status", "approved",
                "message", "审批已通过",
                "comment", comment
        ));
    }

    @PostMapping("/reject")
    public ResponseEntity<Map<String, String>> reject(
            @RequestParam String sessionId,
            @RequestBody(required = false) Map<String, String> body) {

        String reason = body != null ? body.getOrDefault("reason", "审批拒绝") : "审批拒绝";
        log.info("收到审批拒绝请求: sessionId={}, reason={}", sessionId, reason);

        MainState state = pendingStates.get(sessionId);
        if (state == null) return ResponseEntity.notFound().build();

        approvalAction.handleCallback(state, false, reason);

        return ResponseEntity.ok(Map.of(
                "status", "rejected",
                "message", "审批已拒绝",
                "reason", reason
        ));
    }

    @GetMapping("/pending")
    public ResponseEntity<Map<String, String>> listPending() {
        Map<String, String> pending = new ConcurrentHashMap<>();
        pendingStates.forEach((sessionId, state) -> {
            if ("WAITING_APPROVAL".equals(state.getApprovalStatus())) {
                pending.put(sessionId, state.getRequirement() != null ? state.getRequirement() : "未知需求");
            }
        });
        return ResponseEntity.ok(pending);
    }
}
