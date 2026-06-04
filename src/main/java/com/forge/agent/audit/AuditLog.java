package com.forge.agent.audit;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 审计日志记录 — 不可篡改的操作追溯。
 *
 * 核心理念（来自 Anthropic Harness Engineering）：
 * "谁触发了 Agent / 谁审批了 / 谁修改了 Spec — 操作日志不可篡改。"
 *
 * 审计事件类型：
 * - AGENT_TRIGGER   触发 Agent 执行
 * - APPROVAL_PASS   审批通过
 * - APPROVAL_REJECT 审批驳回
 * - SPEC_MODIFY     修改 Spec
 * - PROMPT_PUBLISH  发布 Prompt 新版本
 * - PROMPT_ROLLBACK 回滚 Prompt
 * - PROJECT_CREATE  创建项目
 * - PROJECT_MODIFY  修改项目配置
 * - TASK_INSERT     动态插入任务
 * - TASK_REMOVE     动态移除任务
 * - TASK_RETRY      动态重试任务
 * - SKILL_RELOAD    热更新 Skill
 * - CONFIG_CHANGE   配置变更
 */
public record AuditLog(
        String auditId,
        String timestamp,
        AuditAction action,
        String actor,
        String target,
        String detail,
        String threadId,
        String projectId,
        Map<String, Object> metadata
) {
    private static long counter = 0;
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    public enum AuditAction {
        AGENT_TRIGGER, APPROVAL_PASS, APPROVAL_REJECT,
        SPEC_MODIFY, PROMPT_PUBLISH, PROMPT_ROLLBACK,
        PROJECT_CREATE, PROJECT_MODIFY,
        TASK_INSERT, TASK_REMOVE, TASK_RETRY,
        SKILL_RELOAD, CONFIG_CHANGE
    }

    public static AuditLog of(AuditAction action, String actor, String target, String detail) {
        return new AuditLog(
                "aud-" + System.currentTimeMillis() + "-" + (++counter % 10000),
                FMT.format(Instant.now()),
                action, actor, target, detail, null, null, Map.of()
        );
    }

    public static AuditLog of(AuditAction action, String actor, String target, String detail,
                               String threadId, String projectId) {
        return new AuditLog(
                "aud-" + System.currentTimeMillis() + "-" + (++counter % 10000),
                FMT.format(Instant.now()),
                action, actor, target, detail, threadId, projectId, Map.of()
        );
    }
}
