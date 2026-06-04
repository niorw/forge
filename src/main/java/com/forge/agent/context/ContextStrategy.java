package com.forge.agent.context;

import com.forge.agent.state.MainState;

/**
 * 上下文组装策略接口
 *
 * 每种 LLM 角色（planner/architect/reviewer/worker）对应一个策略实现，
 * 由 ContextBuilder 按角色分发调用。Spring 自动收集所有 @Component 实现。
 */
public interface ContextStrategy {

    /**
     * 策略对应的角色名（与 LlmGateway 的 role 参数一致）
     */
    String getRole();

    /**
     * 构建 system prompt（角色人设 + 行为指令）
     *
     * @param action 当前动作名（如 planDAG, reviewCode 等）
     * @return system prompt 文本
     */
    String buildSystemPrompt(String action);

    /**
     * 根据当前状态构建 user prompt（只包含该角色需要的上下文）
     *
     * @param state  主状态
     * @param action 当前动作名
     * @return user prompt 文本
     */
    String buildUserPrompt(MainState state, String action);

    /**
     * 该角色的最大上下文 token 数（按 1 中文字 ≈ 2 token 估算）
     */
    int getMaxTokens();
}

// ==================== 4 个策略实现 ====================

/**
 * Planner 策略：只看需求 + 分析 + 驳回时的前次 DAG
 */
@org.springframework.stereotype.Component
class PlannerContextStrategy implements ContextStrategy {

    @Override
    public String getRole() { return "planner"; }

    @Override
    public String buildSystemPrompt(String action) {
        return """
                你是一个资深的需求分析与任务规划专家。
                你的职责是：
                1. 理解用户需求，输出结构化的分析结果
                2. 将需求拆解为可执行的 DAG 任务图
                3. 为每个任务节点指定依赖关系、Spec 引用和验收标准
                
                输出格式要求：
                - 分析阶段：输出 JSON 格式的需求分析
                - 规划阶段：输出 JSON 数组，每个元素是 TaskNode 的序列化格式
                
                注意：只输出 JSON，不要包含额外解释文字。""";
    }

    @Override
    public String buildUserPrompt(MainState state, String action) {
        StringBuilder sb = new StringBuilder();

        // 始终包含原始需求
        ContextHelpers.appendIfNotNull(sb, "## 原始需求", state.getRequirement());

        // 包含分析结果
        ContextHelpers.appendIfNotNull(sb, "## 需求分析", state.getAnalysis());

        // 如果是驳回后重新规划，附上前次 DAG 供参考
        if ("planDAG".equals(action) && "REJECTED".equals(state.getApprovalStatus())) {
            ContextHelpers.appendIfNotNull(sb, "## 前次规划（已被驳回，请参考并修正）", state.getTaskDAG() != null ? state.getTaskDAG().toString() : null);
            ContextHelpers.appendIfNotNull(sb, "## 驳回原因", state.getError());
        }

        return sb.toString();
    }

    @Override
    public int getMaxTokens() { return 4000; }
}

/**
 * Architect 策略：看需求 + 分析 + 已有 Spec
 */
@org.springframework.stereotype.Component
class ArchitectContextStrategy implements ContextStrategy {

    @Override
    public String getRole() { return "architect"; }

    @Override
    public String buildSystemPrompt(String action) {
        return """
                你是一个系统架构师，擅长微服务拆分和技术方案设计。
                你的职责是：
                1. 根据需求分析设计系统架构（服务拆分、依赖图、技术选型）
                2. 为每个子模块生成详细的 Spec 规格说明
                
                输出格式：
                - 架构设计：Markdown 格式，包含服务拆分图、接口定义、数据模型
                - Spec 生成：YAML 格式，遵循 OpenAPI 规范""";
    }

    @Override
    public String buildUserPrompt(MainState state, String action) {
        StringBuilder sb = new StringBuilder();

        ContextHelpers.appendIfNotNull(sb, "## 原始需求", state.getRequirement());
        ContextHelpers.appendIfNotNull(sb, "## 需求分析", state.getAnalysis());

        // 附上已有 Spec（供增量生成参考）
        if (state.getSubSpecs() != null && !state.getSubSpecs().isEmpty()) {
            sb.append("\n## 已有 Spec 列表\n");
            state.getSubSpecs().forEach(spec ->
                sb.append("- ").append(spec.name()).append(" (v").append(spec.version()).append(", ").append(spec.status()).append(")\n")
            );
        }

        return sb.toString();
    }

