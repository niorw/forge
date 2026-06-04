package com.forge.agent.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 子Agent状态
 * 
 * 每个子任务执行时的状态管理
 * 包含子任务ID、Spec、执行结果、错误信息等
 */
public class ChildState extends HashMap<String, Object> {

    private static final String TASK_ID = "taskId";
    private static final String TASK_NAME = "taskName";
    private static final String SPEC = "spec";
    private static final String RESULT = "result";
    private static final String STATUS = "status";
    private static final String ERROR = "error";
    private static final String DEPENDENCIES = "dependencies";
    private static final String START_TIME = "startTime";
    private static final String END_TIME = "endTime";

    /**
     * 构造函数
     */
    public ChildState() {
        super();
        this.put(STATUS, "PENDING");
        this.put(DEPENDENCIES, new ArrayList<String>());
    }

    /**
     * 带任务ID的构造函数
     */
    public ChildState(String taskId, String taskName) {
        this();
        this.put(TASK_ID, taskId);
        this.put(TASK_NAME, taskName);
    }

    // ==================== 任务标识 ====================

    /** 获取子任务ID */
    public String getTaskId() {
        return (String) this.get(TASK_ID);
    }

    /** 设置子任务ID */
    public void setTaskId(String taskId) {
        this.put(TASK_ID, taskId);
    }

    /** 获取子任务名称 */
    public String getTaskName() {
        return (String) this.get(TASK_NAME);
    }

    /** 设置子任务名称 */
    public void setTaskName(String taskName) {
        this.put(TASK_NAME, taskName);
    }

    // ==================== Spec ====================

    /** 获取子任务的Spec定义 */
    public String getSpec() {
        return (String) this.get(SPEC);
    }

    /** 设置子任务的Spec定义 */
    public void setSpec(String spec) {
        this.put(SPEC, spec);
    }

    // ==================== 执行结果 ====================

    /** 获取执行结果 */
    public String getResult() {
        return (String) this.get(RESULT);
    }

    /** 设置执行结果 */
    public void setResult(String result) {
        this.put(RESULT, result);
    }

    // ==================== 状态 ====================

    /**
     * 获取任务状态
     * PENDING - 等待执行
     * RUNNING - 执行中
     * SUCCESS - 执行成功
     * FAILED - 执行失败
     */
    public String getStatus() {
        return (String) this.get(STATUS);
    }

    /** 设置任务状态 */
    public void setStatus(String status) {
        this.put(STATUS, status);
    }

    // ==================== 错误信息 ====================

    /** 获取错误信息 */
    public String getError() {
        return (String) this.get(ERROR);
    }

    /** 设置错误信息 */
    public void setError(String error) {
        this.put(ERROR, error);
    }

    // ==================== 依赖关系 ====================

    /** 获取依赖的任务ID列表 */
    @SuppressWarnings("unchecked")
    public List<String> getDependencies() {
        return (List<String>) this.get(DEPENDENCIES);
    }

    /** 设置依赖的任务ID列表 */
    public void setDependencies(List<String> dependencies) {
        this.put(DEPENDENCIES, dependencies);
    }

    // ==================== 时间 ====================

    /** 获取开始时间 */
    public Long getStartTime() {
        return (Long) this.get(START_TIME);
    }

    /** 设置开始时间 */
    public void setStartTime(Long startTime) {
        this.put(START_TIME, startTime);
    }

    /** 获取结束时间 */
    public Long getEndTime() {
        return (Long) this.get(END_TIME);
    }

    /** 设置结束时间 */
    public void setEndTime(Long endTime) {
        this.put(END_TIME, endTime);
    }

    /** 获取执行耗时（毫秒） */
    public Long getDurationMs() {
        Long start = getStartTime();
        Long end = getEndTime();
        if (start != null && end != null) {
            return end - start;
        }
        return null;
    }
}
