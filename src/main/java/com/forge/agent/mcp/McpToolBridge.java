package com.forge.agent.mcp;

import com.forge.agent.tool.ToolRegistry;
import com.forge.agent.tool.ToolResult;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP → ToolRegistry 桥接器
 *
 * 核心理念（来自 Anthropic Harness Engineering）：
 * "一个独立的多智能体编排器只是一个工具。但当它与 MCP 生态深度集成后，
 *  它变成了一个平台。MCP Hub 不是静态的推荐页面——它是实际安装环境的实时镜像。"
 *
 * 职责：
 * 1. 从 Spring AI MCP Client 自动发现所有 MCP Server 提供的工具
 * 2. 将 MCP 工具桥接到 ToolRegistry，使其可被 Agent 调用
 * 3. MCP 工具按 Server 名称分组（toolset = "mcp-{serverName}"）
 * 4. 桥接后的工具自动纳入角色权限体系
 *
 * 调用链路：
 *   Agent → ToolRegistry.execute("mcp_github_create_issue", args, ctx)
 *        → McpToolBridge handler
 *        → MCP Client.callTool("create_issue", args)
 *        → GitHub MCP Server
 *        → 返回结果
 *
 * 配置方式（application.yml）：
 *   spring.ai.mcp.client.stdio.servers.github.command=npx
 *   spring.ai.mcp.client.stdio.servers.github.args=-y,@modelcontextprotocol/server-github
 */
@Component
public class McpToolBridge {

    private static final Logger log = LoggerFactory.getLogger(McpToolBridge.class);

    private final ObjectProvider<List<McpSyncClient>> mcpClientsProvider;
    private final ToolRegistry toolRegistry;

    public McpToolBridge(ObjectProvider<List<McpSyncClient>> mcpClientsProvider, ToolRegistry toolRegistry) {
        this.mcpClientsProvider = mcpClientsProvider;
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    public void bridgeMcpTools() {
        List<McpSyncClient> mcpClients = mcpClientsProvider.getIfAvailable();
        if (mcpClients == null || mcpClients.isEmpty()) {
            log.info("未发现 MCP Server，跳过 MCP 工具桥接");
            return;
        }

        int totalBridged = 0;

        for (McpSyncClient client : mcpClients) {
            String serverName = client.getServerInfo() != null
                    ? client.getServerInfo().name()
                    : "unknown";

            try {
                // 从 MCP Server 获取工具列表
                McpSchema.ListToolsResult listResult = client.listTools();
                List<McpSchema.Tool> tools = listResult.tools();

                String toolset = "mcp-" + serverName.toLowerCase().replaceAll("[^a-z0-9-]", "-");

                for (McpSchema.Tool mcpTool : tools) {
                    String toolName = "mcp_" + serverName.toLowerCase() + "_" + mcpTool.name();

                    // 桥接 MCP 工具到 ToolRegistry
                    toolRegistry.register(toolName,
                            (args, ctx) -> callMcpTool(client, mcpTool, args),
                            new ToolRegistry.ToolMetadata(
                                    toolName,
                                    mcpTool.description() != null ? mcpTool.description() : "MCP tool: " + mcpTool.name(),
                                    toolset,
                                    new String[0]
                            ));

                    totalBridged++;
                }

                log.info("MCP Server [{}] 桥接完成: {} 个工具 (toolset={})",
                        serverName, tools.size(), toolset);

            } catch (Exception e) {
                log.warn("MCP Server [{}] 桥接失败: {}", serverName, e.getMessage());
            }
        }

        log.info("MCP 工具桥接完成: 共 {} 个工具, 来自 {} 个 Server",
                totalBridged, mcpClients.size());
    }

    /**
     * 调用 MCP 工具
     */
    private ToolResult callMcpTool(McpSyncClient client, McpSchema.Tool mcpTool,
                                    Map<String, Object> args) {
        try {
            log.debug("调用 MCP 工具: {} (server={})", mcpTool.name(),
                    client.getServerInfo().name());

            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(mcpTool.name(), args));

            // MCP 返回的内容可能包含多种类型（text, image, etc.）
            StringBuilder output = new StringBuilder();
            if (result.content() != null) {
                for (McpSchema.Content content : result.content()) {
                    if (content instanceof McpSchema.TextContent textContent) {
                        output.append(textContent.text());
                    } else {
                        output.append("[非文本内容: ").append(content.getClass().getSimpleName()).append("]");
                    }
                }
            }

            String outputStr = output.toString();
            if (result.isError() != null && result.isError()) {
                return ToolResult.fail(outputStr, "MCP 工具执行失败: " + mcpTool.name());
            }

            return ToolResult.ok(outputStr);

        } catch (Exception e) {
            log.error("MCP 工具调用异常: {} - {}", mcpTool.name(), e.getMessage());
            return ToolResult.fail("MCP 工具调用异常: " + e.getMessage());
        }
    }
}
