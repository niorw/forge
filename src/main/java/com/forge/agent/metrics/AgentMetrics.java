package com.forge.agent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Agent 监控拦截器
 * 
 * 提供 Counter（计数器）和 Timer（计时器）两种指标
 * 所有 LLM 调用必须通过 llmCall() 方法包装，自动采集：
 * - 调用次数（counter）
 * - 成功/失败次数
 * - 调用耗时（timer）
 */
@Component
public class AgentMetrics {

    private final MeterRegistry meterRegistry;
    private final Counter llmCallCounter;
    private final Counter llmCallSuccessCounter;
    private final Counter llmCallErrorCounter;
    private final Timer llmCallTimer;
    private final Counter dagNodeCounter;
    private final Counter taskDispatchCounter;
    private final Counter taskCompleteCounter;
    private final Counter retryCounter;

    public AgentMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        
        // LLM 调用计数器
        this.llmCallCounter = Counter.builder("agent.llm.call.total")
                .description("LLM调用总次数")
                .register(meterRegistry);
        
        this.llmCallSuccessCounter = Counter.builder("agent.llm.call.success")
                .description("LLM调用成功次数")
                .register(meterRegistry);
        
        this.llmCallErrorCounter = Counter.builder("agent.llm.call.error")
                .description("LLM调用失败次数")
                .register(meterRegistry);
        
        // LLM 调用耗时
        this.llmCallTimer = Timer.builder("agent.llm.call.duration")
                .description("LLM调用耗时")
                .register(meterRegistry);
        
        // DAG 节点执行计数
        this.dagNodeCounter = Counter.builder("agent.dag.node.executed")
                .description("DAG节点执行次数")
                .register(meterRegistry);
        
        // 任务派发计数
        this.taskDispatchCounter = Counter.builder("agent.task.dispatch.total")
                .description("子任务派发总次数")
                .register(meterRegistry);
        
        // 任务完成计数
        this.taskCompleteCounter = Counter.builder("agent.task.complete.total")
                .description("子任务完成总次数")
                .register(meterRegistry);

        // 重试计数
        this.retryCounter = Counter.builder("agent.retry.total")
                .description("重试执行总次数")
                .register(meterRegistry);
    }

    /**
     * 包装 LLM 调用，自动采集指标
     * 
     * 使用示例：
     *   String result = agentMetrics.llmCall("analyze", () -> llmClient.chat(prompt));
     * 
     * @param action 操作名称（用于标签）
     * @param callable 实际的 LLM 调用逻辑
     * @return LLM 调用结果
     */
    public <T> T llmCall(String action, Callable<T> callable) throws Exception {
        llmCallCounter.increment();
        long startTime = System.nanoTime();
        
        try {
            T result = callable.call();
            llmCallSuccessCounter.increment();
            return result;
        } catch (Exception e) {
            llmCallErrorCounter.increment();
            throw e;
        } finally {
            long duration = System.nanoTime() - startTime;
            llmCallTimer.record(duration, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 记录 DAG 节点执行
     */
    public void recordDagNodeExecution(String nodeName) {
        dagNodeCounter.increment();
    }

    /**
     * 记录任务派发
     */
    public void recordTaskDispatch() {
        taskDispatchCounter.increment();
    }

    /**
     * 记录任务完成
     */
    public void recordTaskComplete() {
        taskCompleteCounter.increment();
    }

    /**
     * 记录重试执行
     *
     * @param taskName 任务名称（用于日志标识）
     */
    public void recordRetry(String taskName) {
        retryCounter.increment();
    }

    /**
     * 获取重试总次数
     */
    public double getRetryCount() {
        return retryCounter.count();
    }

    /**
     * 获取 LLM 调用总次数
     */
    public double getLlmCallCount() {
        return llmCallCounter.count();
    }

    /**
     * 获取 LLM 调用平均耗时（毫秒）
     */
    public double getLlmCallAverageMs() {
        return llmCallTimer.mean(TimeUnit.MILLISECONDS);
    }
}
