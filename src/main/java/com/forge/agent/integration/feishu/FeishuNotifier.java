package com.forge.agent.integration.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 飞书通知服务
 *
 * 封装飞书 API 调用，提供审批卡片推送、结果通知等能力。
 * 支持 Webhook（自定义机器人）和飞书应用 API 两种方式。
 *
 * 配置项：
 *   agent.feishu.webhook-url  — 飞书自定义机器人 Webhook 地址
 *   agent.feishu.app-id       — 飞书应用 App ID
 *   agent.feishu.app-secret   — 飞书应用 App Secret
 */
@Component
public class FeishuNotifier {

    private static final Logger log = LoggerFactory.getLogger(FeishuNotifier.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final FeishuCardBuilder cardBuilder;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${agent.feishu.webhook-url:}")
    private String webhookUrl;

    @Value("${agent.feishu.app-id:}")
    private String appId;

    @Value("${agent.feishu.app-secret:}")
    private String appSecret;

    private volatile String cachedToken;
    private volatile long tokenExpireAt = 0;

    public FeishuNotifier(FeishuCardBuilder cardBuilder) {
        this.cardBuilder = cardBuilder;
    }

    /** 推送需求审批卡片 */
    public void notifyApprovalRequired(String threadId, String requirement, String dagSummary) {
        String cardJson = cardBuilder.buildApprovalCard(threadId, requirement, dagSummary);
        sendCard(cardJson);
        log.info("已推送需求审批卡片: threadId={}", threadId);
    }

    /** 推送 Spec 审查卡片 */
    public void notifySpecReviewRequired(String threadId, String specSummary) {
        String cardJson = cardBuilder.buildSpecReviewCard(threadId, specSummary);
        sendCard(cardJson);
        log.info("已推送 Spec 审查卡片: threadId={}", threadId);
    }

    /** 推送验收审批卡片 */
    public void notifyAcceptRequired(String threadId, String testReport) {
        String cardJson = cardBuilder.buildAcceptCard(threadId, testReport);
        sendCard(cardJson);
        log.info("已推送验收审批卡片: threadId={}", threadId);
    }

    /** 推送执行结果通知 */
    public void notifyResult(String threadId, String status, String summary) {
        String cardJson = cardBuilder.buildResultCard(threadId, status, summary);
        sendCard(cardJson);
        log.info("已推送结果通知: threadId={}, status={}", threadId, status);
    }

    /**
     * 发送卡片消息
     * 优先 Webhook，其次飞书应用 API
     */
    public void sendCard(String cardJson) {
        if (webhookUrl != null && !webhookUrl.isEmpty()) {
            sendViaWebhook(cardJson);
        } else if (appId != null && !appId.isEmpty()) {
            log.warn("飞书应用 API 发送暂未实现，请配置 agent.feishu.webhook-url");
        } else {
            log.warn("飞书通知未配置，跳过发送。请配置 agent.feishu.webhook-url");
        }
    }

    /**
     * 通过 Webhook 发送
     * POST {webhook-url}
     * {"msg_type": "interactive", "card": {...}}
     */
    private void sendViaWebhook(String cardJson) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new HashMap<>();
            body.put("msg_type", "interactive");
            body.put("card", mapper.readValue(cardJson, Map.class));

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(webhookUrl, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.debug("飞书 Webhook 发送成功: {}", response.getBody());
            } else {
                log.error("飞书 Webhook 发送失败: status={}, body={}",
                        response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            log.error("飞书 Webhook 发送异常", e);
        }
    }
}
