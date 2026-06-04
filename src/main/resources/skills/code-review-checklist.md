---
name: code-review-checklist
description: 代码审查标准清单（critical and skeptical 态度）
category: checklist
roles:
  - reviewer
---

# 代码审查清单

## 审查态度
对每个函数都假设它有问题，直到证明它没问题。
不要被表面的代码整洁所迷惑，深入检查逻辑正确性。
宁可严格也不要宽松，遗漏问题的代价远大于误报。

## 维度一：接口一致性
- [ ] 方法签名（名称、参数类型、返回值）是否与 Spec 完全一致
- [ ] 参数顺序是否与 Spec 定义一致
- [ ] 返回值包装（ResponseEntity / Result）是否统一
- [ ] Spec 中定义的错误码是否都有对应的处理分支

## 维度二：边界条件
- [ ] null 入参是否处理（@NotNull 或手动校验）
- [ ] 空集合是否处理（Collections.emptyList 场景）
- [ ] 数值边界：负数、零、Integer.MAX_VALUE、精度丢失
- [ ] 字符串边界：空串、超长、特殊字符、SQL 注入
- [ ] 并发场景：竞态条件、重复请求、幂等性

## 维度三：类型安全
- [ ] 是否有不安全的强制类型转换
- [ ] 泛型擦除后是否丢失类型信息
- [ ] 金额是否使用 BigDecimal（禁止 double/float）
- [ ] 日期时间是否使用 java.time（禁止 Date/SimpleDateFormat）

## 维度四：架构合理性
- [ ] 是否遵循项目现有分层（Controller → Service → Repository）
- [ ] 命名规范是否与项目一致
- [ ] 异常处理模式是否统一（全局异常处理器 vs 局部 try-catch）
- [ ] 日志级别是否合理（ERROR=系统故障, WARN=业务异常, INFO=关键流程, DEBUG=调试）

## 维度五：测试有效性
- [ ] 核心路径是否有测试覆盖
- [ ] 异常路径是否有测试覆盖
- [ ] 测试是否验证了行为（而非仅验证不抛异常）
- [ ] Mock 是否过度（过度 Mock 的测试等于没测）

## VERDICT 输出格式
最后一行必须是：VERDICT: PASS 或 VERDICT: FAIL
如果 FAIL，每个问题必须包含：
- 位置（文件:行号）
- 实际行为（代码做了什么）
- 预期行为（应该怎么做）
- 根因分析（为什么会这样）
