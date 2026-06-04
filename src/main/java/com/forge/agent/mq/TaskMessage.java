package com.forge.agent.mq;

import java.util.List;

/**
 * Redis Stream 任务消息体
 * 
 * 使用 Java Record 定义消息格式
 * DispatchAction 将此消息发送到 Redis Stream（agent:task:dispatch）
 * RedisTaskConsumer 消费此消息执行子Agent任务
 */
public record TaskMessage(
    /** 子任务ID */
    String taskId,
    /** 子任务名称 */
    String name,
    /** 子任务描述 */
    String description,
    /** 子任务的 Spec 内容 */
    String specContent,
    /** 依赖的任务ID列表 */
    List<String> dependencies,
    /** 消息创建时间戳 */
    long timestamp
) {
    /**
     * 生成消息的唯一Key（用于Redis Stream ID）
     */
    public String messageKey() {
        return taskId + ":" + timestamp;
    }

    /**
     * 判断是否有依赖
     */
    public boolean hasDependencies() {
        return dependencies != null && !dependencies.isEmpty();
    }
}
