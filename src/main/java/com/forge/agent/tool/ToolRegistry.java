package com.forge.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.forge.agent.event.AgentEvent;
import com.forge.agent.event.AgentEventLog;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Stream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具注册中心 —— Agent 能力的核心枢纽。
 * <p>
 * 参考 Hermes 的 tools/registry.py 设计：
 * <ul>
 *   <li>中心注册表，工具自注册</li>
 *   <li>每个工具有前置条件检查</li>
 *   <li>工具按 toolset 分组，可按需启用/禁用</li>
 *   <li>统一 handler 签名执行</li>
 * </ul>
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** name → ToolEntry */
    private final Map<String, ToolEntry> registry = new ConcurrentHashMap<>();

    /** 事件日志（可选注入，为 null 时不记录事件） */
    private AgentEventLog eventLog;

    /** 设置事件日志（由 EventLogConfig 调用） */
    public void setEventLog(AgentEventLog eventLog) {
        this.eventLog = eventLog;
    }

    // ==================== 内部记录 ====================

    public record ToolMetadata(String name, String description, String toolset, String[] requiredEnv) {}

    public record ToolEntry(ToolMetadata metadata, ToolHandler handler, boolean enabled) {}

    @FunctionalInterface
    public interface ToolHandler {
        ToolResult execute(Map<String, Object> args, ToolContext context);
    }

    // ==================== 注册 ====================

    /**
     * 手动注册一个工具。
     */
    public void register(String name, ToolHandler handler, ToolMetadata metadata) {
        if (registry.containsKey(name)) {
            log.warn("工具 [{}] 已存在，将被覆盖", name);
        }
        registry.put(name, new ToolEntry(metadata, handler, true));
        log.info("注册工具: {} (toolset={})", name, metadata.toolset());
    }

    /**
     * 通过 @Tool 注解自动扫描并注册 bean 中的所有工具方法。
     */
    public void register(Object toolBean) {
        Class<?> clazz = toolBean.getClass();
        // Spring CGLIB 代理需要取父类
        Class<?> targetClass = clazz;
        while (targetClass != null && targetClass.getName().contains("$$")) {
            targetClass = targetClass.getSuperclass();
        }
        if (targetClass == null) targetClass = clazz;

        for (Method method : targetClass.getDeclaredMethods()) {
            Tool annotation = method.getAnnotation(Tool.class);
            if (annotation == null) continue;

            // 构建 handler：通过反射调用方法
            ToolHandler handler = (args, ctx) -> {
                try {
                    method.setAccessible(true);
                    // 支持 (Map, ToolContext) 和 (Map) 两种签名
                    if (method.getParameterCount() == 2) {
                        return (ToolResult) method.invoke(toolBean, args, ctx);
                    } else {
                        return (ToolResult) method.invoke(toolBean, args);
                    }
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    return ToolResult.fail("工具执行异常: " + cause.getMessage());
                }
            };

            // 解析 requiredEnv
            String[] requiredEnv = annotation.checkEnv().isEmpty()
                    ? new String[0]
                    : annotation.checkEnv().split(",");

            ToolMetadata metadata = new ToolMetadata(
                    annotation.name(),
                    annotation.description(),
                    annotation.toolset(),
                    requiredEnv
            );

            register(annotation.name(), handler, metadata);
        }
    }

    // ==================== 查询 ====================

    /** 按名称获取工具 */
    public Optional<ToolEntry> get(String name) {
        return Optional.ofNullable(registry.get(name));
    }

    /** 列出所有工具 */
    public List<ToolEntry> list() {
        return List.copyOf(registry.values());
    }

    /** 按 toolset 分组列出 */
    public List<ToolEntry> listByToolset(String toolset) {
        return registry.values().stream()
                .filter(e -> e.metadata().toolset().equals(toolset))
                .collect(Collectors.toList());
    }

    /** 列出所有已启用的工具 */
    public List<ToolEntry> listEnabled() {
        return registry.values().stream()
                .filter(ToolEntry::enabled)
                .collect(Collectors.toList());
    }

    /** 列出所有可用的工具名称 */
    public Set<String> names() {
        return Collections.unmodifiableSet(registry.keySet());
    }

    // ==================== 执行 ====================

    /**
     * 执行工具。
     *
     * @param name 工具名称
     * @param args 参数
     * @param ctx  执行上下文
     * @return 执行结果
     */
    public ToolResult execute(String name, Map<String, Object> args, ToolContext ctx) {
        ToolEntry entry = registry.get(name);
        if (entry == null) {
            return ToolResult.fail("工具不存在: " + name);
        }
        if (!entry.enabled()) {
            return ToolResult.fail("工具已禁用: " + name);
        }

        // 前置条件检查
        for (String envKey : entry.metadata().requiredEnv()) {
            if (envKey != null && !envKey.isBlank() && System.getenv(envKey.trim()) == null) {
                return ToolResult.fail("工具 [" + name + "] 缺少必要环境变量: " + envKey.trim());
            }
        }

        // 记录工具调用事件
        String agentRole = ctx != null ? ctx.agentRole() : null;
        String threadId = ctx != null ? ctx.threadId() : null;
        if (eventLog != null) {
            eventLog.record(AgentEvent.toolCall(name, agentRole, threadId, args));
        }

        long startTime = System.currentTimeMillis();
        try {
            ToolResult result = entry.handler().execute(args != null ? args : Map.of(), ctx);
            long durationMs = System.currentTimeMillis() - startTime;

            // 记录工具结果事件
            if (eventLog != null) {
                eventLog.record(AgentEvent.toolResult(name, agentRole, threadId, result.success(), durationMs));
            }

            return result;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;

            // 记录工具错误事件
            if (eventLog != null) {
                eventLog.record(AgentEvent.toolError(name, agentRole, threadId, e.getMessage()));
            }

            return ToolResult.fail("工具执行异常: " + e.getMessage());
        }
    }

    // ==================== 启用/禁用 ====================

    public void enable(String name) {
        ToolEntry old = registry.get(name);
        if (old != null) {
            registry.put(name, new ToolEntry(old.metadata(), old.handler(), true));
        }
    }

    public void disable(String name) {
        ToolEntry old = registry.get(name);
        if (old != null) {
            registry.put(name, new ToolEntry(old.metadata(), old.handler(), false));
        }
    }

    // ==================== 角色权限隔离 ====================

    /**
     * 角色工具权限黑名单。
     *
     * 核心理念（来自 Anthropic Harness Engineering）：
     * "Code Reviewer 和 Security Reviewer 没有 Write 和 Edit 权限。
     *  评审者只能阅读和分析，不能修改代码。
     *  这确保了评审的独立性——如果评审者可以直接修改代码，
     *  它就会倾向于'修复后通过'而不是'标记问题并拒绝'。"
     *
     * 设计：role → 被禁止的 toolset 集合
     */
    private static final Map<String, Set<String>> ROLE_DENY_TOOLSETS = Map.of(
            // reviewer 禁止写入和编辑类工具，只审不改
            "reviewer", Set.of("write", "edit", "git", "mcp-github", "mcp-jenkins"),
            // security-reviewer 同样禁止写入，且禁止数据库操作
            "security-reviewer", Set.of("write", "edit", "git", "mcp-github", "mcp-jenkins", "mcp-mysql")
    );

    /**
     * 角色工具权限白名单（可选，优先级高于黑名单）。
     * 如果设置了白名单，则只允许使用白名单中的工具。
     */
    private static final Map<String, Set<String>> ROLE_ALLOW_TOOLSETS = Map.of();

    /**
     * 带角色权限检查的执行。
     *
     * @param name   工具名称
     * @param args   参数
     * @param ctx    执行上下文（含 agentRole）
     * @return 执行结果
     */
    public ToolResult executeWithRoleCheck(String name, Map<String, Object> args, ToolContext ctx) {
        // 先做基础检查
        ToolEntry entry = registry.get(name);
        if (entry == null) {
            return ToolResult.fail("工具不存在: " + name);
        }
        if (!entry.enabled()) {
            return ToolResult.fail("工具已禁用: " + name);
        }

        // 角色权限检查
        String role = ctx.agentRole();
        if (role != null && !role.isBlank()) {
            String toolset = entry.metadata().toolset();

            // 白名单检查（如果存在）
            Set<String> allowList = ROLE_ALLOW_TOOLSETS.get(role);
            if (allowList != null && !allowList.isEmpty() && !allowList.contains(toolset)) {
                return ToolResult.fail(String.format(
                        "权限拒绝：角色 [%s] 不允许使用 toolset [%s] 的工具 [%s]",
                        role, toolset, name));
            }

            // 黑名单检查
            Set<String> denyList = ROLE_DENY_TOOLSETS.get(role);
            if (denyList != null && denyList.contains(toolset)) {
                return ToolResult.fail(String.format(
                        "权限拒绝：角色 [%s] 禁止使用 toolset [%s] 的工具 [%s]（只审不改原则）",
                        role, toolset, name));
            }
        }

        // 前置条件检查
        for (String envKey : entry.metadata().requiredEnv()) {
            if (envKey != null && !envKey.isBlank() && System.getenv(envKey.trim()) == null) {
                return ToolResult.fail("工具 [" + name + "] 缺少必要环境变量: " + envKey.trim());
            }
        }

        try {
            return entry.handler().execute(args != null ? args : Map.of(), ctx);
        } catch (Exception e) {
            return ToolResult.fail("工具执行异常: " + e.getMessage());
        }
    }

    /**
     * 获取角色允许使用的工具列表
     */
    public List<ToolEntry> listForRole(String role) {
        Set<String> denyList = ROLE_DENY_TOOLSETS.getOrDefault(role, Set.of());
        Set<String> allowList = ROLE_ALLOW_TOOLSETS.get(role);

        return registry.values().stream()
                .filter(ToolEntry::enabled)
                .filter(e -> {
                    String toolset = e.metadata().toolset();
                    // 白名单优先
                    if (allowList != null && !allowList.isEmpty()) {
                        return allowList.contains(toolset);
                    }
                    // 黑名单过滤
                    return !denyList.contains(toolset);
                })
                .collect(Collectors.toList());
    }

    /**
     * 检查角色是否有权限使用某工具
     */
    public boolean hasPermission(String role, String toolName) {
        ToolEntry entry = registry.get(toolName);
        if (entry == null) return false;

        String toolset = entry.metadata().toolset();
        Set<String> denyList = ROLE_DENY_TOOLSETS.getOrDefault(role, Set.of());
        Set<String> allowList = ROLE_ALLOW_TOOLSETS.get(role);

        if (allowList != null && !allowList.isEmpty()) {
            return allowList.contains(toolset);
        }
        return !denyList.contains(toolset);
    }
}
