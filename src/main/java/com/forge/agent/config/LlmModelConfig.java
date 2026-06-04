package com.forge.agent.config;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.HashMap;
import java.util.Map;

/**
 * 多模型 + 双供应商配置
 *
 * 同时支持 OpenAI 和 Anthropic，每个角色可独立选择供应商和模型。
 *
 * 配置示例（application.yml）：
 *   agent.models:
 *     planner:
 *       provider: anthropic          # 或 openai
 *       model: claude-haiku-3.5
 *     architect:
 *       provider: anthropic
 *       model: claude-sonnet-4-20250514
 *     worker:
 *       provider: openai
 *       model: gpt-4o
 *
 * 不配置的角色自动使用 spring.ai.anthropic 的默认配置。
 */
@Configuration
public class LlmModelConfig {

    // ===== Anthropic 配置 =====
    @Value("${spring.ai.anthropic.api-key:}")
    private String anthropicApiKey;

    @Value("${spring.ai.anthropic.base-url:https://api.anthropic.com}")
    private String anthropicBaseUrl;

    @Value("${spring.ai.anthropic.chat.options.model:claude-sonnet-4-20250514}")
    private String anthropicDefaultModel;

    @Value("${spring.ai.anthropic.chat.options.max-tokens:4096}")
    private int anthropicMaxTokens;

    // ===== OpenAI 配置 =====
    @Value("${spring.ai.openai.api-key:}")
    private String openaiApiKey;

    @Value("${spring.ai.openai.base-url:https://api.openai.com/v1}")
    private String openaiBaseUrl;

    @Value("${spring.ai.openai.chat.options.model:gpt-4o}")
    private String openaiDefaultModel;

    @Value("${spring.ai.openai.chat.options.max-tokens:4096}")
    private int openaiMaxTokens;

    // ===== 各角色配置 =====
    @Value("${agent.models.planner.provider:anthropic}")
    private String plannerProvider;

    @Value("${agent.models.planner.model:${spring.ai.anthropic.chat.options.model}}")
    private String plannerModel;

    @Value("${agent.models.planner.max-tokens:2048}")
    private int plannerMaxTokens;

    @Value("${agent.models.architect.provider:anthropic}")
    private String architectProvider;

    @Value("${agent.models.architect.model:${spring.ai.anthropic.chat.options.model}}")
    private String architectModel;

    @Value("${agent.models.architect.max-tokens:4096}")
    private int architectMaxTokens;

    @Value("${agent.models.reviewer.provider:anthropic}")
    private String reviewerProvider;

    @Value("${agent.models.reviewer.model:${spring.ai.anthropic.chat.options.model}}")
    private String reviewerModel;

    @Value("${agent.models.reviewer.max-tokens:4096}")
    private int reviewerMaxTokens;

    @Value("${agent.models.worker.provider:anthropic}")
    private String workerProvider;

    @Value("${agent.models.worker.model:${spring.ai.anthropic.chat.options.model}}")
    private String workerModel;

    @Value("${agent.models.worker.max-tokens:8192}")
    private int workerMaxTokens;

    /**
     * 默认模型（兜底，用 Anthropic）
     */
    @Bean
    @Primary
    public ChatModel defaultChatModel() {
        return buildModel("anthropic", anthropicDefaultModel, anthropicMaxTokens);
    }

    /**
     * 模型注册表：角色名 → ChatModel
     */
    @Bean
    public Map<String, ChatModel> chatModelMap() {
        Map<String, ChatModel> map = new HashMap<>();
        map.put("planner", buildModel(plannerProvider, plannerModel, plannerMaxTokens));
        map.put("architect", buildModel(architectProvider, architectModel, architectMaxTokens));
        map.put("reviewer", buildModel(reviewerProvider, reviewerModel, reviewerMaxTokens));
        map.put("worker", buildModel(workerProvider, workerModel, workerMaxTokens));
        return map;
    }

    /**
     * 根据供应商和模型名构建 ChatModel
     */
    private ChatModel buildModel(String provider, String model, int maxTokens) {
        if ("openai".equalsIgnoreCase(provider)) {
            return buildOpenAiModel(model, maxTokens);
        } else {
            return buildAnthropicModel(model, maxTokens);
        }
    }

    private ChatModel buildAnthropicModel(String model, int maxTokens) {
        var api = AnthropicApi.builder()
                .apiKey(anthropicApiKey)
                .baseUrl(anthropicBaseUrl)
                .build();
        var options = AnthropicChatOptions.builder()
                .model(model)
                .maxTokens(maxTokens)
                .build();
        return AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(options)
                .build();
    }

    private ChatModel buildOpenAiModel(String model, int maxTokens) {
        var api = OpenAiApi.builder()
                .apiKey(openaiApiKey)
                .baseUrl(openaiBaseUrl)
                .build();
        var options = OpenAiChatOptions.builder()
                .model(model)
                .maxTokens(maxTokens)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }
}
