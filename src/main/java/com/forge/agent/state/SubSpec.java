package com.forge.agent.state;

/**
 * 子Spec记录
 * 
 * 使用 Java Record 定义子任务的 Spec 规格说明
 * 每个子任务都有一个对应的 Spec，描述该任务的详细执行规格
 */
public record SubSpec(
    /** 任务ID，与 SubTask 对应 */
    String taskId,
    /** Spec 名称 */
    String name,
    /** Spec 内容（YAML 或 Markdown 格式） */
    String content,
    /** Spec 版本号 */
    int version,
    /** Spec 状态：DRAFT/VALIDATED/INVALID */
    String status,
    /** 校验错误信息 */
    String validationError
) {
    /**
     * 创建草稿状态的 Spec
     */
    public static SubSpec draft(String taskId, String name, String content) {
        return new SubSpec(taskId, name, content, 1, "DRAFT", null);
    }

    /**
     * 标记为校验通过
     */
    public SubSpec markValidated() {
        return new SubSpec(taskId, name, content, version, "VALIDATED", null);
    }

    /**
     * 标记为校验失败
     */
    public SubSpec markInvalid(String validationError) {
        return new SubSpec(taskId, name, content, version, "INVALID", validationError);
    }

    /**
     * 更新内容并递增版本号
     */
    public SubSpec updateContent(String newContent) {
        return new SubSpec(taskId, name, newContent, version + 1, "DRAFT", null);
    }

    /**
     * 判断 Spec 是否有效
     */
    public boolean isValid() {
        return "VALIDATED".equals(status);
    }
}
