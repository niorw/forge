package com.forge.agent.engine.actions;

import com.forge.agent.action.BaseAgentAction;
import com.forge.agent.event.StateEventBus;
import com.forge.agent.llm.LlmGateway;
import com.forge.agent.metrics.AgentMetrics;
import com.forge.agent.state.MainState;
import com.forge.agent.state.MainState.ApprovalStatus;
import com.forge.agent.state.SubTask;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 验收节点（质量门）
 *
 * 生成验收报告，汇总所有子任务结果与测试情况，
 * 然后将审批状态设为 WAITING_APPROVAL，等待人工验收后才继续执行。
 * 这是一个 interruptsBefore 中断点节点。
 */
@Component
public class AcceptAction extends BaseAgentAction {

    private final LlmGateway llmGateway;

    public AcceptAction(AgentMetrics metrics, StateEventBus eventBus, LlmGateway llmGateway) {
        super(metrics, eventBus);
        this.llmGateway = llmGateway;
    }

    @Override
    protected MainState doExecute(MainState state) {
        List<SubTask> subTasks = state.getSubTasks();

        // 构建验收报告摘要
        StringBuilder summary = new StringBuilder();
        summary.append("# 验收报告\n\n");

        // 需求信息
        summary.append("## 原始需求\n");
        summary.append(state.getRequirement() != null ? state.getRequirement() : "未提供").append("\n\n");

        // 子任务执行情况
        summary.append("## 子任务执行情况\n");
        if (subTasks != null && !subTasks.isEmpty()) {
            long successCount = subTasks.stream().filter(t -> "SUCCESS".equals(t.status())).count();
            long failedCount = subTasks.stream().filter(t -> "FAILED".equals(t.status())).count();
            summary.append(String.format("- 总数: %d, 成功: %d, 失败: %d\n\n", subTasks.size(), successCount, failedCount));

            for (SubTask subTask : subTasks) {
                summary.append(String.format("### %s [%s]\n", subTask.name(), subTask.status()));
                if (subTask.result() != null) {
                    // 截取前 500 字符避免报告过长
                    String resultPreview = subTask.result().length() > 500
                        ? subTask.result().substring(0, 500) + "..."
                        : subTask.result();
                    summary.append(resultPreview).append("\n\n");
                }
                if (subTask.error() != null) {
                    summary.append("错误: ").append(subTask.error()).append("\n\n");
                }
            }
        } else {
            summary.append("无子任务记录\n\n");
        }

        // 集成测试结果
        String finalResult = state.getFinalResult();
        if (finalResult != null) {
            summary.append("## 集成测试结果\n");
            summary.append(finalResult).append("\n\n");
        }

        String report = summary.toString();
        log.info("验收报告已生成，长度: {}，等待人工审批", report.length());

        // 设置最终结果为验收报告
        state.setFinalResult(report);

        // 设置为等待审批状态 — 质量门中断点
        state.setApprovalStatus(ApprovalStatus.WAITING_APPROVAL.name());
        eventBus().fireApprovalRequired(state, "accept");

        log.info("验收质量门已触发，等待人工审批...");
        return state;
    }
}