    @Override
    public int getMaxTokens() { return 4000; }
}

/**
 * Reviewer 策略：看需求 + SubSpec + 代码 + 验收标准
 */
@org.springframework.stereotype.Component
class ReviewerContextStrategy implements ContextStrategy {

    @Override
    public String getRole() { return "reviewer"; }

    @Override
    public String buildSystemPrompt(String action) {
        return """
                你是一个严格的代码审查专家。
                你的职责是：
                1. 对照 Spec 和验收标准审查代码质量
                2. 检查代码是否满足功能需求和非功能需求
                3. 输出结构化的审查报告
                
                审查维度：
                - 功能正确性：是否满足 Spec 中定义的行为
                - 代码质量：命名、结构、可读性
                - 安全性：注入、越权、敏感信息泄露
                - 性能：时间/空间复杂度、资源泄露
                
                输出格式：JSON { "passed": boolean, "issues": [...], "summary": "..." }""";
    }

    @Override
    public String buildUserPrompt(MainState state, String action) {
        StringBuilder sb = new StringBuilder();

        ContextHelpers.appendIfNotNull(sb, "## 原始需求（概要）", ContextHelpers.truncate(state.getRequirement(), 500));
        ContextHelpers.appendIfNotNull(sb, "## 当前任务节点", state.getCurrentNode());

        // 附上当前节点对应的 SubSpec
        if (state.getSubSpecs() != null && state.getCurrentNode() != null) {
            state.getSubSpecs().stream()
                .filter(s -> s.taskId().equals(state.getCurrentNode()))
                .findFirst()
                .ifPresent(spec -> {
                    ContextHelpers.appendIfNotNull(sb, "## Spec 规格", spec.content());
                });
        }

        return sb.toString();
    }

    @Override
    public int getMaxTokens() { return 4000; }
}

/**
 * Worker 策略：看 SubSpec + 依赖产物 + 任务描述
 */
@org.springframework.stereotype.Component
class WorkerContextStrategy implements ContextStrategy {

    @Override
    public String getRole() { return "worker"; }

    @Override
    public String buildSystemPrompt(String action) {
        return """
                你是一个高效的代码实现工程师。
                你的职责是：
                1. 严格按照 Spec 规格实现代码
                2. 遵循项目既有的代码风格和架构约束
                3. 输出可直接编译运行的代码
                
                注意事项：
                - 不要超出 Spec 定义的范围
                - 优先使用项目已有的依赖和工具类
                - 代码中添加必要的中文注释""";
    }

    @Override
    public String buildUserPrompt(MainState state, String action) {
        StringBuilder sb = new StringBuilder();

        // Worker 主要看当前节点对应的 SubSpec
        if (state.getSubSpecs() != null && state.getCurrentNode() != null) {
            state.getSubSpecs().stream()
                .filter(s -> s.taskId().equals(state.getCurrentNode()))
                .findFirst()
                .ifPresent(spec -> {
                    ContextHelpers.appendIfNotNull(sb, "## Spec 规格", spec.content());
                });
        }

        // 附上当前节点的任务描述
        if (state.getTaskDAG() != null && state.getCurrentNode() != null) {
            state.getTaskDAG().stream()
                .filter(t -> t.nodeId().equals(state.getCurrentNode()))
                .findFirst()
                .ifPresent(task -> {
                    ContextHelpers.appendIfNotNull(sb, "## 任务描述", task.description());
                    if (task.hasDependencies()) {
                        sb.append("\n## 依赖任务\n本任务依赖以下已完成的任务：\n");
                        task.dependencies().forEach(dep -> sb.append("- ").append(dep).append("\n"));
                    }
                });
        }

        return sb.toString();
    }

    @Override
    public int getMaxTokens() { return 4000; }
}

// ==================== 辅助方法（包内可见） ====================

/** 拼接非空内容块 */
class ContextHelpers {
    static void appendIfNotNull(StringBuilder sb, String header, String content) {
        if (content != null && !content.isBlank()) {
            sb.append(header).append("\n").append(content).append("\n\n");
        }
    }

    /** 截断过长文本 */
    static String truncate(String text, int maxChars) {
        if (text == null) return null;
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "\n...[截断]";
    }
}
