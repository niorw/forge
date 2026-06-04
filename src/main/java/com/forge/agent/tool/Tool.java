package com.forge.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个方法为 Agent 可调用的工具。
 * 被标注的方法会被 ToolRegistry 自动扫描并注册。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Tool {

    /** 工具名称，全局唯一 */
    String name();

    /** 工具描述，供 LLM 理解用途 */
    String description();

    /** 参数的 JSON Schema 字符串 */
    String parameters() default "{}";

    /** 所属工具集名称，默认 "default" */
    String toolset() default "default";

    /** 前置条件检查 —— 返回 null 表示通过，返回非 null 字符串为不可用原因 */
    String checkEnv() default "";
}
