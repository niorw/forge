package com.forge.agent.report;

import com.forge.agent.state.MainState;
import com.forge.agent.state.SubTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 价值报表 + 飞书推送服务
 * 
 * 负责：
 * 1. 生成 Agent 执行的价值报表（任务完成率、耗时统计等）
 * 2. 将报表推送到飞书群（通过 Webhook）
 * 3. 支持自定义报表模板
 */
@Service
public class ValueReportService {

    private static final Logger log = LoggerFactory.getLogger(ValueReportService.class);

    @Value("${agent.feishu.webhook-url:}")
    private String feishuWebhookUrl;

    @Value("${agent.feishu.enabled:false}")
    private boolean feishuEnabled;

    private final RestTemplate restTemplate;

    public ValueReportService() {
        this.restTemplate = new RestTemplate();
    }

    /**
     * 生成并推送价值报表
     * 
     * @param state 主Agent状态
     */
    public void generateAndPushReport(MainState state) {
        // 生成报表
        String report = generateReport(state);

        // 推送到飞书
        if (feishuEnabled && feishuWebhookUrl != null && !feishuWebhookUrl.isBlank()) {
            pushToFeishu(report);
        } else {
            log.info("飞书推送未启用，报表内容:\n{}", report);
        }
    }

    /**
     * 生成价值报表
     */
    public String generateReport(MainState state) {
        List<SubTask> subTasks = state.getSubTasks();

        // 统计数据
        int totalTasks = subTasks != null ? subTasks.size() : 0;
        long successTasks = subTasks != null ?
                subTasks.stream().filter(t -> "SUCCESS".equals(t.status())).count() : 0;
        long failedTasks = subTasks != null ?
                subTasks.stream().filter(t -> "FAILED".equals(t.status())).count() : 0;
        double successRate = totalTasks > 0 ? (double) successTasks / totalTasks * 100 : 0;

        // 生成报表内容
        return """
                ## Agent 执行价值报表
                
                **生成时间**: %s
                
                ### 执行摘要
                - **需求**: %s
                - **当前阶段**: %s
                - **总体状态**: %s
                
                ### 任务统计
                - **总任务数**: %d
                - **成功任务**: %d
                - **失败任务**: %d
                - **成功率**: %.1f%%
                
                ### 任务详情
                %s
                
                ### 最终结果
                %s
                """.formatted(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                state.getRequirement() != null ? state.getRequirement() : "未知",
                state.getPhase(),
                "DONE".equals(state.getPhase()) ? "✅ 完成" : "❌ " + state.getPhase(),
                totalTasks,
                successTasks,
                failedTasks,
                successRate,
                buildTaskDetails(subTasks),
                state.getFinalResult() != null ? state.getFinalResult() : "暂无"
        );
    }

    /**
     * 构建任务详情
     */
    private String buildTaskDetails(List<SubTask> subTasks) {
        if (subTasks == null || subTasks.isEmpty()) {
            return "暂无任务";
        }

        StringBuilder sb = new StringBuilder();
        for (SubTask task : subTasks) {
            String statusEmoji = switch (task.status()) {
                case "SUCCESS" -> "✅";
                case "FAILED" -> "❌";
                case "RUNNING" -> "🔄";
                default -> "⏳";
            };
            sb.append(String.format("- %s **%s** (%s)\n", statusEmoji, task.name(), task.status()));
        }
        return sb.toString();
    }

    /**
     * 推送报表到飞书
     * 
     * 使用飞书自定义机器人 Webhook 推送消息
     */
    public void pushToFeishu(String report) {
        try {
            // 构建飞书消息体
            Map<String, Object> message = new HashMap<>();
            message.put("msg_type", "interactive");

            Map<String, Object> card = new HashMap<>();
            card.put("header", Map.of(
                    "title", Map.of("tag", "plain_text", "content", "🤖 Agent 执行报表"),
                    "template", "blue"
            ));

            card.put("elements", List.of(
                    Map.of("tag", "markdown", "content", report)
            ));

            message.put("card", card);

            // 发送 HTTP POST 请求
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(message, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(feishuWebhookUrl, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("飞书推送成功");
            } else {
                log.warn("飞书推送失败: status={}", response.getStatusCode());
            }

        } catch (Exception e) {
            log.error("飞书推送异常", e);
        }
    }

    /**
     * 推送简单文本消息到飞书
     */
    public void pushTextToFeishu(String text) {
        if (!feishuEnabled || feishuWebhookUrl == null || feishuWebhookUrl.isBlank()) {
            return;
        }

        try {
            Map<String, Object> message = Map.of(
                    "msg_type", "text",
                    "content", Map.of("text", text)
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(message, headers);
            restTemplate.postForEntity(feishuWebhookUrl, request, String.class);

            log.info("飞书文本消息推送成功");
        } catch (Exception e) {
            log.error("飞书文本消息推送失败", e);
        }
    }
}
