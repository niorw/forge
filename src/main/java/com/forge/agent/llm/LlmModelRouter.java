package com.forge.agent.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * LLM 模型路由器
 *
 * 根据 Agent 角色选择不同的 LLM 模型：
 * - planner:   快速/便宜（需求分析、DAG规划）
 * - architect: 强大（架构设计、Spec编写）
 * - reviewer:  强大（代码审查、安全审查）
 * - worker:    中等（代码生成、测试生成）
 * - default:   兜底模型
 *
 * 配置方式：application.yml 中 agent.models.xxx
 */
@Component
public class LlmModelRouter {

    private final Map<String, ChatModel> modelMap;
    private final ChatModel defaultModel;

    public LlmModelRouter(Map<String, ChatModel> modelMap, ChatModel defaultModel) {
        this.modelMap = modelMap;
        this.defaultModel = defaultModel;
    }

    /**
     * 根据角色获取对应的 ChatModel
     *
     * @param role 角色名：planner/architect/reviewer/worker
     * @return 对应的 ChatModel，找不到则返回 default
     */
    public ChatModel resolve(String role) {
        if (role == null) return defaultModel;
        return modelMap.getOrDefault(role, defaultModel);
    }

    /**
     * 获取所有已注册的角色
     */
    public java.util.Set<String> availableRoles() {
        return modelMap.keySet();
    }
}
