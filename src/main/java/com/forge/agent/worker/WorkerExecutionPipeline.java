package com.forge.agent.worker;

import com.forge.agent.llm.LlmGateway;
import com.forge.agent.mq.TaskMessage;
import com.forge.agent.resilience.RetryExecutor;
import com.forge.agent.resilience.RetryPolicy;
import com.forge.agent.tool.FileSnapshot;
import com.forge.agent.tool.ToolContext;
import com.forge.agent.tool.ToolRegistry;
import com.forge.agent.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Worker 执行管线 —— 串联代码生成 → 编译验证 → 测试验证。
 *
 * 核心改进（来自 Anthropic Harness Engineering）：
 * 1. 文件快照 Diff — 任务前后拍摄快照，精确识别改了哪些文件
 * 2. 带反馈的自纠错 — 编译/测试失败时 LLM 自动修复
 * 3. 工具权限隔离 — Worker 使用完整工具集，但执行上下文标记角色
 *
 * 流程：
 * 1. 拍摄文件快照（before）
 * 2. 调用 LLM 生成代码
 * 3. 写入项目文件
 * 4. RetryExecutor 执行编译，失败时 LLM 自动修复
 * 5. RetryExecutor 执行测试，失败时 LLM 自动修复
 * 6. 拍摄文件快照（after）
 * 7. 计算 Diff 并附加到执行结果
 */
@Component
public class WorkerExecutionPipeline {

    private static final Logger log = LoggerFactory.getLogger(WorkerExecutionPipeline.class);

    private final RetryExecutor retryExecutor;
    private final LlmGateway llmGateway;
    private final ToolRegistry toolRegistry;

