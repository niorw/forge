package com.forge.agent.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.agent.gateway.RedisTaskDispatcher;
import com.forge.agent.llm.LlmGateway;
import com.forge.agent.metrics.AgentMetrics;
import com.forge.agent.tool.ToolResult;
import com.forge.agent.worker.WorkerExecutionPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis Stream 任务消费者（Worker）
 * 
 * 改进：
 * 1. 使用 LlmGateway 接口替代 LlmClientService
 * 2. 使用 RedisTaskDispatcher 替代旧 RedisTaskProducer（获取 stream name）
 */
@Component
public class RedisTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(RedisTaskConsumer.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentMetrics agentMetrics;
    private final RedisTaskDispatcher taskDispatcher;
    private final LlmGateway llmGateway;
    private final WorkerExecutionPipeline pipeline;

    @Value("${agent.stream.consumer-group:agent-workers}")
    private String consumerGroup;

    @Value("${agent.stream.consumer-name:worker-1}")
    private String consumerName;

    @Value("${agent.worker.project-path:/tmp/gmf-workspace}")
    private String defaultProjectPath;

    public RedisTaskConsumer(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                             AgentMetrics agentMetrics, RedisTaskDispatcher taskDispatcher,
                             LlmGateway llmGateway, WorkerExecutionPipeline pipeline) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.agentMetrics = agentMetrics;
        this.taskDispatcher = taskDispatcher;
        this.llmGateway = llmGateway;
        this.pipeline = pipeline;
    }

    @PostConstruct
    public void init() {
        taskDispatcher.ensureConsumerGroup(consumerGroup);
        log.info("RedisTaskConsumer 初始化完成: group={}, consumer={}", consumerGroup, consumerName);
    }

    public boolean consumeOne() {
        try {
            var records = redisTemplate.opsForStream().read(
                    Consumer.from(consumerGroup, consumerName),
                    StreamReadOptions.empty().count(1),
                    StreamOffset.create(taskDispatcher.getStreamName(), ReadOffset.lastConsumed())
            );

            if (records == null || records.isEmpty()) return false;

            for (var record : records) {
                processRecord(record);
            }
            return true;

        } catch (Exception e) {
            log.error("消费消息失败", e);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private void processRecord(MapRecord<String, Object, Object> record) {
        String recordId = record.getId().getValue();
        // Redis Stream 的 hash 值都是 String，但泛型声明为 Object
        Map<String, String> body = (Map<String, String>) (Map<?, ?>) record.getValue();
        String taskId = body.get("taskId");
        String messageBody = body.get("messageBody");

        log.info("开始处理任务消息: taskId={}, recordId={}", taskId, recordId);

        try {
            TaskMessage message = objectMapper.readValue(messageBody, TaskMessage.class);

            // 使用 WorkerExecutionPipeline 执行完整管线（代码生成 → 编译 → 测试）
            var result = pipeline.execute(message, defaultProjectPath);

            if (result.success()) {
                // 将文件 Diff 信息附加到任务结果
                String output = result.output();
                if (result.fileDiff() != null && !result.fileDiff().isBlank()) {
                    output = output + "\n\n" + result.fileDiff();
                }
                writeTaskResult(taskId, "SUCCESS", output, null);
                log.info("任务执行成功: taskId={}", taskId);
            } else {
                String output = result.fileDiff() != null ? result.fileDiff() : null;
                writeTaskResult(taskId, "FAILED", output, result.error());
                log.warn("任务执行失败: taskId={}, error={}", taskId, result.error());
            }

            redisTemplate.opsForStream().acknowledge(
                    taskDispatcher.getStreamName(), consumerGroup, recordId);

        } catch (Exception e) {
            log.error("任务执行失败: taskId={}", taskId, e);
            writeTaskResult(taskId, "FAILED", null, e.getMessage());
            redisTemplate.opsForStream().acknowledge(
                    taskDispatcher.getStreamName(), consumerGroup, recordId);
        }
    }

    private void writeTaskResult(String taskId, String status, String result, String error) {
        String key = "agent:task:result:" + taskId;
        Map<String, String> resultMap = new HashMap<>();
        resultMap.put("status", status);
        resultMap.put("timestamp", String.valueOf(System.currentTimeMillis()));
        if (result != null) resultMap.put("result", result);
        if (error != null) resultMap.put("error", error);
        redisTemplate.opsForHash().putAll(key, resultMap);
    }
}
