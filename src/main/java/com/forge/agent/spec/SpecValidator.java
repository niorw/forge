package com.forge.agent.spec;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Spec 校验器
 * 在 specReview 节点自动校验 Spec 的完整性和正确性。
 */
@Component
public class SpecValidator {

    // ==================== 常量定义 ====================

    /** 必须包含的章节标题 */
    private static final List<String> REQUIRED_SECTIONS = List.of(
            "# ",          // 一级标题
            "## 用户故事",
            "## 依赖关系",
            "## 接口方法",
            "## 验收标准"
    );

    /** 合法的字段类型 */
    private static final Set<String> LEGAL_TYPES = Set.of(
            "String", "int", "long", "BigDecimal", "boolean",
            "LocalDateTime", "List", "Map"
    );

    /** AC 编号正则：AC-xxx */
    private static final Pattern AC_PATTERN = Pattern.compile("AC-(\\d+)");

    /** 方法名标题正则：### 方法名 */
    private static final Pattern METHOD_PATTERN = Pattern.compile("###\\s+(.+)");

    /** 请求/响应参数表正则：| 字段 | 类型 | 必填 | 说明 | */
    private static final Pattern TABLE_HEADER_PATTERN = Pattern.compile(
            "\\|\\s*字段\\s*\\|\\s*类型\\s*\\|.*?\\|.*?\\|"
    );

    /** 表格行正则 */
    private static final Pattern TABLE_ROW_PATTERN = Pattern.compile(
            "\\|\\s*([^|]+)\\|\\s*([^|]+)\\|\\s*([^|]+)\\|\\s*([^|]+)\\|"
    );

    // ==================== 公开方法 ====================

