package com.forge.agent.engine.actions;

import com.forge.agent.action.BaseAgentAction;
import com.forge.agent.event.StateEventBus;
import com.forge.agent.llm.LlmGateway;
import com.forge.agent.metrics.AgentMetrics;
import com.forge.agent.state.MainState;
import org.springframework.stereotype.Component;

/**
 * 架构设计节点
 *
 * 职责：调用 LLM 分析需求，输出架构设计方案
 * 包括：服务拆分、依赖图、技术选型等
 *
 * 输入：state.requirement + state.analysis
 * 输出：写入 state.analysis（追加架构设计部分）
 */
@Component
public class ArchitectureAction extends BaseAgentAction {

    private final LlmGateway llmGateway;

    public ArchitectureAction(AgentMetrics metrics, StateEventBus eventBus, LlmGateway llmGateway) {
        super(metrics, eventBus);
        this.llmGateway = llmGateway;
    }

    @Override
    protected MainState doExecute(MainState state) {
        String requirement = state.getRequirement();
        String analysis = state.getAnalysis();

        if (requirement == null || requirement.isBlank()) {
            state.setError("需求描述为空，无法进行架构设计");
            return state;
        }

        // 调用 LLM 生成架构设计方案
        String architecture = llmGateway.designArchitecture(requirement, analysis);
        state.setAnalysis(architecture);
        log.info("架构设计完成，结果长度: {}", architecture.length());
        return state;
    }

    @Override
    public String name() {
        return "architecture";
    }

    @Override
    public String phase() {
        return "ARCHITECTURE";
    }
}
