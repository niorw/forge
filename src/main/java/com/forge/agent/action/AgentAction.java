package com.forge.agent.action;

import com.forge.agent.state.MainState;

/**
 * Agent 工作流动作统一接口
 * 
 * 所有工作流节点（Analyze、Plan、ReviewSpec、Approval、Schedule、Dispatch、Collect、Integrate）
 * 都必须实现此接口，以获得：
 * - 统一的执行契约
 * - 自动的 metrics/日志/错误处理（由 BaseAgentAction 提供）
 * - 可组合、可替换的能力
 * 
 * 设计意图：
 * - 消除 8 个 Action 中 30% 的重复样板代码
 * - 使得横切关注点（metrics、日志、重试）可以统一织入
 * - 支持 Action 的动态组合和装饰器模式
 */
public interface AgentAction {

    /**
     * 执行动作的核心逻辑
     * 
     * 实现者只需关注业务逻辑，不需要处理：
     * - 异常捕获（由 BaseAgentAction 统一处理）
     * - metrics 采集（由 BaseAgentAction 统一处理）
     * - 日志记录（由 BaseAgentAction 统一处理）
     * 
     * @param state 当前主状态（可变）
     * @return 更新后的状态
     * @throws Exception 业务异常（由框架层统一处理）
     */
    MainState execute(MainState state) throws Exception;

    /**
     * 获取动作名称，用于 metrics 标签和日志标识
     * 默认取类名去掉 Action 后缀，子类可覆盖
     */
    default String name() {
        String simpleName = getClass().getSimpleName();
        if (simpleName.endsWith("Action")) {
            simpleName = simpleName.substring(0, simpleName.length() - 6);
        }
        // 驼峰转 kebab-case
        return simpleName.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
    }

    /**
     * 获取此动作对应的阶段标识
     * 用于 MainState.setPhase()，默认取 name().toUpperCase()
     */
    default String phase() {
        return name().toUpperCase().replace("-", "_");
    }
}
