package com.forge.agent.engine.actions;

import com.forge.agent.action.BaseAgentAction;
import com.forge.agent.event.StateEventBus;
import com.forge.agent.llm.LlmGateway;
import com.forge.agent.metrics.AgentMetrics;
import com.forge.agent.state.MainState;
import org.springframework.stereotype.Component;

/**
 * 需求分析节点
 * 
 * 改进：继承 BaseAgentAction，消除 try/catch/setPhase/setError 样板代码
 * 业务逻辑从 ~60 行精简到 ~15 行
 */
@Component
public class AnalyzeAction extends BaseAgentAction {

    private final LlmGateway llmGateway;

    public AnalyzeAction(AgentMetrics metrics, StateEventBus eventBus, LlmGateway llmGateway) {
        super(metrics, eventBus);
        this.llmGateway = llmGateway;
    }

    @Override
    protected MainState doExecute(MainState state) {
        String requirement = state.getRequirement();
        if (requirement == null || requirement.isBlank()) {
            state.setError("需求描述为空，无法进行分析");
            return state;
        }

        String analysis = llmGateway.analyzeRequirement(requirement);
        state.setAnalysis(analysis);
        log.info("需求分析完成，分析结果长度: {}", analysis.length());
        return state;
    }
}
