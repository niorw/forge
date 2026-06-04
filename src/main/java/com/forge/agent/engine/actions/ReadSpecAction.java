package com.forge.agent.engine.actions;

import com.forge.agent.action.BaseAgentAction;
import com.forge.agent.event.StateEventBus;
import com.forge.agent.metrics.AgentMetrics;
import com.forge.agent.spec.SpecManager;
import com.forge.agent.spec.SpecValidator;
import com.forge.agent.state.MainState;
import com.forge.agent.state.SubSpec;
import com.forge.agent.state.TaskNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Spec 读取与绑定节点
 * 
 * 职责（SDD 流程）：
 * 1. 从 openspec/specs/ 加载人写好的 Spec 文件
 * 2. 将 Spec 内容绑定到 DAG 中对应的 TaskNode
 * 3. 校验 Spec 格式是否合规
 * 4. 生成 SubSpec 对象存入状态，供后续 DispatchAction 使用
 * 
 * 不再调用 LLM 生成 Spec — Spec 是人维护的契约，不是 LLM 的草稿。
 */
@Component
public class ReadSpecAction extends BaseAgentAction {

    private final SpecManager specManager;
    private final SpecValidator specValidator;

    public ReadSpecAction(AgentMetrics metrics, StateEventBus eventBus,
                          SpecManager specManager, SpecValidator specValidator) {
        super(metrics, eventBus);
        this.specManager = specManager;
        this.specValidator = specValidator;
    }

    @Override
    protected MainState doExecute(MainState state) {
        List<TaskNode> taskDAG = state.getTaskDAG();
        if (taskDAG == null || taskDAG.isEmpty()) {
            log.info("DAG 任务图为空，跳过 Spec 读取");
            return state;
        }

        // 加载所有可用 Spec
        Map<String, String> allSpecs = specManager.loadAllSpecs();
        if (allSpecs.isEmpty()) {
            log.warn("openspec/specs/ 目录下没有 Spec 文件");
            state.setError("没有找到任何 Spec 文件，请先在 openspec/specs/ 目录下放置 Spec");
            return state;
        }

        log.info("加载了 {} 个 Spec 文件: {}", allSpecs.size(), allSpecs.keySet());

        List<SubSpec> subSpecs = new ArrayList<>();
        int boundCount = 0;

        for (TaskNode taskNode : taskDAG) {
            if (taskNode.hasSpec()) {
                // Task 已绑定 Spec（Planner Agent 规划时指定的）
                String specContent = allSpecs.get(taskNode.specRef());
                if (specContent == null) {
                    log.warn("Task [{}] 引用的 Spec 不存在: {}", taskNode.nodeId(), taskNode.specRef());
                    continue;
                }

                // 按 specScope 裁剪（只保留 Task 需要的部分）
                String scopedContent = scopeSpec(specContent, taskNode.specScope());

                SubSpec spec = SubSpec.draft(taskNode.nodeId(), taskNode.name(), scopedContent);

                // 校验格式
                var validationResult = specValidator.validate(spec.content());
                if (!validationResult.valid()) {
                    String validationError = String.join("; ", validationResult.errors());
                    spec = spec.markInvalid(validationError);
                    log.warn("Spec 校验失败 [{}]: {}", taskNode.nodeId(), validationError);
                } else {
                    spec = spec.markValidated();
                    boundCount++;
                    log.info("Spec 绑定成功 [{}] → {} (scope: {})",
                            taskNode.nodeId(), taskNode.specRef(), taskNode.specScope());
                }

                subSpecs.add(spec);

            } else {
                // Task 没有绑定 Spec — 尝试自动匹配
                String taskDesc = taskNode.name() + " " + taskNode.description();
                List<String> matched = specManager.matchSpecs(taskDesc);
                if (!matched.isEmpty()) {
                    String bestMatch = matched.get(0);
                    String specContent = allSpecs.get(bestMatch);
                    SubSpec spec = SubSpec.draft(taskNode.nodeId(), taskNode.name(), specContent);
                    spec = spec.markValidated();
                    subSpecs.add(spec);
                    boundCount++;
                    log.info("Spec 自动匹配 [{}] → {}", taskNode.nodeId(), bestMatch);
                } else {
                    log.info("Task [{}] 无匹配 Spec，将按描述执行", taskNode.nodeId());
                }
            }
        }

        state.setSubSpecs(subSpecs);
        log.info("Spec 读取完成: {} 个 Task 中 {} 个绑定了 Spec", taskDAG.size(), boundCount);
        return state;
    }

    /**
     * 按 specScope 裁剪 Spec 内容
     * 
     * 如果 specScope 为 null，返回完整 Spec
     * 如果 specScope 指定了方法名，尝试提取该方法相关的部分
     */
    private String scopeSpec(String fullContent, String specScope) {
        if (specScope == null || specScope.isBlank()) {
            return fullContent;
        }

        // 简单策略：返回完整内容 + scope 标注
        // 生产环境可以做 YAML 路径提取
        return fullContent + "\n\n# === 本次执行范围: " + specScope + " ===";
    }
}
