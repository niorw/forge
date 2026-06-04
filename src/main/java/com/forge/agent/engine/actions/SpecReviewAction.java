package com.forge.agent.engine.actions;

import com.forge.agent.action.BaseAgentAction;
import com.forge.agent.event.StateEventBus;
import com.forge.agent.metrics.AgentMetrics;
import com.forge.agent.state.MainState;
import com.forge.agent.state.SubSpec;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spec 审查质量门节点
 *
 * 职责：检查 subSpecs 是否完整、合格，作为质量门（Quality Gate）
 * 这是一个 interruptsBefore 中断点 — 执行到这里会暂停，等待人工审查后才继续
 *
 * 输入：state.subSpecs
 * 输出：设置 state.approvalStatus 为 WAITING_APPROVAL
 */
@Component
public class SpecReviewAction extends BaseAgentAction {

    public SpecReviewAction(AgentMetrics metrics, StateEventBus eventBus) {
        super(metrics, eventBus);
    }

    @Override
    protected MainState doExecute(MainState state) {
        List<SubSpec> subSpecs = state.getSubSpecs();

        // 检查 subSpecs 是否存在
        if (subSpecs == null || subSpecs.isEmpty()) {
            state.setError("没有可审查的 Spec，请先执行 SpecAuthor 节点");
            return state;
        }

        // 检查每个 SubSpec 是否有内容
        int emptyCount = 0;
        for (SubSpec spec : subSpecs) {
            if (spec.content() == null || spec.content().isBlank()) {
                log.warn("任务 [{}] 的 Spec 内容为空", spec.name());
                emptyCount++;
            }
        }

        if (emptyCount > 0) {
            state.setError(String.format("有 %d 个 Spec 内容为空，请补充后重试", emptyCount));
            return state;
        }

        // 设置审批状态为等待审批 — 质量门中断点
        state.setApprovalStatus(MainState.ApprovalStatus.WAITING_APPROVAL.name());
        log.info("Spec 审查质量门：共 {} 个 Spec 等待人工审批", subSpecs.size());
        return state;
    }

    @Override
    public String name() {
        return "specReview";
    }

    @Override
    public String phase() {
        return "SPEC_REVIEW";
    }
}
