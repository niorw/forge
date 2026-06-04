package com.forge.agent.resilience;

import com.forge.agent.metrics.AgentMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;

/**
 * 通用重试执行器。
 *
 * 提供两种执行模式：
 * <ul>
 *   <li>{@link #execute} —— 简单重试，每次用相同逻辑重试</li>
 *   <li>{@link #executeWithFeedback} —— 带反馈的重试，可基于上次失败结果调整重试逻辑</li>
 * </ul>
 *
 * 参考 Hermes 的 terminal tool 执行 + 重试模式。
 */
@Component
public class RetryExecutor {

    private static final Logger log = LoggerFactory.getLogger(RetryExecutor.class);

    private final AgentMetrics agentMetrics;

    public RetryExecutor(AgentMetrics agentMetrics) {
        this.agentMetrics = agentMetrics;
    }

    // ==================== 函数式接口 ====================

    /**
     * 带反馈的执行动作。
     *
     * @param <T> 返回类型
     */
    @FunctionalInterface
    public interface FeedbackAction<T> {
        /**
         * 执行操作。
         *
         * @param previousResult 上一次执行的结果（首次执行时为 null）
         * @param previousError  上一次执行的错误信息（首次执行时为 null）
         * @return 本次执行结果
         * @throws Exception 执行异常
         */
        T execute(T previousResult, String previousError) throws Exception;
    }

    /**
     * 失败反馈处理器。
     *
     * @param <T> 返回类型
     */
    @FunctionalInterface
    public interface FeedbackHandler<T> {
        /**
         * 处理执行失败。
         *
         * @param result  本次失败的结果
         * @param error   错误信息
         * @param attempt 当前重试次数（从 1 开始）
         * @return 处理后的结果（用于下次重试时传入 FeedbackAction.previousResult）
         */
        T handleFailure(T result, String error, int attempt);
    }

    // ==================== 核心方法 ====================

    /**
     * 简单重试执行。
     * 每次使用相同的 Callable 重试，适用于幂等操作。
     *
     * @param taskName  任务名称（用于日志）
     * @param policy    重试策略
     * @param action    要执行的动作
     * @return 执行结果
     * @param <T> 返回类型
     */
    public <T> T execute(String taskName, RetryPolicy policy, Callable<T> action) {
        return executeWithFeedback(taskName, policy,
                (prevResult, prevError) -> action.call(),
                (result, error, attempt) -> result);
    }

    /**
     * 带反馈的重试执行。
     * 每次重试时，将上次的结果和错误信息传递给 FeedbackAction，
     * 以便调用方根据失败原因调整重试策略（如让 LLM 修复代码）。
     *
     * @param taskName        任务名称（用于日志）
     * @param policy          重试策略
     * @param action          执行动作，接收上次结果和错误
     * @param feedbackHandler 失败反馈处理器
     * @return 最终执行结果
     * @param <T> 返回类型
     */
    public <T> T executeWithFeedback(String taskName, RetryPolicy policy,
                                      FeedbackAction<T> action,
                                      FeedbackHandler<T> feedbackHandler) {
        T previousResult = null;
        String previousError = null;

        for (int attempt = 0; attempt < policy.maxAttempts(); attempt++) {
            try {
                // 记录重试次数指标（首次不算重试）
                if (attempt > 0) {
                    agentMetrics.recordRetry(taskName);
                }

                T result = action.execute(previousResult, previousError);

                // 判断结果是否为失败（支持 ToolResult 等带有 success 字段的对象）
                if (isResultFailed(result)) {
                    String error = extractError(result);
                    log.warn("[{}] 第 {} 次执行失败: {}", taskName, attempt + 1, error);

                    previousResult = feedbackHandler.handleFailure(result, error, attempt + 1);
                    previousError = error;

                    // 还有重试机会，等待退避时间
                    if (attempt < policy.maxAttempts() - 1) {
                        long delay = policy.calculateDelay(attempt);
                        log.info("[{}] 等待 {}ms 后进行第 {} 次重试", taskName, delay, attempt + 2);
                        Thread.sleep(delay);
                    }
                    continue;
                }

                // 执行成功
                if (attempt > 0) {
                    log.info("[{}] 第 {} 次重试成功", taskName, attempt + 1);
                }
                return result;

            } catch (Exception e) {
                // 判断异常是否可重试
                if (!policy.isRetryable(e)) {
                    log.error("[{}] 不可重试异常: {}", taskName, e.getMessage());
                    throw new RetryExhaustedException(taskName, attempt + 1, e);
                }

                log.warn("[{}] 第 {} 次执行异常: {} ({})",
                        taskName, attempt + 1, e.getMessage(), e.getClass().getSimpleName());

                previousResult = feedbackHandler.handleFailure(null, e.getMessage(), attempt + 1);
                previousError = e.getMessage();

                // 还有重试机会，等待退避时间
                if (attempt < policy.maxAttempts() - 1) {
                    try {
                        long delay = policy.calculateDelay(attempt);
                        log.info("[{}] 等待 {}ms 后进行第 {} 次重试", taskName, delay, attempt + 2);
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RetryExhaustedException(taskName, attempt + 1, e);
                    }
                }
            }
        }

        // 所有重试都失败了
        log.error("[{}] 已达到最大重试次数 {}，最终失败", taskName, policy.maxAttempts());
        throw new RetryExhaustedException(taskName, policy.maxAttempts(), previousError);
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 判断结果是否为失败状态。
     * 支持 ToolResult 等带有 success 字段的对象。
     */
    private <T> boolean isResultFailed(T result) {
        if (result == null) {
            return true;
        }
        // 通过反射检查是否有 isSuccess() 或 success() 方法
        try {
            var method = result.getClass().getMethod("success");
            Object value = method.invoke(result);
            if (value instanceof Boolean b) {
                return !b;
            }
        } catch (NoSuchMethodException ignored) {
            // 不是 ToolResult 类型，视为成功
        } catch (Exception ignored) {
            // 反射异常，视为成功
        }
        return false;
    }

    /**
     * 从结果中提取错误信息。
     * 支持 ToolResult 等带有 error() 方法的对象。
     */
    private <T> String extractError(T result) {
        if (result == null) {
            return "结果为 null";
        }
        try {
            var method = result.getClass().getMethod("error");
            Object value = method.invoke(result);
            if (value instanceof String s) {
                return s;
            }
        } catch (NoSuchMethodException ignored) {
            // 不是 ToolResult 类型
        } catch (Exception ignored) {
            // 反射异常
        }
        return result.toString();
    }

    // ==================== 异常类 ====================

    /**
     * 重试耗尽异常。
     * 当所有重试都失败后抛出。
     */
    public static class RetryExhaustedException extends RuntimeException {
        private final String taskName;
        private final int attempts;

        public RetryExhaustedException(String taskName, int attempts, Throwable cause) {
            super(String.format("[%s] 重试耗尽，共尝试 %d 次", taskName, attempts), cause);
            this.taskName = taskName;
            this.attempts = attempts;
        }

        public RetryExhaustedException(String taskName, int attempts, String lastError) {
            super(String.format("[%s] 重试耗尽，共尝试 %d 次，最后错误: %s", taskName, attempts, lastError));
            this.taskName = taskName;
            this.attempts = attempts;
        }

        public String getTaskName() {
            return taskName;
        }

        public int getAttempts() {
            return attempts;
        }
    }
}
