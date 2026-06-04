package com.forge.agent.engine.actions;

import com.forge.agent.action.BaseAgentAction;
import com.forge.agent.event.StateEventBus;
import com.forge.agent.llm.LlmGateway;
import com.forge.agent.metrics.AgentMetrics;
import com.forge.agent.review.Verdict;
import com.forge.agent.state.MainState;
import com.forge.agent.state.SubSpec;
import com.forge.agent.state.SubTask;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码审查节点 — 独立的 Code Reviewer Agent。
 *
 * 核心理念（来自 Anthropic Harness Engineering）：
 * "将做工作的智能体与评判工作的智能体分离，是一个强有力的杠杆——
 *  因为让评估者变得更加怀疑，远比让生成者变得更加自我批判要容易得多。"
 *
 * 重构要点：
 * 1. 使用 Verdict 协议输出结构化 PASS/FAIL
 * 2. 通过 LlmModelRouter 路由到独立的 reviewer 模型（不同于 worker）
 * 3. 工具权限隔离：reviewer 角色没有 write/edit/git 权限（只审不改）
 * 4. 审查维度：接口一致性、边界处理、架构合理性、测试有效性
 *
 * 输入：state.subTasks（已完成的子任务） + state.subSpecs（对应的 Spec）
 * 输出：Verdict 写入 state.codeVerdict
 */
@Component
public class ReviewAction extends BaseAgentAction {

    private final LlmGateway llmGateway;

    /** 审查 Prompt 模板（critical and skeptical 语气） */
    private static final String REVIEW_PROMPT_TEMPLATE = """
            ## 代码审查任务
            
            ### 审查态度
            你是一个极度严格和怀疑的代码审查员。对每个函数都假设它有问题，直到证明它没问题。
            不要被表面的代码整洁所迷惑，深入检查逻辑正确性。
            
            ### 待审查代码
            %s
            
            ### 对应 Spec
            %s
            
            ### 验收标准
            %s
            
            ### 文件变更（Diff）
            %s
            
            ### 审查维度（逐项检查）
            
            1. **接口一致性**：方法签名、参数类型、返回值是否与 Spec 完全一致？
            2. **错误码完整性**：Spec 中定义的错误码是否都有处理？是否有未定义的错误码？
            3. **边界条件**：null 入参、空集合、负数、超大值、并发场景是否处理？
            4. **类型安全**：是否有强制类型转换、泛型擦除、精度丢失风险？
            5. **测试有效性**：测试用例是否覆盖了核心路径和异常路径？
            6. **架构合理性**：是否遵循项目现有的分层和命名规范？
            
            ## 输出要求
            
            严格按以下格式输出：
            1. 先逐项检查上面的审查维度
            2. 最后一行必须是：VERDICT: PASS 或 VERDICT: FAIL
            3. 如果 FAIL，每个问题必须包含"三要素"：
               - 位置（文件:行号）
               - 实际行为（代码做了什么）
               - 预期行为（应该怎么做）
               - 根因分析（为什么会这样）
            4. 用 critical/high/medium/low 标注严重程度
            
            关键原则：
            - 宁可严格也不要宽松，遗漏问题的代价远大于误报
            - 如果代码质量勉强及格，也应该 FAIL 并说明改进方向
            """;

    public ReviewAction(AgentMetrics metrics, StateEventBus eventBus, LlmGateway llmGateway) {
        super(metrics, eventBus);
        this.llmGateway = llmGateway;
    }

    @Override
    protected MainState doExecute(MainState state) {
        List<SubTask> subTasks = state.getSubTasks();
        List<SubSpec> subSpecs = state.getSubSpecs();

        if (subTasks == null || subTasks.isEmpty()) {
            state.setCodeVerdict(Verdict.pass("无子任务需要审查", Verdict.ReviewDimension.CODE_QUALITY));
            return state;
        }

        List<Verdict> verdicts = new ArrayList<>();
        int reviewedCount = 0;

        for (SubTask subTask : subTasks) {
            if (!subTask.isCompleted()) {
                log.info("跳过未完成的任务 [{}]: status={}", subTask.name(), subTask.status());
                continue;
            }

            // 查找对应的 Spec
            String specContent = "";
            String acceptanceCriteria = "";
            if (subSpecs != null) {
                for (SubSpec spec : subSpecs) {
                    if (spec.taskId().equals(subTask.taskId())) {
                        specContent = spec.content();
                        break;
                    }
                }
            }

            // 获取文件变更信息
            String diffInfo = subTask.fileDiff() != null ? subTask.fileDiff() : "（无 Diff 信息）";

            log.info("代码审查任务 [{}]...", subTask.name());

            // 构建审查 Prompt
            String code = subTask.result() != null ? subTask.result() : "";
            String reviewPrompt = String.format(REVIEW_PROMPT_TEMPLATE,
                    code.isEmpty() ? "（无代码）" : code,
                    specContent.isEmpty() ? "（无 Spec）" : specContent,
                    acceptanceCriteria.isEmpty() ? "（无验收标准）" : acceptanceCriteria,
                    diffInfo);

            // 调用 reviewer 角色的 LLM（独立模型，与 worker 不同）
            String llmResponse = llmCall("code-review",
                    () -> llmGateway.chatWithRole("reviewer", "code-review", reviewPrompt));

            // 解析 Verdict
            Verdict verdict = Verdict.parseFromText(llmResponse, Verdict.ReviewDimension.CODE_QUALITY);
            verdicts.add(verdict);
            reviewedCount++;

            if (verdict.isFail()) {
                log.warn("代码审查发现问题 [{}]: {} (CRITICAL/HIGH: {})",
                        subTask.name(), verdict.summary(), verdict.criticalFindingsCount());
            }
        }

        // 合并所有子任务的审查结果
        Verdict merged = mergeVerdicts(verdicts);
        state.setCodeVerdict(merged);

        // 同时更新 finalResult（向后兼容）
        state.setFinalResult(merged.toReport());

        if (merged.isFail()) {
            log.warn("代码审查未通过: {} (共 {} 个问题)", merged.summary(), merged.findings().size());
        } else {
            log.info("代码审查通过，共审查 {} 个任务", reviewedCount);
        }

        return state;
    }

    /**
     * 合并多个子任务的审查结果
     * 任一 FAIL 则整体 FAIL，findings 合并
     */
    private Verdict mergeVerdicts(List<Verdict> verdicts) {
        if (verdicts.isEmpty()) {
            return Verdict.pass("无审查结果", Verdict.ReviewDimension.CODE_QUALITY);
        }

        boolean anyFail = verdicts.stream().anyMatch(Verdict::isFail);
        List<Verdict.Finding> allFindings = verdicts.stream()
                .flatMap(v -> v.findings().stream())
                .toList();

        String summary = String.format("代码审查完成：%d 个子任务，%d 个通过，%d 个未通过",
                verdicts.size(),
                verdicts.stream().filter(Verdict::isPass).count(),
                verdicts.stream().filter(Verdict::isFail).count());

        if (anyFail) {
            return Verdict.fail(summary, allFindings, Verdict.ReviewDimension.CODE_QUALITY);
        }

        return Verdict.pass(summary, Verdict.ReviewDimension.CODE_QUALITY);
    }

    @Override
    public String name() {
        return "review";
    }

    @Override
    public String phase() {
        return "REVIEW";
    }
}
