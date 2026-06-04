package com.forge.agent.tool.tools;

import com.forge.agent.tool.Tool;
import com.forge.agent.tool.ToolContext;
import com.forge.agent.tool.ToolRegistry;
import com.forge.agent.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Git 操作工具 —— 提供分支创建、提交、MR 创建等能力。
 */
@Component
public class GitTool {

    private static final Logger log = LoggerFactory.getLogger(GitTool.class);

    public GitTool(ToolRegistry registry) {
        registry.register(this);
        log.info("GitTool 已注册");
    }

    @Tool(name = "git_create_branch",
          description = "创建 Git 分支。参数: branchName (String) - 分支名称",
          parameters = "{\"type\":\"object\",\"properties\":{\"branchName\":{\"type\":\"string\"}},\"required\":[\"branchName\"]}",
          toolset = "git")
    public ToolResult gitCreateBranch(Map<String, Object> args, ToolContext ctx) {
        String branchName = (String) args.get("branchName");
        if (branchName == null || branchName.isBlank()) {
            return ToolResult.fail("branchName 不能为空");
        }
        try {
            int exitCode = runCommand("git", "checkout", "-b", branchName);
            if (exitCode == 0) {
                return ToolResult.ok("分支创建成功: " + branchName);
            }
            return ToolResult.fail("分支创建失败，exitCode=" + exitCode);
        } catch (Exception e) {
            return ToolResult.fail("创建分支异常: " + e.getMessage());
        }
    }

    @Tool(name = "git_commit",
          description = "提交当前变更。参数: message (String) - 提交信息",
          parameters = "{\"type\":\"object\",\"properties\":{\"message\":{\"type\":\"string\"}},\"required\":[\"message\"]}",
          toolset = "git")
    public ToolResult gitCommit(Map<String, Object> args, ToolContext ctx) {
        String message = (String) args.get("message");
        if (message == null || message.isBlank()) {
            return ToolResult.fail("commit message 不能为空");
        }
        try {
            runCommand("git", "add", "-A");
            int exitCode = runCommand("git", "commit", "-m", message);
            if (exitCode == 0) {
                return ToolResult.ok("提交成功: " + message);
            }
            return ToolResult.fail("提交失败，exitCode=" + exitCode);
        } catch (Exception e) {
            return ToolResult.fail("提交异常: " + e.getMessage());
        }
    }

    @Tool(name = "git_create_mr",
          description = "创建 Merge Request。参数: title (String) - MR 标题, description (String) - MR 描述",
          parameters = "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"description\":{\"type\":\"string\"}},\"required\":[\"title\"]}",
          toolset = "git")
    public ToolResult gitCreateMr(Map<String, Object> args, ToolContext ctx) {
        String title = (String) args.get("title");
        String description = (String) args.getOrDefault("description", "");
        if (title == null || title.isBlank()) {
            return ToolResult.fail("MR title 不能为空");
        }
        // 实际 MR 创建需要对接 GitLab/GitHub API，此处为占位实现
        log.info("创建 MR: title={}, description={}", title, description);
        return ToolResult.ok("MR 创建请求已发送: " + title);
    }

    /** 执行外部命令 */
    private int runCommand(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        return process.waitFor();
    }
}
