---
name: sdd-process
description: SDD（Spec-Driven Development）全流程规范
category: process
roles:
  - planner
  - architect
  - reviewer
  - worker
---

# SDD 全流程规范（Spec-Driven Development）

## 核心理念
先写 Spec，再写代码。Spec 是需求与实现之间的契约。

## 9 阶段流程

### 阶段 1：analyze（需求分析）
- 输入：用户原始需求
- 输出：结构化分析（功能拆解、技术难点、依赖关系、工作量评估）
- 角色：planner

### 阶段 2：architecture（架构设计）
- 输入：需求分析结果
- 输出：服务拆分、依赖关系图、技术选型
- 角色：architect

### 阶段 3：specAuthor（Spec 编写）
- 输入：架构设计 + 子任务列表
- 输出：每个子任务的详细 Spec（接口方法、验收标准、错误码）
- 角色：architect

### 阶段 4：specReview（Spec 审查）【质量门】
- 中断点：等待人工审查 Spec
- 检查：必填章节是否完整、字段类型是否合法、错误码是否定义

### 阶段 5：plan（DAG 规划）
- 输入：审查通过的 Spec
- 输出：DAG 任务图（节点、依赖、优先级）
- 角色：planner

### 阶段 6：approval（审批）【质量门】
- 中断点：等待人工审批 DAG
- 审批通过 → 进入执行阶段
- 审批驳回 → 回到 plan 阶段重新规划

### 阶段 7-8：schedule → dispatch → collect（执行循环）
- 拓扑排序 → 就绪任务派发到 Redis Stream → Worker 执行 → 收集结果
- 循环直到所有子任务完成

### 阶段 9：review → securityReview → test → integrationTest → accept
- 代码审查（VERDICT 协议）→ 安全审查 → 单元测试 → 集成测试 → 验收

## 关键约束
1. Spec 必须在代码之前完成
2. 每个子任务绑定至少一个 Spec
3. 三个质量门禁不可跳过
4. 审批驳回必须带原因，重新规划时参考驳回原因
