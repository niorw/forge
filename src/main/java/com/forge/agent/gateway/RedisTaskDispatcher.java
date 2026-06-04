package com.forge.agent.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.agent.mq.TaskMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Redis Stream 实现的任务派发器
 * 
 * 实现 TaskDispatcher 接口，将旧 RedisTaskProducer 的发送逻辑
 * 和旧 DispatchAction 中的 Redis 调用统一收归此处。
 * 
 * 如需切换到 RabbitMQ/Kafka，只需新建实现类，注入即可。
 */
@Component
public class RedisTaskDispatcher implements TaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RedisTaskDispatcher.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${agent.stream.dispatch-name:agent:task:dispatch}")
    private String streamName;

    public RedisTaskDispatcher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void dispatch(TaskMessage message) {
        try {
            String jsonBody = objectMapper.writeValueAsString(message);

            Map<String, String> body = new HashMap<>();
            body.put("taskId", message.taskId());
            body.put("name", message.name());
            body.put("messageBody", jsonBody);
            body.put("timestamp", String.valueOf(message.timestamp()));

            RecordId recordId = redisTemplate.opsForStream()
                    .add(StreamRecords.newRecord()
                            .ofMap(body)
                            .withStreamKey(streamName));

            log.info("任务消息已发送到 Stream [{}]: taskId={}, recordId={}",
                    streamName, message.taskId(), recordId);

        } catch (JsonProcessingException e) {
            throw new RuntimeException("任务消息序列化失败: " + message.taskId(), e);
        } catch (Exception e) {
            throw new RuntimeException("任务消息发送失败: " + message.taskId(), e);
        }
    }

    /**
     * 确保 Consumer Group 存在
     */
    public void ensureConsumerGroup(String groupName) {
        try {
            redisTemplate.opsForStream().createGroup(streamName, groupName);
            log.info("Consumer Group 创建成功: {}", groupName);
        } catch (Exception e) {
            log.debug("Consumer Group 已存在或创建失败: {}", groupName);
        }
    }

    public String getStreamName() {
        return streamName;
    }
}
