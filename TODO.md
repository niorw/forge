# forge 待办清单

> 基于 SDD + DAG + LangGraph4j 的微服务开发 Agent 平台
> 目标：一个微服务从需求到上线，从 2 周缩短到 2 天

---

## ✅ Phase 1 — 对抗性架构（已完成）

- [x] **Verdict 协议** — 结构化 PASS/FAIL 裁决，16 种问题分类，4 级严重程度
- [x] **独立 ReviewerAgent** — 重构 ReviewAction，VERDICT 协议 + 独立模型路由
- [x] **SecurityReviewAction** — 独立安全审查（SQL注入/越权/脱敏/熔断/输入校验/并发安全）
- [x] **工具权限隔离** — ToolRegistry 角色级黑名单，reviewer 禁止 write/edit/git
- [x] **文件快照 Diff** — WorkerExecutionPipeline 前后快照，精确识别变更文件
- [x] **MainAgentGraph 升级** — 16 节点，review → securityReview 审查链路

## ✅ Phase 1.5 — MCP × Skill 集成（已完成）

### Skill 知识注入
- [x] **SkillRegistry** — 注册中心，YAML frontmatter 解析，角色适用过滤
- [x] **6 个核心 Skill** — sdd-process / code-review-checklist / security-checklist / java-naming-convention / api-design-guide / gmcf-business-context
- [x] **ContextBuilder 注入** — buildSystemPrompt() 自动注入角色适用的 Skill 知识
- [x] **SkillConfig** — 启动自动加载 + 外部目录覆盖

### MCP Server 集成
- [x] **spring-ai-starter-mcp-client** — pom.xml 依赖
- [x] **McpToolBridge** — MCP → ToolRegistry 桥接，自动发现 McpSyncClient
- [x] **application.yml** — MCP Server 配置模板（GitHub/MySQL/Jenkins）
- [x] **ToolRegistry 角色权限** — MCP toolset 纳入权限体系

### Skill 版本管理
- [x] **SkillVersionManager** — WatchService 文件监控 + 定时 Git pull + 版本历史
- [x] **SkillController** — REST API（list/detail/content/reload/git-pull/versions/stats）

## ✅ Phase 2 — 可观测性（已完成）

- [x] **AgentEvent** — 21 种事件类型（节点/LLM/工具/任务/审查/审批/管线/系统）
- [x] **AgentEventLog** — 环形缓冲 2000 条 + SSE 广播 + 过滤查询
- [x] **EventStreamController** — SSE 实时事件流 + 历史查询 + 统计
- [x] **SpringAiLlmGateway 埋点** — LLM_REQUEST/RESPONSE/ERROR 事件记录
- [x] **ToolRegistry 埋点** — TOOL_CALL/RESULT/ERROR 事件记录
- [x] **Live Feed 前端** — 暗色主题 + 9 类过滤器 + 色彩编码 + 详情展开

## ✅ Phase 3 — 闭环体验（已完成）

### 对话记忆
- [x] **MainState 扩展** — rejectionReason / previousDAGJson / revisionCount
- [x] **ApprovalAction 重写** — 驳回时自动保存对话记忆（DAG + 原因 + 计数）
- [x] **PlanAction 重写** — 增量修订模式（上一轮 DAG + 驳回原因 → LLM 修改）
- [x] **最大修订 3 次** — 超过则标记失败，需人工介入

### Sprint Board
- [x] **SprintBoardController** — 五列看板 API（Inbox/InProgress/Review/Done/Failed）
- [x] **sprint-board.html** — 暗色看板 + 管线状态条 + 任务卡片 + 10 秒刷新

### 动态规划
- [x] **DynamicPlanController** — INSERT/REMOVE/RETRY/BATCH-INSERT
- [x] **安全约束** — 只能移除 PENDING，被依赖的任务不能移除

## ✅ Phase 4 — 平台化（已完成）

### 多项目支持
- [x] **ProjectRegistry** — 多项目注册中心，项目级 Spec/Prompt/Model 隔离
- [x] **ProjectController** — REST API（list/get/register/delete/setDefault）

### Prompt 管理平台
- [x] **PromptVersionStore** — 版本化 + A/B 测试 + 流量分配 + 使用统计
- [x] **PromptController** — REST API（publish/rollback/history/ab-test）

### Agent 可视化编排
- [x] **agent-orchestrator.html** — 拖拽式 DAG 编辑器 + 节点面板 + 属性面板 + JSON 导出

### 权限与审计
- [x] **AuditLog** — 13 种审计事件类型（AGENT_TRIGGER/APPROVAL/SPEC/PROMPT/PROJECT/TASK/CONFIG）
- [x] **AuditService** — 内存环形缓冲 + 日志文件双写（不可篡改）
- [x] **AuditController** — REST API（query/recent/stats）

### 平台入口
- [x] **index.html** — Dashboard 控制台，9 个功能入口 + 统计摘要

---

## 访问地址

| 页面 | URL |
|------|-----|
| Dashboard | http://localhost:8080/ |
| Sprint Board | http://localhost:8080/sprint-board.html |
| Live Feed | http://localhost:8080/live-feed.html |
| Agent 编排 | http://localhost:8080/agent-orchestrator.html |
| 多项目 API | http://localhost:8080/api/projects |
| Skill API | http://localhost:8080/api/skills |
| Prompt API | http://localhost:8080/api/prompts |
| 事件 API | http://localhost:8080/api/events/stats |
| 审计 API | http://localhost:8080/api/audit |
| 动态规划 API | http://localhost:8080/api/dynamic-plan |

## 设计原则

1. **可插拔** — 所有组件通过接口抽象，实现可替换
2. **可扩展** — 工具/SPI 注册机制，新增能力不改已有代码
3. **Provider 无关** — LLM 供应商可随时切换
4. **Checkpoint 驱动** — 所有状态可持久化、可恢复
5. **Context 感知** — 每次 LLM 调用都有完整上下文（含 Skill 知识注入）
6. **自纠错** — 失败 → 反馈 → 重试，不是直接报错
7. **对抗性** — 生成者与评判者分离，VERDICT 协议驱动质量门禁
8. **可观测** — 全链路事件记录，实时 SSE 推送，飞行记录仪
9. **可退化** — 每个组件都是假设，模型升级后可移除
