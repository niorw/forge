package com.forge.agent.config;

import org.springframework.context.annotation.Configuration;

/**
 * OpenTelemetry 配置类
 *
 * 当前使用 Micrometer + Prometheus 作为主要监控方案。
 * 如需 OTel 链路追踪，在 pom.xml 添加以下依赖后启用：
 *
 * <pre>
 * &lt;dependency&gt;
 *     &lt;groupId&gt;io.opentelemetry&lt;/groupId&gt;
 *     &lt;artifactId&gt;opentelemetry-api&lt;/artifactId&gt;
 * &lt;/dependency&gt;
 * &lt;dependency&gt;
 *     &lt;groupId&gt;io.opentelemetry&lt;/groupId&gt;
 *     &lt;artifactId&gt;opentelemetry-sdk&lt;/artifactId&gt;
 * &lt;/dependency&gt;
 * &lt;dependency&gt;
 *     &lt;groupId&gt;io.opentelemetry&lt;/groupId&gt;
 *     &lt;artifactId&gt;opentelemetry-exporter-otlp&lt;/artifactId&gt;
 * &lt;/dependency&gt;
 * </pre>
 */
@Configuration
public class OpenTelemetryConfig {
    // OTel 链路追踪暂未启用
    // 当前监控通过 AgentMetrics (Micrometer Counter/Timer) + Prometheus 覆盖
}
