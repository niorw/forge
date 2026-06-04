# Forge

> **Enterprise-grade Multi-Agent DAG Orchestration Platform**  
> Built with LangGraph4j + Spring AI | SDD-Driven | Production-Ready

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.1-green.svg)](https://spring.io/projects/spring-boot)
[![LangGraph4j](https://img.shields.io/badge/LangGraph4j-1.8.17-blue.svg)](https://github.com/langchain4j/langgraph4j)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0.0-purple.svg)](https://docs.spring.io/spring-ai/reference/)
[![License](https://img.shields.io/badge/License-Apache%202.0-red.svg)](LICENSE)

---

## ✨ 核心特性

### 🎯 多Agent DAG编排
- **有向无环图(DAG)引擎** - 基于 LangGraph4j 的原生图编排
- **拓扑排序执行** - 自动解析任务依赖，保证执行顺序
- **并行任务调度** - 无依赖任务自动并行执行
- **人工审批节点** - `interruptsBefore` 实现人机协作

### 🤖 多模型智能路由
- **双供应商支持** - Anthropic (Claude) + OpenAI (GPT)
- **角色级模型配置** - Planner/Reviewer/Coder 可用不同模型
- **动态模型切换** - 运行时按需切换，无需重启
- **Token 成本追踪** - 实时统计每个任务的 Token 消耗

### 📋 SDD (Spec-Driven Development)
- **9阶段全流程** - 需求→架构→Spec→审批→调度→开发→集成→验证→交付
- **Spec 自动生成** - LLM 驱动的 Business Spec + Technical Spec
- **子任务拆解** - 自动将 Spec 拆解为可执行的子任务 DAG
- **版本化管理** - Spec 变更自动触发 DAG 重编排

### 🔧 生产级基础设施
- **Redis Stream MQ** - 子Agent任务队列，支持消费者组
- **MySQL Checkpoint** - DAG 状态持久化，支持断点恢复
- **Prometheus 指标** - Agent 任务量、Token 消耗、延迟分布
- **Grafana 仪表盘** - 开箱即用的监控面板
- **审计日志** - 全链路操作追溯

---

## 🏗️ 技术栈

| 层级 | 技术 | 说明 |
|------|------|------|
| **运行时** | Java 21 + Spring Boot 3.4 | 虚拟线程 + 响应式编程 |
| **图引擎** | LangGraph4j 1.8 | DAG 编排 + 状态管理 |
| **LLM 集成** | Spring AI 1.0 | 统一多供应商 API |
| **消息队列** | Redis Stream | 子Agent任务调度 |
| **持久化** | MySQL + HikariCP | Checkpoint + 业务数据 |
| **监控** | Prometheus + Grafana | 指标采集 + 可视化 |
| **前端** | 原生 HTML/JS | 工作流监控面板 |

---

## 🚀 快速开始

### 环境要求

- Java 21+
- Redis 6+ (集群或单机)
- MySQL 8.0+
- Maven 3.8+

### 1. 克隆项目

```bash
git clone https://github.com/niorw/forge.git
cd forge
```

### 2. 配置环境变量

```bash
cp .env.example .env
# 编辑 .env 填入实际配置
```

**必须配置的环境变量：**

```bash
# MySQL
DB_HOST=localhost
DB_PORT=3306
DB_NAME=forge
DB_USER=root
DB_PASSWORD=your_password

# Redis
REDIS_NODES=localhost:6379

# LLM API (至少配置一个)
ANTHROPIC_API_KEY=sk-ant-...
# 或
OPENAI_API_KEY=sk-...
```

### 3. 初始化数据库

```bash
mysql -u root -p < src/main/resources/sql/init.sql
```

### 4. 启动应用

```bash
# 开发环境（单机 Redis + 本地 LLM）
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 生产环境
mvn clean package -DskipTests
java -jar target/forge-1.0.0-SNAPSHOT.jar
```

### 5. 访问应用

- **应用首页**: http://localhost:8080
- **工作流监控**: http://localhost:8080/workflow-monitor.html
- **Sprint 看板**: http://localhost:8080/sprint-board.html
- **实时事件流**: http://localhost:8080/live-feed.html
- **健康检查**: http://localhost:8080/actuator/health
- **Prometheus 指标**: http://localhost:8080/actuator/prometheus

---

## 📁 项目结构

```
forge/
├── src/main/java/com/forge/agent/
│   ├── ForgeApplication.java          # 启动类
│   ├── engine/                        # DAG 引擎核心
│   │   ├── MainAgentGraph.java        # LangGraph4j 状态图
│   │   ├── DagEngine.java             # DAG 拓扑排序
│   │   └── actions/                   # 节点动作实现
│   │       ├── AnalyzeAction.java     # 需求分析
│   │       ├── PlanAction.java        # DAG 规划
│   │       ├── SpecAuthorAction.java  # Spec 生成
│   │       ├── ApprovalAction.java    # 人工审批
│   │       ├── DispatchAction.java    # 任务派发
│   │       └── ...
│   ├── llm/                           # LLM 网关
│   │   ├── LlmGateway.java           # 统一接口
│   │   ├── LlmModelRouter.java       # 模型路由
│   │   └── SpringAiLlmGateway.java   # Spring AI 实现
│   ├── gateway/                       # 任务调度
│   │   ├── TaskDispatcher.java       # 调度接口
│   │   └── RedisTaskDispatcher.java  # Redis Stream 实现
│   ├── spec/                          # Spec 管理
│   ├── state/                         # 状态管理
│   ├── event/                         # 事件系统
│   ├── web/                           # REST API
│   └── config/                        # 配置类
│
├── src/main/resources/
│   ├── application.yml                # 主配置
│   ├── application-dev.yml            # 开发环境配置
│   ├── sql/init.sql                   # 数据库初始化
│   ├── static/                        # 前端页面
│   └── monitoring/                    # Grafana 仪表盘
│
├── pom.xml                            # Maven 配置
├── .env.example                       # 环境变量模板
└── README.md                          # 项目文档
```

---

## ⚙️ 配置说明

### 多模型配置

```yaml
agent:
  models:
    planner:
      provider: anthropic              # 或 openai
      model: claude-sonnet-4-20250514
      max-tokens: 2048
    reviewer:
      provider: anthropic
      model: claude-sonnet-4-20250514
      max-tokens: 4096
    coder:
      provider: openai
      model: gpt-4o
      max-tokens: 8192
```

### Redis 集群配置

```yaml
spring:
  data:
    redis:
      cluster:
        nodes: host1:7000,host2:7001,host3:7002
      password: ${REDIS_PASSWORD:}
```

### MySQL Checkpoint

```yaml
spring:
  datasource:
    url: jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai
    username: ${DB_USER}
    password: ${DB_PASSWORD}
```

---

## 📖 使用指南

### 1. 执行需求分析

```bash
curl -X POST http://localhost:8080/api/agent/execute \
  -H "Content-Type: application/json" \
  -d '{
    "requirement": "实现订单创建接口，支持幂等校验和分布式锁",
    "project": "order-service"
  }'
```

### 2. 查看工作流状态

```bash
# 获取任务状态
curl http://localhost:8080/api/workflow/status/{taskId}

# 实时事件流 (SSE)
curl http://localhost:8080/api/workflow/stream/{taskId}
```

### 3. 人工审批

```bash
curl -X POST http://localhost:8080/api/approval/approve \
  -H "Content-Type: application/json" \
  -d '{
    "taskId": "task-001",
    "approved": true,
    "comment": "Spec 审核通过"
  }'
```

### 4. 查看 Spec

```bash
curl http://localhost:8080/api/spec/{taskId}
```

---

## 📊 监控指标

### Prometheus 指标

- `agent_task_total` - 任务总数
- `agent_task_duration_seconds` - 任务耗时分布
- `agent_llm_tokens_total` - Token 消耗总量
- `agent_llm_latency_seconds` - LLM 调用延迟
- `agent_dag_nodes_total` - DAG 节点总数

### Grafana 仪表盘

导入 `src/main/resources/monitoring/` 下的 JSON 文件：

- `grafana-agent-health.json` - Agent 健康度
- `grafana-token-cost.json` - Token 成本分析
- `grafana-agent-value.json` - Agent 价值产出

---

## 🧪 测试

```bash
# 运行所有测试
mvn test

# 运行单个测试类
mvn test -Dtest=DAGOrchestratorTest

# 跳过测试打包
mvn clean package -DskipTests
```

---

## 🤝 贡献指南

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'feat: add amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 创建 Pull Request

### 提交规范

遵循 [Conventional Commits](https://www.conventionalcommits.org/):

- `feat:` 新功能
- `fix:` Bug 修复
- `docs:` 文档更新
- `style:` 代码格式
- `refactor:` 重构
- `test:` 测试
- `chore:` 构建/工具

---

## 📄 许可证

本项目采用 [Apache License 2.0](LICENSE) 许可证。

---

## 🔗 相关链接

- [LangGraph4j 文档](https://github.com/langchain4j/langgraph4j)
- [Spring AI 文档](https://docs.spring.io/spring-ai/reference/)
- [Spring Boot 文档](https://docs.spring.io/spring-boot/docs/current/reference/html/)

---

**Built with ❤️ by [niorw](https://github.com/niorw)**
