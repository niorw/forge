package com.forge.agent.tool.tools;

import com.forge.agent.tool.Tool;
import com.forge.agent.tool.ToolContext;
import com.forge.agent.tool.ToolRegistry;
import com.forge.agent.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 编译验证工具 —— 提供 Maven 编译和测试执行能力。
 */
@Component
public class CompileTool {

    private static final Logger log = LoggerFactory.getLogger(CompileTool.class);

    /** 默认超时（秒） */
    private static final long DEFAULT_TIMEOUT_SECONDS = 120;

    public CompileTool(ToolRegistry registry) {
        registry.register(this);
        log.info("CompileTool 已注册");
    }

    @Tool(name = "compile",
          description = "编译项目。参数: projectPath (String) - 项目根路径",
          parameters = "{\"type\":\"object\",\"properties\":{\"projectPath\":{\"type\":\"string\"}},\"required\":[\"projectPath\"]}",
          toolset = "compile")
    public ToolResult compile(Map<String, Object> args, ToolContext ctx) {
        String projectPath = (String) args.get("projectPath");
        if (projectPath == null || projectPath.isBlank()) {
            return ToolResult.fail("projectPath 不能为空");
        }
        return executeMaven(projectPath, "compile", null);
    }

    @Tool(name = "test",
          description = "执行测试。参数: projectPath (String) - 项目根路径, testClass (String, 可选) - 测试类名",
          parameters = "{\"type\":\"object\",\"properties\":{\"projectPath\":{\"type\":\"string\"},\"testClass\":{\"type\":\"string\"}},\"required\":[\"projectPath\"]}",
          toolset = "compile")
    public ToolResult test(Map<String, Object> args, ToolContext ctx) {
        String projectPath = (String) args.get("projectPath");
        String testClass = (String) args.get("testClass");
        if (projectPath == null || projectPath.isBlank()) {
            return ToolResult.fail("projectPath 不能为空");
        }
        if (testClass != null && !testClass.isBlank()) {
            return executeMaven(projectPath, "test", "-Dtest=" + testClass);
        }
        return executeMaven(projectPath, "test", null);
    }

    /**
     * 执行 Maven 命令。
     *
     * @param projectPath 项目根路径
     * @param goal        Maven 目标（compile / test）
     * @param extraArg    额外参数（可为 null）
     * @return 执行结果
     */
    private ToolResult executeMaven(String projectPath, String goal, String extraArg) {
        try {
            ProcessBuilder pb = new ProcessBuilder("mvn", "-f", projectPath + "/pom.xml", goal,
                    extraArg != null ? extraArg : "-q");
            pb.redirectErrorStream(true);

            log.info("执行 Maven: {} {}", projectPath, goal);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.fail(output.toString(), "Maven 执行超时（" + DEFAULT_TIMEOUT_SECONDS + "秒）");
            }

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                return ToolResult.ok(output.toString());
            }
            return ToolResult.fail(output.toString(), "Maven 执行失败，exitCode=" + exitCode);

        } catch (Exception e) {
            return ToolResult.fail("Maven 执行异常: " + e.getMessage());
        }
    }
}
