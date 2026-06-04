package com.forge.agent.gateway;

import com.forge.agent.mq.TaskMessage;

/**
 * 任务派发器抽象接口
 * 
 * 解耦 DispatchAction 与具体消息中间件
 * 
 * 实现方式：
 * - RedisTaskDispatcher: Redis Stream（当前实现）
 * - RabbitMqTaskDispatcher: RabbitMQ
 * - KafkaTaskDispatcher: Kafka
 * - DirectTaskDispatcher: 本地直接执行（测试用）
 */
public interface TaskDispatcher {

    /**
     * 派发子任务到 worker
     * 
     * @param message 任务消息
     * @throws RuntimeException 派发失败时抛出
     */
    void dispatch(TaskMessage message);
}
