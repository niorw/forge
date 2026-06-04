package com.forge.agent.state;

import java.util.List;

/**
 * 子任务记录
 * 
 * 使用 Java Record 定义子任务的基本信息
 * 包含任务ID、名称、描述、状态、依赖关系、执行结果等
 *
 * 新增 fileDiff 字段：记录本次任务执行产生的文件变更（来自 FileSnapshot.Diff）
 */
public record SubTask(
    /** 任务唯一标识 */
    String taskId,
    /** 任务名称 */
    String name,
    /** 任务描述 */
    String description,
    /** 任务状态：PENDING/RUNNING/SUCCESS/FAILED */
    String status,
    /** 依赖的任务ID列表 */
    List<String> dependencies,
    /** 任务执行结果 */
    String result,
    /** 错误信息 */
    String error,
    /** 文件变更 Diff 报告（来自 FileSnapshot） */
    String fileDiff
) {
    /**
     * 创建新的子任务（默认状态为PENDING）
     */
    public static SubTask create(String taskId, String name, String description, List<String> dependencies) {
        return new SubTask(taskId, name, description, "PENDING", dependencies, null, null, null);
    }

    /**
     * 标记任务为运行中
     */
    public SubTask markRunning() {
        return new SubTask(taskId, name, description, "RUNNING", dependencies, result, error, fileDiff);
    }

    /**
     * 标记任务为成功
     */
    public SubTask markSuccess(String result) {
        return new SubTask(taskId, name, description, "SUCCESS", dependencies, result, null, fileDiff);
    }

    /**
     * 标记任务为成功（带文件 Diff）
     */
    public SubTask markSuccess(String result, String fileDiff) {
        return new SubTask(taskId, name, description, "SUCCESS", dependencies, result, null, fileDiff);
    }

    /**
     * 标记任务为失败
     */
    public SubTask markFailed(String error) {
        return new SubTask(taskId, name, description, "FAILED", dependencies, null, error, fileDiff);
    }

    /**
     * 设置文件 Diff 信息
     */
    public SubTask withFileDiff(String fileDiff) {
        return new SubTask(taskId, name, description, status, dependencies, result, error, fileDiff);
    }

    /**
     * 判断任务是否完成（成功或失败）
     */
    public boolean isCompleted() {
        return "SUCCESS".equals(status) || "FAILED".equals(status);
    }
}
