package com.forge.agent.llm;

import com.forge.agent.event.AgentEvent;
import com.forge.agent.event.AgentEventLog;
import com.forge.agent.metrics.AgentMetrics;
import com.forge.agent.mq.TaskMessage;
import com.forge.agent.state.TaskNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring AI 实现的 LLM 网关
 * 
 * 相比旧的 LlmClientService 的改进：
 * 1. 提示词从 PromptRegistry 获取，不再硬编码
 * 2. JSON 解析提取为独立的 ResponseParser 工具类
 * 3. 实现 LlmGateway 接口，可被 Mock 替换
 * 4. metrics 采集由 BaseAgentAction 统一处理，此处不再重复
 */
@Component
public class SpringAiLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmGateway.class);

    private final ChatModel chatModel;
    private final LlmModelRouter modelRouter;
    private final PromptRegistry promptRegistry;
    private final ObjectMapper objectMapper;
    private final AgentEventLog eventLog;

    public SpringAiLlmGateway(ChatModel chatModel, LlmModelRouter modelRouter,
                              PromptRegistry promptRegistry, ObjectMapper objectMapper,
                              AgentEventLog eventLog) {
        this.chatModel = chatModel;
        this.modelRouter = modelRouter;
        this.promptRegistry = promptRegistry;
        this.objectMapper = objectMapper;
        this.eventLog = eventLog;
    }

    @Override
    public String chat(String action, String userPrompt) {
        PromptRegistry.PromptTemplate pt = promptRegistry.get(action);
        return doChat(action, pt.system(), userPrompt);
    }

    @Override
    public String chat(String action, String systemPrompt, String userPrompt) {
        return doChat(action, systemPrompt, userPrompt);
    }

    @Override
    public String chatWithRole(String role, String action, String userPrompt) {
        PromptRegistry.PromptTemplate pt = promptRegistry.get(action);
        return doChatWithRole(role, action, pt.system(), userPrompt);
    }

    @Override
    public String analyzeRequirement(String requirement) {
        return chatWithRole("planner", "analyze", requirement);
    }

    @Override
    public List<TaskNode> planDAG(String analysis, Map<String, String> availableSpecs) {
        // 构建带 Spec 上下文的规划提示词
        StringBuilder specContext = new StringBuilder();
        if (availableSpecs != null && !availableSpecs.isEmpty()) {
            specContext.append("\n\n## 可用的 Spec 文件\n");
            specContext.append("以下 Spec 已经由人写好，你必须为每个 Task 指定引用哪个 Spec：\n\n");
            for (Map.Entry<String, String> entry : availableSpecs.entrySet()) {
                specContext.append("### ").append(entry.getKey()).append("\n");
                // 只取前 500 字符作为摘要，避免 token 过长
                String content = entry.getValue();
                if (content.length() > 500) {
                    specContext.append(content, 0, 500).append("...\n\n");
                } else {
                    specContext.append(content).append("\n\n");
                }
            }
        }

        String userPrompt = analysis + specContext;
        String response = chatWithRole("planner", "plan", userPrompt);
        return ResponseParser.parseTaskDAG(response, objectMapper);
    }

    @Override
    public String generateSpec(TaskNode taskNode) {
        PromptRegistry.PromptTemplate pt = promptRegistry.get("generate-spec");
        String userPrompt = pt.user() != null
            ? pt.user().replace("{}", taskNode.name())
                         .replaceFirst("\\{\\}", taskNode.description())
                         .replaceFirst("\\{\\}", taskNode.nodeType())
            : String.format("任务名称：%s\n任务描述：%s\n任务类型：%s",
                taskNode.name(), taskNode.description(), taskNode.nodeType());
        return doChatWithRole("architect", "generate-spec", pt.system(), userPrompt);
    }

    @Override
    public String integrateResults(String resultsSummary) {
        return chatWithRole("planner", "integrate", "子任务结果汇总：\n" + resultsSummary);
    }

    @Override
    public String executeChildAgent(TaskMessage message) {
        PromptRegistry.PromptTemplate pt = promptRegistry.get("child-agent");
        String userPrompt = pt.user() != null
            ? pt.user().replace("{}", message.name())
                         .replaceFirst("\\{\\}", message.description())
                         .replaceFirst("\\{\\}", message.specContent() != null ? message.specContent() : "无")
            : String.format("任务名称：%s\n任务描述：\n\n执行规格（Spec）：\n%s",
                message.name(), message.description(),
                message.specContent() != null ? message.specContent() : "无");
        return doChatWithRole("worker", "child-agent", pt.system(), userPrompt);
    }

    @Override
    public String generateTest(String code, String acceptanceCriteria) {
        String userPrompt = String.format(
            "## 待测代码/实现\n%s\n\n## 验收标准\n%s\n\n请根据验收标准生成完整的测试用例并给出测试报告。",
            code, acceptanceCriteria);
        return chatWithRole("reviewer", "generate-test", userPrompt);
    }

    @Override
    public String integrationTest(Map<String, String> taskResults, String architecture) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 架构描述\n").append(architecture).append("\n\n## 各子任务结果\n");
        for (Map.Entry<String, String> entry : taskResults.entrySet()) {
            sb.append("### ").append(entry.getKey()).append("\n");
            String result = entry.getValue();
            // 截取前 1000 字符避免 token 过长
            if (result.length() > 1000) {
                sb.append(result, 0, 1000).append("...(截断)\n\n");
            } else {
                sb.append(result).append("\n\n");
            }
        }
        sb.append("请进行跨服务集成测试验证，检查接口一致性、数据流完整性和端到端场景。");
        return chatWithRole("reviewer", "integration-test", sb.toString());
    }

    @Override
    public String designArchitecture(String requirement, String analysis) {
        String userPrompt = String.format(
            "## 原始需求\n%s\n\n## 需求分析\n%s\n\n请输出架构设计方案，包括：服务拆分、依赖关系图、技术选型。",
            requirement, analysis != null ? analysis : "暂无");
        return chatWithRole("architect", "architecture", userPrompt);
    }

    @Override
    public String generateSubSpec(String taskDescription, String architecture, String specContent) {
        String userPrompt = String.format(
            "## 任务描述\n%s\n\n## 架构上下文\n%s\n\n## 已有 Spec\n%s\n\n请为该任务生成详细的 Spec，包括接口方法、验收标准、错误码定义。",
            taskDescription,
            architecture != null ? architecture : "暂无",
            specContent != null && !specContent.isBlank() ? specContent : "无");
        return chatWithRole("architect", "spec-author", userPrompt);
    }

    @Override
    public String reviewCode(String code, String specContent, String acceptanceCriteria) {
        String userPrompt = String.format(
            "## 待审查代码\n%s\n\n## 对应 Spec\n%s\n\n## 验收标准\n%s\n\n请审查代码，检查接口签名、错误码、安全性是否符合 Spec 和验收标准。",
            code != null ? code : "无代码",
            specContent != null ? specContent : "无 Spec",
            acceptanceCriteria != null && !acceptanceCriteria.isBlank() ? acceptanceCriteria : "无验收标准");
        return chatWithRole("reviewer", "review-code", userPrompt);
    }

    // ==================== 内部方法 ====================

    private String doChat(String action, String systemPrompt, String userPrompt) {
        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new SystemMessage(systemPrompt));
        }
        messages.add(new UserMessage(userPrompt));

        var response = chatModel.call(new Prompt(messages));
        String result = response.getResult().getOutput().getText();
        log.debug("LLM [{}] 响应长度: {}", action, result != null ? result.length() : 0);
        return result;
    }

    /**
     * 角色感知的 LLM 调用（根据角色选择不同模型）
     */
    private String doChatWithRole(String role, String action, String systemPrompt, String userPrompt) {
        ChatModel model = modelRouter.resolve(role);
        int estimatedTokens = (systemPrompt != null ? systemPrompt.length() : 0) + userPrompt.length();

        // 记录 LLM 请求事件
        eventLog.record(AgentEvent.llmRequest(role, action, null, estimatedTokens / 2));

        long startTime = System.currentTimeMillis();
        try {
            List<Message> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(new SystemMessage(systemPrompt));
            }
            messages.add(new UserMessage(userPrompt));

            var response = model.call(new Prompt(messages));
            String result = response.getResult().getOutput().getText();
            long durationMs = System.currentTimeMillis() - startTime;

            // 记录 LLM 响应事件
            eventLog.record(AgentEvent.llmResponse(role, action, null, durationMs,
                    result != null ? result.length() : 0));

            log.debug("LLM [{}:{}] 响应长度: {} ({}ms)", role, action,
                    result != null ? result.length() : 0, durationMs);
            return result;

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            // 记录 LLM 错误事件
            eventLog.record(AgentEvent.llmError(role, action, null, e.getMessage()));
            throw e;
        }
    }

    // ==================== JSON 响应解析器（独立工具类） ====================

    /**
     * LLM 响应解析器
     * 
     * 从 LlmClientService 中提取为独立的静态工具类
     * 职责单一：只负责将 LLM 的原始文本响应解析为结构化对象
     */
    public static class ResponseParser {

        /**
         * 解析 LLM 返回的 JSON 为 TaskNode 列表
         * 处理 LLM 可能返回的 markdown code block 包装
         */
        public static List<TaskNode> parseTaskDAG(String response, ObjectMapper objectMapper) {
            if (response == null || response.isBlank()) {
                throw new RuntimeException("LLM 返回的 DAG 规划为空");
            }

            String json = extractJson(response);

            try {
                JsonNode root = objectMapper.readTree(json);
                List<TaskNode> nodes = new ArrayList<>();

                for (JsonNode node : root) {
                    String nodeId = node.get("nodeId").asText();
                    String name = node.get("name").asText();
                    String description = node.has("description") ? node.get("description").asText() : "";
                    boolean requiresApproval = node.has("requiresApproval") && node.get("requiresApproval").asBoolean();
                    int priority = node.has("priority") ? node.get("priority").asInt(5) : 5;

                    List<String> deps = new ArrayList<>();
                    if (node.has("dependencies")) {
                        for (JsonNode dep : node.get("dependencies")) {
                            deps.add(dep.asText());
                        }
                    }

                    // Spec 引用字段
                    String specRef = node.has("specRef") ? node.get("specRef").asText(null) : null;
                    String specScope = node.has("specScope") ? node.get("specScope").asText(null) : null;
                    List<String> acceptanceCriteria = new ArrayList<>();
                    if (node.has("acceptanceCriteria")) {
                        for (JsonNode ac : node.get("acceptanceCriteria")) {
                            acceptanceCriteria.add(ac.asText());
                        }
                    }

                    if (requiresApproval) {
                        nodes.add(TaskNode.withSpecAndApproval(nodeId, name, description, deps,
                                specRef, specScope, acceptanceCriteria));
                    } else if (specRef != null) {
                        nodes.add(TaskNode.withSpec(nodeId, name, description, deps,
                                specRef, specScope, acceptanceCriteria));
                    } else {
                        nodes.add(TaskNode.execute(nodeId, name, description, deps));
                    }
                }

                return nodes;

            } catch (Exception e) {
                String preview = json.substring(0, Math.min(200, json.length()));
                throw new RuntimeException("解析 DAG JSON 失败: " + preview, e);
            }
        }

        /**
         * 从 LLM 响应中提取 JSON 字符串
         * 处理 markdown code block 包装
         */
        public static String extractJson(String response) {
            String json = response.trim();

            // 去掉 markdown code block 包装
            if (json.contains("```")) {
                Matcher m = Pattern.compile("```(?:json)?\\s*\\n?([\\s\\S]*?)```").matcher(json);
                if (m.find()) {
                    json = m.group(1).trim();
                }
            }

            // 确保是合法的 JSON 数组或对象
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start < 0 || end < 0 || end <= start) {
                // 尝试对象格式
                start = json.indexOf('{');
                end = json.lastIndexOf('}');
            }
            if (start < 0 || end < 0 || end <= start) {
                throw new RuntimeException("无法解析 JSON: " + json.substring(0, Math.min(200, json.length())));
            }

            return json.substring(start, end + 1);
        }
    }
}
