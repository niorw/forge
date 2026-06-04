package com.forge.agent.engine.actions;

import com.forge.agent.action.BaseAgentAction;
import com.forge.agent.event.StateEventBus;
import com.forge.agent.metrics.AgentMetrics;
import com.forge.agent.state.MainState;
import com.forge.agent.state.MainState.ApprovalStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 审批节点 — 对话记忆版本。
 *
 * 核心改进（来自 Anthropic Harness Engineering）：
 * "Sprint 合约——在编码开始之前，关于完成标准的显式协议。
 *  当审批驳回后，不应该从零开始，而是在上一轮基础上修改。"
 *
 * 驳回时：
 * 1. 保存驳回原因到 state.rejectionReason
 * 2. 序列化当前 DAG 为 JSON 保存到 state.previousDAGJson
 * 3. 递增修订计数器 state.revisionCount
 * 4. plan 节点读取这些字段做增量修改
 */
@Component
public class ApprovalAction extends BaseAgentAction {

    private final ObjectMapper objectMapper;

    public ApprovalAction(AgentMetrics metrics, StateEventBus eventBus, ObjectMapper objectMapper) {
        super(metrics, eventBus);
        this.objectMapper = objectMapper;
    }

    @Override
    protected MainState doExecute(MainState state) {
        ApprovalStatus status = state.getApprovalStatusEnum();
        log.info("当前审批状态: {}", status);

        switch (status) {
            case PENDING -> {
                log.info("审批请求已提交，等待人工审批...");
                state.setApprovalStatus(ApprovalStatus.WAITING_APPROVAL.name());
                eventBus().fireApprovalRequired(state, "approval");
            }
            case APPROVED -> {
                log.info("审批已通过，继续执行后续节点");
                state.setPhase(MainState.Phase.SCHEDULE.name());
            }
            case REJECTED -> {
                // 驳回时保存对话记忆
                log.info("审批被拒绝，保存对话记忆以便增量修改");
                saveRejectionMemory(state);
            }
            case WAITING_APPROVAL -> {
                log.info("仍在等待人工审批...");
            }
        }

        return state;
    }

    /**
     * 处理审批回调（由 ApprovalController 调用）
     */
    public MainState handleCallback(MainState state, boolean approved, String comment) {
        if (approved) {
            state.setApprovalStatus(ApprovalStatus.APPROVED.name());
            log.info("审批通过，意见: {}", comment);
        } else {
            state.setApprovalStatus(ApprovalStatus.REJECTED.name());
            state.setRejectionReason(comment);
            log.info("审批拒绝，原因: {}", comment);
        }
        return state;
    }

    /**
     * 保存驳回记忆 — 供 plan 节点增量修改使用
     */
    private void saveRejectionMemory(MainState state) {
        // 1. 序列化当前 DAG 为 JSON
        try {
            if (state.getTaskDAG() != null && !state.getTaskDAG().isEmpty()) {
                String dagJson = objectMapper.writeValueAsString(state.getTaskDAG());
                state.setPreviousDAGJson(dagJson);
                log.info("已保存上一轮 DAG ({} 个节点)", state.getTaskDAG().size());
            }
        } catch (Exception e) {
            log.warn("序列化 DAG 失败: {}", e.getMessage());
        }

        // 2. 递增修订计数
        state.incrementRevisionCount();

        // 3. 设置错误信息（触发回到 plan 的路由）
        String reason = state.getRejectionReason() != null ? state.getRejectionReason() : "未说明原因";
        state.setError("人工审批被拒绝（第 " + state.getRevisionCount() + " 次修订），原因: " + reason);
    }
}
