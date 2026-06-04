package com.forge.agent.engine.actions;

import com.forge.agent.action.BaseAgentAction;
import com.forge.agent.event.StateEventBus;
import com.forge.agent.llm.LlmGateway;
import com.forge.agent.metrics.AgentMetrics;
import com.forge.agent.state.MainState;
import com.forge.agent.state.SubSpec;
import com.forge.agent.state.TaskNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Spec 编写节点
 *
 * 职责：LLM 为每个 TaskNode 生成详细的 SubSpec
 * 包括：接口方法、验收标准、错误码定义等
 *
 * 输入：state.taskDAG + state.analysis（架构设计）
 * 输出：写入 state.subSpecs
 */
@Component
public class SpecAuthorAction extends BaseAgentAction {

    private final LlmGateway llmGateway;

    public SpecAuthorAction(AgentMetrics metrics, StateEventBus eventBus, LlmGateway llmGateway) {
        super(metrics, eventBus);
        this.llmGateway = llmGateway;
    }

    @Override
    protected MainState doExecute(MainState state) {
        List<TaskNode> taskDAG = state.getTaskDAG();
        String architecture = state.getAnalysis();

        if (taskDAG == null || taskDAG.isEmpty()) {
            state.setError("任务 DAG 为空，无法生成 Spec");
            return state;
        }

        // 为每个 TaskNode 生成 SubSpec
        List<SubSpec> subSpecs = new ArrayList<>();
        for (TaskNode taskNode : taskDAG) {
            log.info("为任务 [{}] 生成 Spec...", taskNode.name());
            String specContent = llmGateway.generateSubSpec(
                    taskNode.description(),
                    architecture,
                    taskNode.specRef() != null ? taskNode.specRef() : ""
            );
            SubSpec subSpec = SubSpec.draft(taskNode.nodeId(), taskNode.name(), specContent);
            subSpecs.add(subSpec);
        }

        state.setSubSpecs(subSpecs);
        log.info("Spec 编写完成，共生成 {} 个 SubSpec", subSpecs.size());
        return state;
    }

    @Override
    public String name() {
        return "specAuthor";
    }

    @Override
    public String phase() {
        return "SPEC_AUTHOR";
    }
}
