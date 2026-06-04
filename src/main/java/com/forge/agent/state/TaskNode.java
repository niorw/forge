package com.forge.agent.state;

import java.util.List;

/**
 * DAG 节点记录
 * 
 * 每个节点代表一个子任务，包含：
 * - 基本信息（ID、名称、描述）
 * - 依赖关系（用于拓扑排序）
 * - Spec 引用（执行时读取哪个 Spec 的哪个部分）
 * - 验收标准（Reviewer 审查依据）
 */
public record TaskNode(
    /** 节点ID（唯一标识） */
    String nodeId,
    /** 节点名称 */
    String name,
    /** 节点描述 */
    String description,
    /** 依赖的节点ID列表（前置任务） */
    List<String> dependencies,
    /** 节点类型：ANALYZE/PLAN/EXECUTE/REVIEW */
    String nodeType,
    /** 是否需要人工审批 */
    boolean requiresApproval,
    /** 优先级：1-10，数字越大优先级越高 */
    int priority,
    /** 估算执行时间（秒） */
    int estimatedDurationSeconds,
    // ===== Spec 引用（SDD 核心） =====
    /** 引用的 Spec 文件名（如 "order-service-spec.yaml"），null 表示无 Spec */
    String specRef,
    /** Spec 内的作用域（如 "methods.refundOrder"、"AC-1,AC-2"），null 表示整个 Spec */
    String specScope,
    /** 验收标准 ID 列表（Reviewer 对照审查） */
    List<String> acceptanceCriteria
) {
    /**
     * 创建执行节点（无 Spec 引用）
     */
    public static TaskNode execute(String nodeId, String name, String description, List<String> dependencies) {
        return new TaskNode(nodeId, name, description, dependencies, "EXECUTE", false, 5, 60,
                null, null, List.of());
    }

    /**
     * 创建需要审批的节点（无 Spec 引用）
     */
    public static TaskNode withApproval(String nodeId, String name, String description, List<String> dependencies) {
        return new TaskNode(nodeId, name, description, dependencies, "EXECUTE", true, 5, 60,
                null, null, List.of());
    }

    /**
     * 创建绑定了 Spec 的执行节点
     * 
     * @param specRef Spec 文件名
     * @param specScope Spec 内作用域（方法名/User Story ID）
     * @param acceptanceCriteria 验收标准 ID 列表
     */
    public static TaskNode withSpec(String nodeId, String name, String description,
                                     List<String> dependencies, String specRef,
                                     String specScope, List<String> acceptanceCriteria) {
        return new TaskNode(nodeId, name, description, dependencies, "EXECUTE", false, 5, 60,
                specRef, specScope, acceptanceCriteria != null ? acceptanceCriteria : List.of());
    }

    /**
     * 创建绑定了 Spec 的需要审批的节点
     */
    public static TaskNode withSpecAndApproval(String nodeId, String name, String description,
                                                List<String> dependencies, String specRef,
                                                String specScope, List<String> acceptanceCriteria) {
        return new TaskNode(nodeId, name, description, dependencies, "EXECUTE", true, 5, 60,
                specRef, specScope, acceptanceCriteria != null ? acceptanceCriteria : List.of());
    }

    /**
     * 判断是否绑定了 Spec
     */
    public boolean hasSpec() {
        return specRef != null && !specRef.isBlank();
    }

    /**
     * 判断是否有依赖
     */
    public boolean hasDependencies() {
        return dependencies != null && !dependencies.isEmpty();
    }

    /**
     * 判断是否为根节点（无依赖）
     */
    public boolean isRoot() {
        return !hasDependencies();
    }
}
