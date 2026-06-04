package com.forge.agent.resilience;

import java.net.SocketTimeoutException;
import java.net.ConnectException;
import java.util.Set;

/**
 * 重试策略配置。
 *
 * @param maxAttempts          最大重试次数（含首次执行）
 * @param delayMs              初始延迟（毫秒）
 * @param backoffMultiplier    退避倍数
 * @param maxDelayMs           最大延迟（毫秒）
 * @param retryableExceptions  可重试的异常类型集合（空集合表示所有异常都可重试）
 */
public record RetryPolicy(
        int maxAttempts,
        long delayMs,
        double backoffMultiplier,
        long maxDelayMs,
        Set<Class<? extends Exception>> retryableExceptions
) {

    /** 默认策略：3 次，1 秒，2 倍退避，最大 30 秒 */
    public static RetryPolicy defaults() {
        return new RetryPolicy(3, 1000L, 2.0, 30_000L, Set.of());
    }

    /** 激进策略：5 次，500ms，1.5 倍退避，最大 10 秒 */
    public static RetryPolicy aggressive() {
        return new RetryPolicy(5, 500L, 1.5, 10_000L, Set.of());
    }

    /** LLM 调用策略：3 次，2 秒，2 倍退避，最大 60 秒，仅重试超时和连接异常 */
    public static RetryPolicy llm() {
        return new RetryPolicy(
                3, 2000L, 2.0, 60_000L,
                Set.of(SocketTimeoutException.class, ConnectException.class)
        );
    }

    /**
     * 计算指定重试次数后的延迟时间（毫秒）。
     *
     * @param attempt 当前重试次数（从 0 开始）
     * @return 延迟毫秒数
     */
    public long calculateDelay(int attempt) {
        double delay = delayMs * Math.pow(backoffMultiplier, attempt);
        return Math.min((long) delay, maxDelayMs);
    }

    /**
     * 判断给定异常是否可重试。
     * 若 retryableExceptions 为空，则所有异常都可重试。
     */
    public boolean isRetryable(Exception e) {
        if (retryableExceptions.isEmpty()) {
            return true;
        }
        return retryableExceptions.stream().anyMatch(cls -> cls.isInstance(e));
    }
}
