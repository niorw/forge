package com.forge.agent.event;

import com.forge.agent.state.MainState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工作流状态事件总线
 * 
 * 职责：
 * - 解耦 Action 与外部观察者（WorkflowMonitor、日志、告警）
 * - Action 只管发事件，不需要知道谁在监听
 * - 支持多观察者：SSE 推送、Prometheus metrics、飞书告警 等
 * 
 * 设计模式：Observer + EventBus
 * 
 * 事件流：
 *   Action → StateEventBus → [WorkflowMonitorListener, MetricsListener, AlertListener, ...]
 */
@Component
public class StateEventBus {

    private static final Logger log = LoggerFactory.getLogger(StateEventBus.class);

    private final List<StateEventListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 注册事件监听器
     */
    public void subscribe(StateEventListener listener) {
        listeners.add(listener);
        log.debug("注册事件监听器: {}", listener.getClass().getSimpleName());
    }

    /**
     * 移除事件监听器
     */
    public void unsubscribe(StateEventListener listener) {
        listeners.remove(listener);
    }

    /**
     * 节点开始执行
     */
    public void fireNodeStarted(MainState state, String nodeName) {
        fireEvent(new StateEvent(state, nodeName, StateEvent.Type.STARTED, null, null));
    }

    /**
     * 节点执行完成
     */
    public void fireNodeCompleted(MainState state, String nodeName) {
        fireEvent(new StateEvent(state, nodeName, StateEvent.Type.COMPLETED, null, null));
    }

    /**
     * 节点执行失败
     */
    public void fireNodeFailed(MainState state, String nodeName, String error) {
        fireEvent(new StateEvent(state, nodeName, StateEvent.Type.FAILED, error, null));
    }

    /**
     * 审批等待
     */
    public void fireApprovalRequired(MainState state, String nodeName) {
        fireEvent(new StateEvent(state, nodeName, StateEvent.Type.APPROVAL_REQUIRED, null, null));
    }

    /**
     * 子任务派发
     */
    public void fireTaskDispatched(MainState state, String taskId) {
        fireEvent(new StateEvent(state, "dispatch", StateEvent.Type.TASK_DISPATCHED, null, taskId));
    }

    /**
     * 子任务完成
     */
    public void fireTaskCompleted(MainState state, String taskId) {
        fireEvent(new StateEvent(state, "collect", StateEvent.Type.TASK_COMPLETED, null, taskId));
    }

    private void fireEvent(StateEvent event) {
        for (StateEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                log.warn("事件监听器处理异常: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }
}
