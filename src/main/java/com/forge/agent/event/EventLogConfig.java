package com.forge.agent.event;

import com.forge.agent.tool.ToolRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * 事件日志自动配置
 *
 * 将 AgentEventLog 注册为：
 * 1. StateEventBus 的监听器（接收工作流事件）
 * 2. ToolRegistry 的事件记录器（接收工具调用事件）
 */
@Configuration
public class EventLogConfig {

    private static final Logger log = LoggerFactory.getLogger(EventLogConfig.class);

    private final StateEventBus eventBus;
    private final AgentEventLog eventLog;
    private final ToolRegistry toolRegistry;

    public EventLogConfig(StateEventBus eventBus, AgentEventLog eventLog, ToolRegistry toolRegistry) {
        this.eventBus = eventBus;
        this.eventLog = eventLog;
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    public void init() {
        // 1. 注册 AgentEventLog 为 StateEventBus 监听器
        eventBus.subscribe(eventLog);
        log.info("AgentEventLog 已注册为 StateEventBus 监听器");

        // 2. 将 AgentEventLog 注入 ToolRegistry（用于工具调用事件记录）
        toolRegistry.setEventLog(eventLog);
        log.info("AgentEventLog 已注入 ToolRegistry");
    }
}