    /**
     * 校验 Spec 内容
     *
     * @param specContent Spec 文本内容
     * @return 校验结果
     */
    public ValidationResult validate(String specContent) {
        if (specContent == null || specContent.isBlank()) {
            return new ValidationResult(false,
                    List.of("Spec 内容不能为空"),
                    List.of(),
                    0
            );
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int totalScore = 0;

        // 1. 结构完整性检查（40分）
        int structureScore = validateStructure(specContent, errors, warnings);
        totalScore += structureScore;

        // 2. 接口方法完整性检查（25分）
        int methodScore = validateMethods(specContent, errors, warnings);
        totalScore += methodScore;

        // 3. 验收标准完整性检查（15分）
        int acScore = validateAcceptanceCriteria(specContent, errors, warnings);
        totalScore += acScore;

        // 4. 字段类型合法性检查（10分）
        int typeScore = validateFieldTypes(specContent, errors, warnings);
        totalScore += typeScore;

        // 5. 安全要求检查（10分）
        int securityScore = validateSecurity(specContent, errors, warnings);
        totalScore += securityScore;

        boolean valid = errors.isEmpty();
        return new ValidationResult(valid, List.copyOf(errors), List.copyOf(warnings), totalScore);
    }

    /**
     * 校验 Spec，如果无效则抛出异常
     *
     * @param specContent Spec 文本内容
     * @throws SpecValidationException 校验不通过时抛出
     */
    public void validateOrThrow(String specContent) {
        ValidationResult result = validate(specContent);
        if (!result.valid()) {
            throw new SpecValidationException(result);
        }
    }

    // ==================== 结构完整性检查（40分） ====================

    /**
     * 检查必须包含的章节是否齐全
     */
    private int validateStructure(String spec, List<String> errors, List<String> warnings) {
        int found = 0;
        for (String section : REQUIRED_SECTIONS) {
            if (spec.contains(section)) {
                found++;
            } else {
                errors.add("缺少必须章节: " + section.trim());
            }
        }
        // 每个章节 8 分
        return found * 8;
    }

    // ==================== 接口方法完整性检查（25分） ====================

    /**
     * 检查每个方法是否包含请求参数表、响应参数表、错误码表
     */
    private int validateMethods(String spec, List<String> errors, List<String> warnings) {
        // 查找接口方法章节
        int methodSectionStart = spec.indexOf("## 接口方法");
        if (methodSectionStart < 0) {
            return 0; // 没有接口方法章节，已在结构检查中报错
        }

        // 查找下一个顶级章节（## 开头，但不是接口方法本身）
        String afterMethod = spec.substring(methodSectionStart + 6);
        int nextSection = afterMethod.indexOf("\n## ");
        String methodContent = nextSection >= 0 ? afterMethod.substring(0, nextSection) : afterMethod;

        // 查找所有 ### 方法名
        Matcher methodMatcher = METHOD_PATTERN.matcher(methodContent);
        List<String> methods = new ArrayList<>();
        while (methodMatcher.find()) {
            methods.add(methodMatcher.group(1).trim());
        }

        if (methods.isEmpty()) {
            warnings.add("接口方法章节中未找到任何方法定义（### 方法名）");
            return 0;
        }

        int score = 0;
        int perMethodScore = 25 / methods.size();

        for (String method : methods) {
            // 在方法内容中查找该方法的区域
            int methodIdx = methodContent.indexOf("### " + method);
            if (methodIdx < 0) continue;

            String methodArea = methodContent.substring(methodIdx);

            // 检查是否有请求参数表
            boolean hasRequest = methodArea.contains("请求参数") || methodArea.contains("入参");
            // 检查是否有响应参数表
            boolean hasResponse = methodArea.contains("响应参数") || methodArea.contains("返回值") || methodArea.contains("出参");
            // 检查是否有错误码表
            boolean hasErrorCode = methodArea.contains("错误码") || methodArea.contains("异常码");

            if (hasRequest && hasResponse && hasErrorCode) {
                score += perMethodScore;
            } else {
                if (!hasRequest) errors.add("方法 [" + method + "] 缺少请求参数表");
                if (!hasResponse) errors.add("方法 [" + method + "] 缺少响应参数表");
                if (!hasErrorCode) warnings.add("方法 [" + method + "] 建议补充错误码表");
                // 部分得分
                if (hasRequest || hasResponse) score += perMethodScore / 2;
            }
        }

        return score;
    }

    // ==================== 验收标准完整性检查（15分） ====================

    /**
     * 检查 AC 编号的完整性和连续性
     */
    private int validateAcceptanceCriteria(String spec, List<String> errors, List<String> warnings) {
        Matcher matcher = AC_PATTERN.matcher(spec);
        List<Integer> acNumbers = new ArrayList<>();
        while (matcher.find()) {
            acNumbers.add(Integer.parseInt(matcher.group(1)));
        }

        if (acNumbers.isEmpty()) {
            warnings.add("未找到验收标准（AC-xxx）");
            return 0;
        }

        // 检查重复
        Set<Integer> seen = new HashSet<>();
        Set<Integer> duplicates = new HashSet<>();
        for (int num : acNumbers) {
            if (!seen.add(num)) {
                duplicates.add(num);
            }
        }
        if (!duplicates.isEmpty()) {
            errors.add("AC 编号重复: " + duplicates);
        }

        // 检查跳号
        Collections.sort(acNumbers);
        List<Integer> missing = new ArrayList<>();
        for (int i = acNumbers.get(0); i < acNumbers.get(acNumbers.size() - 1); i++) {
            if (!acNumbers.contains(i)) {
                missing.add(i);
            }
        }
        if (!missing.isEmpty()) {
            errors.add("AC 编号跳号，缺少: " + missing.stream().map(n -> "AC-" + n).collect(Collectors.joining(", ")));
        }

        // 无错误则满分
        return duplicates.isEmpty() && missing.isEmpty() ? 15 : 5;
    }

    // ==================== 字段类型合法性检查（10分） ====================

    /**
     * 检查表格中字段类型是否合法，必填标注是否明确
     */
    private int validateFieldTypes(String spec, List<String> errors, List<String> warnings) {
        // 查找所有表格行（跳过表头和分隔行）
        String[] lines = spec.split("\n");
        int invalidTypes = 0;
        int ambiguousRequired = 0;
        int totalRows = 0;

        for (String line : lines) {
            // 匹配表格数据行（至少有4列）
            Matcher rowMatcher = TABLE_ROW_PATTERN.matcher(line);
            if (rowMatcher.find()) {
                String type = rowMatcher.group(2).trim();
                String required = rowMatcher.group(3).trim();

                // 跳过表头行和分隔行
                if (type.equals("类型") || type.startsWith("-") || type.startsWith(":")) {
                    continue;
                }

                totalRows++;

                // 检查类型合法性（支持泛型如 List<String>）
                String baseType = type.contains("<") ? type.substring(0, type.indexOf('<')).trim() : type;
                if (!LEGAL_TYPES.contains(baseType)) {
                    invalidTypes++;
                    warnings.add("不合法的字段类型: " + type);
                }

                // 检查必填标注
                if (!required.equals("是") && !required.equals("否")) {
                    ambiguousRequired++;
                    warnings.add("必填字段标注不明确: " + required + "（应为 是/否）");
                }
            }
        }

        if (totalRows == 0) {
            warnings.add("未找到字段定义表格");
            return 0;
        }

        // 按错误比例扣分
        int score = 10;
        if (invalidTypes > 0) score -= Math.min(5, invalidTypes * 2);
        if (ambiguousRequired > 0) score -= Math.min(3, ambiguousRequired);
        return Math.max(0, score);
    }

    // ==================== 安全要求检查（10分） ====================

    /**
     * 检查安全要求章节是否包含 SQL 注入、越权、脱敏等要求
     */
    private int validateSecurity(String spec, List<String> errors, List<String> warnings) {
        int securityIdx = spec.indexOf("## 安全要求");
        if (securityIdx < 0) {
            // 没有安全要求章节，给出警告
            warnings.add("建议增加 ## 安全要求 章节");
            return 5; // 不扣太多分，但给警告
        }

        // 查找安全要求章节内容
        String afterSecurity = spec.substring(securityIdx + 8);
        int nextSection = afterSecurity.indexOf("\n## ");
        String securityContent = nextSection >= 0 ? afterSecurity.substring(0, nextSection) : afterSecurity;
        String securityLower = securityContent.toLowerCase();

        int score = 10;
        List<String> missing = new ArrayList<>();

        // 检查 SQL 注入防护
        if (!securityLower.contains("sql") && !securityLower.contains("注入")) {
            missing.add("SQL 注入防护");
            score -= 3;
        }

        // 检查越权防护
        if (!securityLower.contains("越权") && !securityLower.contains("权限") && !securityLower.contains("授权")) {
            missing.add("越权/权限控制");
            score -= 3;
        }

        // 检查数据脱敏
        if (!securityLower.contains("脱敏") && !securityLower.contains("敏感") && !securityLower.contains("mask")) {
            missing.add("数据脱敏要求");
            score -= 2;
        }

        if (!missing.isEmpty()) {
            warnings.add("安全要求章节建议补充: " + String.join(", ", missing));
        }

        return Math.max(0, score);
    }

    // ==================== 内部类 ====================

    /**
     * 校验结果记录
     *
     * @param valid    是否通过校验
     * @param errors   必须修复的错误
     * @param warnings 建议修复的警告
     * @param score    0-100 完整性评分
     */
    public record ValidationResult(
            boolean valid,
            List<String> errors,
            List<String> warnings,
            int score
    ) {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Spec 校验结果 ===\n");
            sb.append("状态: ").append(valid ? "✅ 通过" : "❌ 不通过").append("\n");
            sb.append("评分: ").append(score).append("/100\n");

            if (!errors.isEmpty()) {
                sb.append("\n[错误] 必须修复:\n");
                errors.forEach(e -> sb.append("  ✗ ").append(e).append("\n"));
            }
            if (!warnings.isEmpty()) {
                sb.append("\n[警告] 建议修复:\n");
                warnings.forEach(w -> sb.append("  ⚠ ").append(w).append("\n"));
            }
            return sb.toString();
        }
    }

    /**
     * Spec 校验异常
     */
    public static class SpecValidationException extends RuntimeException {
        private final ValidationResult result;

        public SpecValidationException(ValidationResult result) {
            super("Spec 校验未通过，评分: " + result.score() + "/100\n" + result);
            this.result = result;
        }

        public ValidationResult getResult() {
            return result;
        }
    }
}
