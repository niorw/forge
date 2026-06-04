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
 * 安全审查节点 — 独立的安全门禁。
 *
 * 核心理念（来自 Anthropic Harness Engineering）：
 * "Security Reviewer 建议与 Code Reviewer 并行执行，各自独立给出结论。
 *  这样即使功能评审通过，安全问题仍然可以单独拦截。"
 *
 * 审查维度：
 * 1. SQL 注入 — 检查是否有未参数化的 SQL 拼接
 * 2. 越权访问 — 检查是否有权限校验缺失
 * 3. 敏感信息泄露 — 检查日志中是否打印了敏感字段
 * 4. 超时熔断 — 检查外部调用是否有超时和熔断保护
 * 5. 输入校验 — 检查接口入参是否有校验
 * 6. 依赖安全 — 检查是否有已知漏洞的依赖
 *
 * 输出：Verdict（PASS/FAIL）+ 安全检查报告
 *
 * 工具权限：只读（read/grep），无 write/edit/git（只审不改原则）
 */
@Component
public class SecurityReviewAction extends BaseAgentAction {

    private final LlmGateway llmGateway;

    /** 安全审查清单 */
    private static final String SECURITY_CHECKLIST = """
            ## 安全审查清单（逐项检查）
            
            ### 1. SQL 注入风险
            - [ ] 所有 SQL 是否使用参数化查询（PreparedStatement / MyBatis #{}）
            - [ ] 是否存在字符串拼接 SQL（${} 或 String.format 拼接）
            - [ ] 动态 ORDER BY / LIMIT 是否有白名单校验
            
            ### 2. 越权访问风险
            - [ ] 接口是否有权限校验（@RequiresPermissions / 自定义注解）
            - [ ] 是否有水平越权风险（用户可操作他人数据）
            - [ ] 是否有垂直越权风险（低权限用户访问高权限接口）
            
            ### 3. 敏感信息泄露
            - [ ] 日志中是否打印了身份证、银行卡、手机号等敏感字段
            - [ ] 返回值中是否暴露了内部 ID 或系统路径
            - [ ] 异常信息中是否包含了堆栈或 SQL 语句
            
            ### 4. 超时熔断保护
            - [ ] 外部服务调用是否设置了超时时间
            - [ ] 是否有熔断降级策略（Sentinel / Hystrix / Resilience4j）
            - [ ] 数据库连接池是否配置了最大等待时间
            
            ### 5. 输入校验
            - [ ] 接口入参是否有 @Valid / @NotNull / @Size 等校验注解
            - [ ] 金额/数量字段是否有范围校验
            - [ ] 字符串字段是否有长度限制
            
            ### 6. 并发安全
            - [ ] 是否有竞态条件（check-then-act）
            - [ ] 金额操作是否使用了乐观锁或分布式锁
            - [ ] 幂等性是否保证（重复请求不会重复扣款）
            """;

    public SecurityReviewAction(AgentMetrics metrics, StateEventBus eventBus, LlmGateway llmGateway) {
        super(metrics, eventBus);
        this.llmGateway = llmGateway;
    }

    @Override
    protected MainState doExecute(MainState state) {
        List<SubTask> subTasks = state.getSubTasks();
        List<SubSpec> subSpecs = state.getSubSpecs();

        if (subTasks == null || subTasks.isEmpty()) {
            // 没有子任务，安全审查直接通过
            state.setSecurityVerdict(Verdict.pass("无子任务需要安全审查", Verdict.ReviewDimension.SECURITY));
            return state;
        }

        List<Verdict> verdicts = new ArrayList<>();
        int reviewedCount = 0;

        for (SubTask subTask : subTasks) {
            if (!subTask.isCompleted()) {
                continue;
            }

            String code = subTask.result() != null ? subTask.result() : "";

            // 查找对应的 Spec
            String specContent = "";
            if (subSpecs != null) {
                for (SubSpec spec : subSpecs) {
                    if (spec.taskId().equals(subTask.taskId())) {
                        specContent = spec.content();
                        break;
                    }
                }
            }

            log.info("安全审查任务 [{}]...", subTask.name());

            String reviewPrompt = buildSecurityPrompt(subTask.name(), code, specContent);
            String llmResponse = llmCall("security-review",
                    () -> llmGateway.chatWithRole("security-reviewer", "security-review", reviewPrompt));

            Verdict verdict = Verdict.parseFromText(llmResponse, Verdict.ReviewDimension.SECURITY);
            verdicts.add(verdict);
            reviewedCount++;

            if (verdict.isFail()) {
                log.warn("安全审查发现问题 [{}]: {} (CRITICAL/HIGH: {})",
                        subTask.name(), verdict.summary(), verdict.criticalFindingsCount());
            }
        }

        // 合并所有子任务的安全审查结果
        Verdict merged = mergeVerdicts(verdicts);
        state.setSecurityVerdict(merged);

        if (merged.isFail()) {
            log.warn("安全审查未通过: {} (共 {} 个问题)", merged.summary(), merged.findings().size());
        } else {
            log.info("安全审查通过，共审查 {} 个任务", reviewedCount);
        }

        return state;
    }

    /**
     * 构建安全审查的 Prompt
     */
    private String buildSecurityPrompt(String taskName, String code, String specContent) {
        return String.format("""
                ## 安全审查任务
                
                ### 任务名称
                %s
                
                ### 待审查代码
                %s
                
                ### 对应 Spec
                %s
                
                %s
                
                ## 输出要求
                
                严格按以下格式输出：
                1. 先逐项检查上面的审查清单
                2. 最后一行必须是：VERDICT: PASS 或 VERDICT: FAIL
                3. 如果 FAIL，每个问题必须包含：
                   - 位置（文件:行号）
                   - 实际行为（代码做了什么）
                   - 预期行为（应该怎么做）
                   - 根因分析（为什么会这样）
                4. 用 critical/high/medium/low 标注严重程度
                
                注意：
                - 对每个函数都假设它有安全问题，仔细检查
                - 宁可误报也不要漏报
                """,
                taskName,
                code.isEmpty() ? "（无代码）" : code,
                specContent.isEmpty() ? "（无 Spec）" : specContent,
                SECURITY_CHECKLIST);
    }

    /**
     * 合并多个子任务的审查结果
     * 任一 FAIL 则整体 FAIL，findings 合并
     */
    private Verdict mergeVerdicts(List<Verdict> verdicts) {
        if (verdicts.isEmpty()) {
            return Verdict.pass("无审查结果", Verdict.ReviewDimension.SECURITY);
        }

        boolean anyFail = verdicts.stream().anyMatch(Verdict::isFail);
        List<Verdict.Finding> allFindings = verdicts.stream()
                .flatMap(v -> v.findings().stream())
                .toList();

        String summary = String.format("安全审查完成：%d 个子任务，%d 个通过，%d 个未通过",
                verdicts.size(),
                verdicts.stream().filter(Verdict::isPass).count(),
                verdicts.stream().filter(Verdict::isFail).count());

        if (anyFail) {
            return Verdict.fail(summary, allFindings, Verdict.ReviewDimension.SECURITY);
        }

        return Verdict.pass(summary, Verdict.ReviewDimension.SECURITY);
    }

    @Override
    public String name() {
        return "securityReview";
    }

    @Override
    public String phase() {
        return "SECURITY_REVIEW";
    }
}
