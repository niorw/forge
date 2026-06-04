package com.forge.agent.integration.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 飞书消息卡片构建器
 *
 * 根据不同审批场景生成飞书 Interactive Card JSON：
 * - 需求审批卡片（蓝色）
 * - Spec 审查卡片（橙色）
 * - 验收审批卡片（绿色）
 * - 结果通知卡片（按状态变色）
 *
 * 每张卡片都带"通过"/"驳回"按钮，按钮的 value 包含 threadId，
 * 飞书回调时通过 threadId 定位到具体的 Agent 任务。
 */
@Component
public class FeishuCardBuilder {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 需求审批卡片
     */
    public String buildApprovalCard(String threadId, String requirement, String dagSummary) {
        ObjectNode card = createBaseCard("📋 需求审批", "blue");
        ArrayNode elements = card.putArray("elements");

        addMarkdownDiv(elements, "**需求描述**\n" + requirement);
        if (dagSummary != null && !dagSummary.isEmpty()) {
            addMarkdownDiv(elements, "**执行计划摘要**\n" + dagSummary);
        }
        elements.addObject().put("tag", "hr");
        addApprovalButtons(elements, threadId);

        return card.toString();
    }

    /**
     * Spec 审查卡片
     */
    public String buildSpecReviewCard(String threadId, String specSummary) {
        ObjectNode card = createBaseCard("📝 Spec 审查", "orange");
        ArrayNode elements = card.putArray("elements");

        addMarkdownDiv(elements, "**技术方案摘要**\n" + specSummary);
        elements.addObject().put("tag", "hr");
        addApprovalButtons(elements, threadId);

        return card.toString();
    }

    /**
     * 验收审批卡片
     */
    public String buildAcceptCard(String threadId, String testReport) {
        ObjectNode card = createBaseCard("✅ 验收审批", "green");
        ArrayNode elements = card.putArray("elements");

        addMarkdownDiv(elements, "**测试报告**\n" + testReport);
        elements.addObject().put("tag", "hr");
        addApprovalButtons(elements, threadId);

        return card.toString();
    }

    /**
     * 结果通知卡片
     */
    public String buildResultCard(String threadId, String status, String summary) {
        String template;
        String title;
        switch (status.toUpperCase()) {
            case "SUCCESS":
                template = "green";
                title = "✅ 任务执行成功";
                break;
            case "REJECTED":
                template = "orange";
                title = "🚫 任务已被驳回";
                break;
            default:
                template = "red";
                title = "❌ 任务执行失败";
                break;
        }

        ObjectNode card = createBaseCard(title, template);
        ArrayNode elements = card.putArray("elements");

        addMarkdownDiv(elements, "**状态**: " + status);
        addMarkdownDiv(elements, "**摘要**\n" + summary);
        addMarkdownDiv(elements, "**线程 ID**: " + threadId);

        return card.toString();
    }

    // ==================== 内部辅助 ====================

    private ObjectNode createBaseCard(String title, String template) {
        ObjectNode card = mapper.createObjectNode();
        ObjectNode config = card.putObject("config");
        config.put("wide_screen_mode", true);
        ObjectNode header = card.putObject("header");
        ObjectNode titleNode = header.putObject("title");
        titleNode.put("tag", "plain_text");
        titleNode.put("content", title);
        header.put("template", template);
        return card;
    }

    private void addMarkdownDiv(ArrayNode elements, String content) {
        ObjectNode div = elements.addObject();
        div.put("tag", "div");
        ObjectNode text = div.putObject("text");
        text.put("tag", "lark_md");
        text.put("content", content);
    }

    private void addApprovalButtons(ArrayNode elements, String threadId) {
        ObjectNode actionBlock = elements.addObject();
        actionBlock.put("tag", "action");
        ArrayNode actions = actionBlock.putArray("actions");

        // 通过按钮
        ObjectNode approveBtn = actions.addObject();
        approveBtn.put("tag", "button");
        ObjectNode approveText = approveBtn.putObject("text");
        approveText.put("tag", "plain_text");
        approveText.put("content", "通过");
        approveBtn.put("type", "primary");
        ObjectNode approveValue = approveBtn.putObject("value");
        approveValue.put("action", "approve");
        approveValue.put("threadId", threadId);

        // 驳回按钮
        ObjectNode rejectBtn = actions.addObject();
        rejectBtn.put("tag", "button");
        ObjectNode rejectText = rejectBtn.putObject("text");
        rejectText.put("tag", "plain_text");
        rejectText.put("content", "驳回");
        rejectBtn.put("type", "danger");
        ObjectNode rejectValue = rejectBtn.putObject("value");
        rejectValue.put("action", "reject");
        rejectValue.put("threadId", threadId);
    }
}