    public WorkerExecutionPipeline(RetryExecutor retryExecutor,
                                   LlmGateway llmGateway,
                                   ToolRegistry toolRegistry) {
        this.retryExecutor = retryExecutor;
        this.llmGateway = llmGateway;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 执行完整的 Worker 管线：代码生成 → 编译 → 测试。
     *
     * @param task        任务消息
     * @param projectPath 项目根路径
     * @return 执行结果（含文件 Diff 信息）
     */
    public PipelineResult execute(TaskMessage task, String projectPath) {
        String taskId = task.taskId();
        log.info("[{}] 开始执行管线: projectPath={}", taskId, projectPath);

        // ========== 第零步：拍摄文件快照（before） ==========
        FileSnapshot beforeSnapshot = FileSnapshot.capture(projectPath);
        log.info("[{}] 文件快照(before)拍摄完成: {} 个文件", taskId, beforeSnapshot.toString().length());

        try {
            // ========== 第一步：LLM 生成代码 ==========
            final AtomicReference<String> codeRef = new AtomicReference<>(generateCode(task));
            String code = codeRef.get();
            if (code == null || code.isBlank()) {
                return PipelineResult.fail("代码生成失败：LLM 返回空结果", null);
            }

            // ========== 第二步：写入文件 ==========
            String filePath = resolveFilePath(projectPath, task.name());
            writeFile(filePath, code);
            log.info("[{}] 代码已写入: {}", taskId, filePath);

            // ========== 第三步：编译验证（带自动重试） ==========
            ToolResult compileResult = retryExecutor.executeWithFeedback(
                    "compile-" + taskId,
                    RetryPolicy.defaults(),
                    // 执行动作：编译项目
                    (prevResult, prevError) -> {
                        if (prevError != null) {
                            // 编译失败，用 LLM 修复代码
                            String fixedCode = fixCode(task, codeRef.get(), prevError);
                            writeFile(filePath, fixedCode);
                            codeRef.set(fixedCode);
                        }
                        Map<String, Object> args = Map.of("projectPath", projectPath);
                        ToolContext ctx = ToolContext.of(taskId, null, "worker");
                        return toolRegistry.execute("compile", args, ctx);
                    },
                    // 失败反馈处理器
                    (result, error, attempt) -> {
                        log.warn("[{}] 编译第 {} 次失败: {}", taskId, attempt, error);
                        return result;
                    }
            );

            if (!compileResult.success()) {
                String diff = captureDiff(beforeSnapshot, projectPath, taskId);
                return PipelineResult.fail("编译失败: " + compileResult.error(), diff);
            }
            log.info("[{}] 编译通过", taskId);

            // ========== 第四步：测试验证（带自动重试） ==========
            ToolResult testResult = retryExecutor.executeWithFeedback(
                    "test-" + taskId,
                    RetryPolicy.defaults(),
                    // 执行动作：运行测试
                    (prevResult, prevError) -> {
                        if (prevError != null) {
                            // 测试失败，用 LLM 修复代码
                            String fixedCode = fixCode(task, codeRef.get(), prevError);
                            writeFile(filePath, fixedCode);
                            codeRef.set(fixedCode);
                        }
                        Map<String, Object> args = Map.of("projectPath", projectPath);
                        ToolContext ctx = ToolContext.of(taskId, null, "worker");
                        return toolRegistry.execute("test", args, ctx);
                    },
                    // 失败反馈处理器
                    (result, error, attempt) -> {
                        log.warn("[{}] 测试第 {} 次失败: {}", taskId, attempt, error);
                        return result;
                    }
            );

            // ========== 第五步：拍摄文件快照（after）+ 计算 Diff ==========
            String diff = captureDiff(beforeSnapshot, projectPath, taskId);

            if (!testResult.success()) {
                return PipelineResult.fail("测试失败: " + testResult.error(), diff);
            }
            log.info("[{}] 测试通过", taskId);

            String resultText = "任务完成: " + task.name() + "\n" + testResult.output();
            return PipelineResult.success(resultText, diff);

        } catch (RetryExecutor.RetryExhaustedException e) {
            log.error("[{}] 重试耗尽: {}", taskId, e.getMessage());
            String diff = captureDiff(beforeSnapshot, projectPath, taskId);
            return PipelineResult.fail("重试耗尽: " + e.getMessage(), diff);
        } catch (Exception e) {
            log.error("[{}] 管线执行异常", taskId, e);
            String diff = captureDiff(beforeSnapshot, projectPath, taskId);
            return PipelineResult.fail("执行异常: " + e.getMessage(), diff);
        }
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 拍摄文件快照并计算 Diff
     */
    private String captureDiff(FileSnapshot beforeSnapshot, String projectPath, String taskId) {
        try {
            FileSnapshot afterSnapshot = FileSnapshot.capture(projectPath);
            FileSnapshot.Diff diff = beforeSnapshot.diff(afterSnapshot);
            String diffReport = diff.toReport();
            log.info("[{}] 文件快照 Diff: {} 个变更", taskId, diff.totalChanges());
            return diffReport;
        } catch (Exception e) {
            log.warn("[{}] 文件快照 Diff 计算失败: {}", taskId, e.getMessage());
            return "文件快照 Diff 计算失败: " + e.getMessage();
        }
    }

    /**
     * 调用 LLM 生成代码。
     */
    private String generateCode(TaskMessage task) {
        String prompt = String.format(
                "请根据以下任务描述生成 Java 代码：\n\n任务名称: %s\n任务描述: %s\n\nSpec:\n%s",
                task.name(),
                task.description(),
                task.specContent() != null ? task.specContent() : "无"
        );
        return llmGateway.chatWithRole("worker", "generate-code", prompt);
    }

    /**
     * 调用 LLM 修复编译/测试失败的代码。
     */
    private String fixCode(TaskMessage task, String currentCode, String errorMessage) {
        String prompt = String.format(
                "代码执行失败，请修复。\n\n失败信息:\n%s\n\n当前代码:\n%s\n\n任务描述:\n%s",
                errorMessage,
                currentCode,
                task.description()
        );
        return llmGateway.chatWithRole("worker", "fix-code", prompt);
    }

    /**
     * 写入文件（自动创建目录）。
     */
    private void writeFile(String filePath, String content) {
        try {
            Path path = Path.of(filePath);
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new RuntimeException("写入文件失败: " + filePath, e);
        }
    }

    /**
     * 解析代码文件路径。
     */
    private String resolveFilePath(String projectPath, String taskName) {
        String fileName = taskName.replaceAll("[^a-zA-Z0-9]", "");
        if (fileName.isEmpty()) {
            fileName = "GeneratedCode";
        }
        fileName = Character.toUpperCase(fileName.charAt(0)) + fileName.substring(1);
        return projectPath + "/src/main/java/" + fileName + ".java";
    }

    // ==================== 执行结果 record ====================

    /**
     * 管线执行结果 — 包含执行结果和文件 Diff
     */
    public record PipelineResult(
            boolean success,
            String output,
            String error,
            String fileDiff
    ) {
        public static PipelineResult success(String output, String fileDiff) {
            return new PipelineResult(true, output, null, fileDiff);
        }

        public static PipelineResult fail(String error, String fileDiff) {
            return new PipelineResult(false, null, error, fileDiff);
        }
    }
}
