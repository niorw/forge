package com.forge.agent.integration.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.forge.agent.engine.MainAgentGraph;
import com.forge.agent.approval.ApprovalController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 飞书 Webhook 处理器
 *
 * 接收飞书事件回调：
 * 1. URL 验证（challenge）— 飞书订阅回调时的首次验证
 * 2. 卡片按钮回调 — 用户点击审批卡片上的通过/驳回按钮
 * 3. 消息事件 — 用户在群里 @机器人 发消息
 *
 * 以及提供内部通知推送接口供 Agent 内部调用。
 */
@RestController
@RequestMapping("/api/feishu")
public class FeishuWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(FeishuWebhookHandler.class);

    private final FeishuNotifier notifier;
    private final MainAgentGraph mainAgentGraph;
    private final ApprovalController approvalController;

    public FeishuWebhookHandler(FeishuNotifier notifier,
                                 MainAgentGraph mainAgentGraph,
                                 ApprovalController approvalController) {
        this.notifier = notifier;
        this.mainAgentGraph = mainAgentGraph;
        this.approvalController = approvalController;
    }

    /**
     * 接收飞书事件回调
     */
    @PostMapping("/event")
    public ResponseEntity<Map<String, Object>> handleEvent(@RequestBody JsonNode body) {
        log.info("收到飞书事件回调: {}", body);

        try {
            // 1. URL 验证（challenge）
            if (body.has("challenge")) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("challenge", body.get("challenge").asText());
                log.info("飞书 URL 验证通过");
                return ResponseEntity.ok(resp);
            }

            // 2. 卡片按钮回调
            if (body.has("action")) {
                return handleCardAction(body);
            }

            // 3. 事件回调（消息事件等）
            if (body.has("header") && body.has("event")) {
                return handleEventCallback(body);
            }

            log.warn("未识别的飞书事件类型: {}", body);
            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.error("处理飞书事件异常", e);
            Map<String, Object> errorResp = new HashMap<>();
            errorResp.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(errorResp);
        }
    }

    /**
     * 主动推送通知（供 Agent 内部调用）
     */
    @PostMapping("/notify")
    public ResponseEntity<Map<String, Object>> notify(@RequestBody JsonNode request) {
        log.info("收到内部通知请求: {}", request);

        try {
            String threadId = request.path("threadId").asText();
            String cardType = request.path("cardType").asText();
            JsonNode payload = request.path("payload");

            switch (cardType) {
                case "approval":
                    notifier.notifyApprovalRequired(threadId,
                            payload.path("requirement").asText(),
                            payload.path("dagSummary").asText());
                    break;
                case "specReview":
                    notifier.notifySpecReviewRequired(threadId,
                            payload.path("specSummary").asText());
                    break;
                case "accept":
                    notifier.notifyAcceptRequired(threadId,
                            payload.path("testReport").asText());
                    break;
                case "result":
                    notifier.notifyResult(threadId,
                            payload.path("status").asText(),
                            payload.path("summary").asText());
                    break;
                default:
                    Map<String, Object> err = new HashMap<>();
                    err.put("error", "未知的卡片类型: " + cardType);
                    return ResponseEntity.badRequest().body(err);
            }

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("threadId", threadId);
            resp.put("cardType", cardType);
            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            log.error("推送通知异常", e);
            Map<String, Object> errorResp = new HashMap<>();
            errorResp.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(errorResp);
        }
    }

    // ==================== 内部处理 ====================

    /**
     * 卡片按钮回调：用户点"通过"或"驳回"
     */
    private ResponseEntity<Map<String, Object>> handleCardAction(JsonNode body) {
        JsonNode action = body.path("action");
        JsonNode value = action.path("value");

        String actionType = value.path("action").asText("unknown");
        String threadId = value.path("threadId").asText("");

        log.info("收到卡片按钮回调: action={}, threadId={}", actionType, threadId);

        switch (actionType) {
            case "approve":
                approvalController.approve(threadId, Map.of("comment", "飞书审批通过"));
                log.info("审批通过: threadId={}", threadId);
                break;
            case "reject":
                approvalController.reject(threadId, Map.of("reason", "飞书审批驳回"));
                log.info("审批驳回: threadId={}", threadId);
                break;
            default:
                log.warn("未知的卡片操作: {}", actionType);
                break;
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("toast", Map.of(
                "type", "info",
                "content", "approve".equals(actionType) ? "已通过" : "已驳回"
        ));
        return ResponseEntity.ok(resp);
    }

    /**
     * 事件回调：用户 @机器人 发消息
     */
    private ResponseEntity<Map<String, Object>> handleEventCallback(JsonNode body) {
        JsonNode header = body.path("header");
        String eventType = header.path("event_type").asText("");

        log.info("事件回调: eventType={}", eventType);

        if ("im.message.receive_v1".equals(eventType)) {
            JsonNode event = body.path("event");
            JsonNode message = event.path("message");
            String messageType = message.path("message_type").asText("");

            if ("text".equals(messageType)) {
                String text = message.path("content").path("text").asText("").trim();
                if (!text.isEmpty()) {
                    log.info("收到消息: text={}", text);
                    // 异步启动 Agent 执行
                    mainAgentGraph.startExecution(text, "feishu-" + System.currentTimeMillis());
                }
            }
        }

        return ResponseEntity.ok(new HashMap<>());
    }
}
