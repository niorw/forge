package com.forge.agent.event;

/**
 * 状态事件监听器接口
 * 
 * 实现此接口以监听工作流状态变化：
 * - WorkflowMonitorListener: SSE 推送
 * - MetricsListener: Prometheus 指标
 * - AlertListener: 飞书/钉钉告警
 */
@FunctionalInterface
public interface StateEventListener {
    void onEvent(StateEvent event);
}
