package com.forge.agent.review;

import java.util.Collections;
import java.util.List;

/**
 * VERDICT 协议 — 结构化审查裁决。
 *
 * 核心理念（来自 Anthropic Harness Engineering）：
 * "将主观的'代码看起来还行'转化为二元的 PASS/FAIL 信号，
 *  使得流水线可以基于这个信号做出自动化决策。"
 *
 * 输出格式：
 *   VERDICT: PASS
 *   summary: 代码质量良好，接口签名与 Spec 一致
 *
 *   VERDICT: FAIL
 *   summary: 发现 2 个问题
 *   findings:
 *     - location: OrderService.java:42
 *       severity: HIGH
 *       category: LOGIC
 *       actual: 未处理 null 入参
 *       expected: 应抛出 IllegalArgumentException
 *       rootCause: 缺少参数校验
 *
 * 设计要点：
 * 1. 只审不改 — Verdict 不包含修复代码，只描述问题
 * 2. 三要素 — 每个 Finding 必须包含 location + actual + expected
 * 3. 结构化 — 便于自动化决策（PASS/FAIL 二元信号）
 * 4. 可追溯 — 包含审查维度和置信度
 */
public record Verdict(
        /** 裁决结果：PASS 或 FAIL */
        Decision decision,
        /** 审查摘要（一句话） */
        String summary,
        /** 发现的问题列表（PASS 时为空） */
        List<Finding> findings,
        /** 审查维度：CODE_QUALITY / SECURITY / BOTH */
        ReviewDimension dimension,
        /** 审查置信度：HIGH / MEDIUM / LOW */
        Confidence confidence
) {

    // ==================== 枚举 ====================

    public enum Decision { PASS, FAIL }

    public enum ReviewDimension { CODE_QUALITY, SECURITY, BOTH }

    public enum Confidence { HIGH, MEDIUM, LOW }

    public enum Severity { CRITICAL, HIGH, MEDIUM, LOW }

    /**
     * 问题分类 — 覆盖代码质量和安全两个维度
     */
    public enum Category {
        // 代码质量类
        LOGIC("逻辑错误"),
        INTERFACE("接口不一致"),
        TYPE("类型安全"),
        BOUNDARY("边界条件"),
        STYLE("代码风格"),
        TEST("测试覆盖"),
        ARCHITECTURE("架构合理性"),
        PERFORMANCE("性能问题"),

        // 安全类
        SQL_INJECTION("SQL 注入"),
        AUTH_BYPASS("越权访问"),
        DATA_LEAK("敏感信息泄露"),
        XSS("跨站脚本"),
        DEPENDENCY("依赖安全"),
        TIMEOUT_CIRCUIT("超时熔断"),
        INPUT_VALIDATION("输入校验"),
        CRYPTO("加密安全");

        private final String description;
        Category(String description) { this.description = description; }
        public String description() { return description; }
    }

    // ==================== Finding ====================

    /**
     * 单个审查发现 — 必须满足"三要素"：在哪里、什么现象、为什么
     */
    public record Finding(
            /** 位置：文件路径:行号（如 OrderService.java:42） */
            String location,
            /** 严重程度 */
            Severity severity,
            /** 问题分类 */
            Category category,
            /** 实际行为 */
            String actual,
            /** 预期行为 */
            String expected,
            /** 根因分析（供 Debugger 快速定位） */
            String rootCause
    ) {}

    // ==================== 工厂方法 ====================

    /** 审查通过 */
    public static Verdict pass(String summary, ReviewDimension dimension) {
        return new Verdict(Decision.PASS, summary, Collections.emptyList(), dimension, Confidence.HIGH);
    }

    /** 审查通过（带置信度） */
    public static Verdict pass(String summary, ReviewDimension dimension, Confidence confidence) {
        return new Verdict(Decision.PASS, summary, Collections.emptyList(), dimension, confidence);
    }

    /** 审查失败 */
    public static Verdict fail(String summary, List<Finding> findings, ReviewDimension dimension) {
        return new Verdict(Decision.FAIL, summary, findings, dimension, Confidence.HIGH);
    }

    /** 审查失败（带置信度） */
    public static Verdict fail(String summary, List<Finding> findings, ReviewDimension dimension, Confidence confidence) {
        return new Verdict(Decision.FAIL, summary, findings, dimension, confidence);
    }

    // ==================== 便利方法 ====================

    public boolean isPass() { return decision == Decision.PASS; }
    public boolean isFail() { return decision == Decision.FAIL; }

    /** 获取 CRITICAL + HIGH 级别的问题数 */
    public int criticalFindingsCount() {
        return (int) findings.stream()
                .filter(f -> f.severity() == Severity.CRITICAL || f.severity() == Severity.HIGH)
                .count();
    }

    /**
     * 格式化为人类可读的审查报告
     */
    public String toReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("VERDICT: ").append(decision.name()).append("\n");
        sb.append("维度: ").append(dimension.name()).append("\n");
        sb.append("置信度: ").append(confidence.name()).append("\n");
        sb.append("摘要: ").append(summary).append("\n");

        if (!findings.isEmpty()) {
            sb.append("\n发现 ").append(findings.size()).append(" 个问题：\n");
            for (int i = 0; i < findings.size(); i++) {
                Finding f = findings.get(i);
                sb.append(String.format("\n[%d] %s | %s | %s\n", i + 1, f.severity().name(), f.category().description(), f.location()));
                sb.append("    实际: ").append(f.actual()).append("\n");
                sb.append("    预期: ").append(f.expected()).append("\n");
                sb.append("    根因: ").append(f.rootCause()).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 从 LLM 原始文本响应解析 Verdict
     * 支持 "VERDICT: PASS" 和 "VERDICT: FAIL" 格式
     */
    public static Verdict parseFromText(String llmResponse, ReviewDimension dimension) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return fail("LLM 返回空响应", Collections.emptyList(), dimension, Confidence.LOW);
        }

        String upper = llmResponse.toUpperCase();

        if (upper.contains("VERDICT: PASS") || upper.contains("VERDICT:PASS")) {
            // 提取摘要：取 VERDICT 行之后的第一段非空文本
            String summary = extractSummary(llmResponse);
            return pass(summary, dimension);
        }

        if (upper.contains("VERDICT: FAIL") || upper.contains("VERDICT:FAIL")) {
            String summary = extractSummary(llmResponse);
            List<Finding> findings = extractFindings(llmResponse);
            return fail(summary, findings, dimension);
        }

        // 无法识别 VERDICT 标记，尝试从内容推断
        if (upper.contains("PASS") && !upper.contains("FAIL")) {
            return pass("LLM 未输出标准 VERDICT 标记，但从内容推断为 PASS", dimension, Confidence.LOW);
        }

        return fail("LLM 未输出标准 VERDICT 标记，从内容推断为 FAIL",
                Collections.emptyList(), dimension, Confidence.LOW);
    }

    /** 从 LLM 响应中提取摘要 */
    private static String extractSummary(String text) {
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim().toUpperCase();
            if (line.startsWith("VERDICT:")) {
                // 取 VERDICT 行后面的第一个非空行作为摘要
                for (int j = i + 1; j < lines.length; j++) {
                    String next = lines[j].trim();
                    if (!next.isEmpty() && !next.startsWith("-") && !next.startsWith("*")) {
                        return next;
                    }
                }
            }
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    /** 从 LLM 响应中提取 Finding 列表（尽力解析） */
    private static List<Finding> extractFindings(String text) {
        // 简单提取：找包含 "位置" / "location" / "文件" 等关键词的行
        List<Finding> findings = new java.util.ArrayList<>();
        String[] lines = text.split("\n");
        String currentLocation = "未知位置";
        Severity currentSeverity = Severity.MEDIUM;
        StringBuilder currentActual = new StringBuilder();
        StringBuilder currentExpected = new StringBuilder();
        StringBuilder currentRootCause = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            String lower = trimmed.toLowerCase();

            if (lower.contains("位置") || lower.contains("location") || lower.contains(".java:") || lower.contains(".xml:")) {
                // 保存上一个 finding
                if (currentActual.length() > 0) {
                    findings.add(new Finding(currentLocation, currentSeverity, Category.LOGIC,
                            currentActual.toString().trim(), currentExpected.toString().trim(),
                            currentRootCause.toString().trim()));
                    currentActual = new StringBuilder();
                    currentExpected = new StringBuilder();
                    currentRootCause = new StringBuilder();
                }
                currentLocation = trimmed.replaceAll("^[\\-\\*\\d.\\)]+\\s*", "");
            } else if (lower.contains("严重") || lower.contains("critical") || lower.contains("high")) {
                if (lower.contains("critical")) currentSeverity = Severity.CRITICAL;
                else if (lower.contains("high")) currentSeverity = Severity.HIGH;
                else if (lower.contains("low")) currentSeverity = Severity.LOW;
            } else if (lower.contains("实际") || lower.contains("actual") || lower.contains("现象")) {
                currentActual.append(trimmed.replaceAll("^[\\-\\*\\d.\\)]+\\s*", "")).append(" ");
            } else if (lower.contains("预期") || lower.contains("expected") || lower.contains("应该")) {
                currentExpected.append(trimmed.replaceAll("^[\\-\\*\\d.\\)]+\\s*", "")).append(" ");
            } else if (lower.contains("根因") || lower.contains("原因") || lower.contains("rootcause")) {
                currentRootCause.append(trimmed.replaceAll("^[\\-\\*\\d.\\)]+\\s*", "")).append(" ");
            }
        }

        // 保存最后一个 finding
        if (currentActual.length() > 0) {
            findings.add(new Finding(currentLocation, currentSeverity, Category.LOGIC,
                    currentActual.toString().trim(), currentExpected.toString().trim(),
                    currentRootCause.toString().trim()));
        }

        return findings;
    }
}
