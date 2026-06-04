package com.forge.agent.tool;

/**
 * 工具执行结果。
 */
public record ToolResult(
        /** 输出内容 */
        String output,
        /** 是否成功 */
        boolean success,
        /** 错误信息（成功时为 null） */
        String error
) {

    /** 成功结果 */
    public static ToolResult ok(String output) {
        return new ToolResult(output, true, null);
    }

    /** 失败结果 */
    public static ToolResult fail(String error) {
        return new ToolResult(null, false, error);
    }

    /** 带输出的失败结果 */
    public static ToolResult fail(String output, String error) {
        return new ToolResult(output, false, error);
    }
}
