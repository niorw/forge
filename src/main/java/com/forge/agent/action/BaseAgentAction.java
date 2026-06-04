package com.forge.agent.action;

import com.forge.agent.event.StateEventBus;
import com.forge.agent.metrics.AgentMetrics;
import com.forge.agent.state.MainState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 动作模板方法基类
 * 
 * 核心设计：Template Method 模式
 * 
 * execute() 定义了固定的执行骨架：
 *   1. 前置钩子 onBefore() — 设置 phase、发送事件
 *   2. 业务逻辑 doExecute() — 子类实现
 *   3. 后置钩子 onAfter() — 发送完成事件
 *   4. 异常处理 onError() — 统一错误处理
 * 
 * 子类只需实现 doExecute()，所有横切关注点由基类统一处理。
 * 
 * 消除的重复代码（每个 Action 约 30 行）：
 * - try/catch 异常处理
 * - state.setPhase() / state.setCurrentNode()
 * - log.info("=== xxx 节点开始 ===")
 * - log.error("xxx 失败", e)
 * - state.setError()
 * - metrics 记录
 */
public abstract class BaseAgentAction implements AgentAction {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final AgentMetrics metrics;
    private final StateEventBus eventBus;

    protected BaseAgentAction(AgentMetrics metrics, StateEventBus eventBus) {
        this.metrics = metrics;
        this.eventBus = eventBus;
    }

    /**
     * 模板方法 — 定义执行骨架，子类不应覆盖此方法
     */
    @Override
    public final MainState execute(MainState state) {
        String actionName = name();
        String phaseName = phase();

        log.info("=== {} 开始 ===", actionName);
        state.setPhase(phaseName);
        state.setCurrentNode(actionName);

        // 发送节点开始事件
        eventBus.fireNodeStarted(state, actionName);

        try {
            // 执行子类的业务逻辑
            state = doExecute(state);

            // 如果子类没有设置错误，标记为完成
            if (state.getError() == null) {
                log.info("=== {} 完成 ===", actionName);
                eventBus.fireNodeCompleted(state, actionName);
            } else {
                log.warn("=== {} 执行中产生错误: {} ===", actionName, state.getError());
                eventBus.fireNodeFailed(state, actionName, state.getError());
            }

        } catch (Exception e) {
            onError(state, e);
        }

        return state;
    }

    /**
     * 子类实现：具体的业务逻辑
     * 
     * 注意：
     * - 不需要 try/catch，异常由 onError() 统一处理
     * - 不需要 setPhase()，已由 execute() 处理
     * - 如果业务判断需要标记错误，调用 state.setError() 并返回
     * 
     * @param state 当前状态
     * @return 更新后的状态
     * @throws Exception 业务异常
     */
    protected abstract MainState doExecute(MainState state) throws Exception;

    /**
     * 统一错误处理 — 子类可覆盖以自定义错误处理策略
     * 
     * 默认行为：记录日志 + 设置 state 错误信息
     */
    protected void onError(MainState state, Exception e) {
        String actionName = name();
        log.error("{} 执行失败", actionName, e);
        state.setError(actionName + " 执行失败: " + e.getMessage());
        state.setPhase("ERROR");
        eventBus.fireNodeFailed(state, actionName, e.getMessage());
    }

    // ==================== 便利方法 ====================

    /**
     * 带 metrics 采集的 LLM 调用包装
     * 子类可以直接调用此方法，自动获得 metrics 采集能力
     */
    protected <T> T llmCall(String action, java.util.concurrent.Callable<T> callable) {
        try {
            return metrics.llmCall(action, callable);
        } catch (Exception e) {
            throw new RuntimeException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    protected AgentMetrics metrics() {
        return metrics;
    }

    protected StateEventBus eventBus() {
        return eventBus;
    }
}
