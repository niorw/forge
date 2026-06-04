#!/usr/bin/env python3
"""
Forge 技术方案文档 v2
修正：LangGraph4j 原生编排，去掉自研 DAG 引擎
"""
import pathlib

html = '''<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Forge 技术方案 v2 — LangGraph4j 原生编排</title>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600&family=JetBrains+Mono:wght@400;500&display=swap" rel="stylesheet">
<style>
:root {
  --bg: #08090a; --bg-panel: #0f1011; --bg-surface: #191a1b;
  --text-primary: #f7f8f8; --text-secondary: #d0d6e0;
  --text-tertiary: #8a8f98; --text-muted: #62666d;
  --accent: #7170ff; --accent-bg: #5e6ad2; --accent-hover: #828fff;
  --green: #27a644; --green-emerald: #10b981;
  --red: #ef4444; --orange: #f59e0b;
  --border: rgba(255,255,255,0.08); --border-subtle: rgba(255,255,255,0.05);
  --radius: 8px; --radius-sm: 6px; --radius-lg: 12px;
}
* { margin: 0; padding: 0; box-sizing: border-box; }
body {
  font-family: 'Inter', system-ui, -apple-system, sans-serif;
  background: var(--bg); color: var(--text-primary);
  font-feature-settings: "cv01", "ss03"; line-height: 1.6;
  -webkit-font-smoothing: antialiased;
}
.mono { font-family: 'JetBrains Mono', ui-monospace, monospace; }
nav {
  position: fixed; top: 0; left: 0; right: 0; z-index: 100;
  background: rgba(15,16,17,0.85); backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--border-subtle);
  padding: 0 32px; height: 56px;
  display: flex; align-items: center; justify-content: space-between;
}
nav .logo { font-size: 15px; font-weight: 600; letter-spacing: -0.3px; }
nav .logo span { color: var(--accent); }
nav .links { display: flex; gap: 24px; }
nav .links a {
  font-size: 13px; font-weight: 500; color: var(--text-tertiary);
  text-decoration: none; transition: color 0.15s;
}
nav .links a:hover { color: var(--text-primary); }
.container { max-width: 1200px; margin: 0 auto; padding: 0 32px; }
section { padding: 80px 0; }
section + section { border-top: 1px solid var(--border-subtle); }
.hero { padding: 120px 0 80px; text-align: center; }
.hero h1 {
  font-size: 48px; font-weight: 500; line-height: 1.05;
  letter-spacing: -1.056px; margin-bottom: 20px;
}
.hero .subtitle { font-size: 18px; color: var(--text-tertiary); max-width: 640px; margin: 0 auto 32px; }
.hero .badge {
  display: inline-block; padding: 4px 12px; border-radius: 9999px;
  font-size: 12px; font-weight: 500;
  border: 1px solid var(--border); color: var(--text-secondary); margin-bottom: 24px;
}
h2 { font-size: 32px; font-weight: 500; line-height: 1.13; letter-spacing: -0.704px; margin-bottom: 16px; }
h3 { font-size: 20px; font-weight: 600; line-height: 1.33; letter-spacing: -0.24px; margin: 32px 0 12px; }
h4 { font-size: 16px; font-weight: 600; margin: 24px 0 8px; color: var(--text-secondary); }
p { margin-bottom: 16px; color: var(--text-secondary); }
.lead { font-size: 18px; color: var(--text-tertiary); max-width: 720px; }
.card {
  background: rgba(255,255,255,0.02); border: 1px solid var(--border);
  border-radius: var(--radius); padding: 24px;
}
.card-grid { display: grid; gap: 16px; }
.card-grid-2 { grid-template-columns: repeat(2, 1fr); }
.card-grid-3 { grid-template-columns: repeat(3, 1fr); }
.card h3 { margin-top: 0; font-size: 17px; }
.card p { font-size: 14px; margin-bottom: 0; }
.stat {
  background: rgba(255,255,255,0.02); border: 1px solid var(--border);
  border-radius: var(--radius); padding: 20px 24px; text-align: center;
}
.stat .value { font-size: 36px; font-weight: 500; letter-spacing: -0.8px; margin: 8px 0 4px; }
.stat .label { font-size: 13px; color: var(--text-tertiary); font-weight: 500; }
pre {
  background: var(--bg-panel); border: 1px solid var(--border);
  border-radius: var(--radius); padding: 20px; overflow-x: auto;
  font-family: 'JetBrains Mono', monospace; font-size: 13px; line-height: 1.65;
  color: var(--text-secondary); margin: 16px 0;
}
pre .comment { color: var(--text-muted); }
pre .keyword { color: var(--accent); }
pre .string { color: var(--green-emerald); }
pre .type { color: #e2c08d; }
pre .annotation { color: var(--orange); }
table { width: 100%; border-collapse: collapse; margin: 16px 0; font-size: 14px; }
th { text-align: left; padding: 10px 16px; font-weight: 600; font-size: 12px; text-transform: uppercase; letter-spacing: 0.5px; color: var(--text-tertiary); border-bottom: 1px solid var(--border); }
td { padding: 10px 16px; border-bottom: 1px solid var(--border-subtle); color: var(--text-secondary); }
tr:hover td { background: rgba(255,255,255,0.02); }
.pill { display: inline-block; padding: 2px 10px; border-radius: 9999px; font-size: 12px; font-weight: 500; border: 1px solid rgba(255,255,255,0.08); }
.pill-green { color: var(--green-emerald); border-color: rgba(16,185,129,0.3); background: rgba(16,185,129,0.08); }
.pill-blue { color: var(--accent); border-color: rgba(113,112,255,0.3); background: rgba(113,112,255,0.08); }
.pill-orange { color: var(--orange); border-color: rgba(245,158,11,0.3); background: rgba(245,158,11,0.08); }
.pill-red { color: var(--red); border-color: rgba(239,68,68,0.3); background: rgba(239,68,68,0.08); }
.flow { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 24px; margin: 16px 0; background: rgba(255,255,255,0.02); border: 1px solid var(--border); border-radius: var(--radius); }
.flow-node { padding: 8px 16px; border-radius: var(--radius-sm); font-size: 13px; font-weight: 500; border: 1px solid var(--border); background: var(--bg-surface); white-space: nowrap; }
.flow-node.active { border-color: var(--accent); color: var(--accent); }
.flow-node.human { border-color: var(--orange); color: var(--orange); }
.flow-node.end { border-color: var(--green-emerald); color: var(--green-emerald); }
.flow-arrow { color: var(--text-muted); font-size: 18px; }
.arch-diagram { background: var(--bg-panel); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 32px; margin: 24px 0; font-family: 'JetBrains Mono', monospace; font-size: 13px; line-height: 1.5; color: var(--text-secondary); overflow-x: auto; white-space: pre; }
.callout { padding: 16px 20px; border-radius: var(--radius-sm); margin: 16px 0; font-size: 14px; border-left: 3px solid var(--accent); background: rgba(113,112,255,0.05); color: var(--text-secondary); }
.callout-warn { border-left-color: var(--orange); background: rgba(245,158,11,0.05); }
.callout-success { border-left-color: var(--green-emerald); background: rgba(16,185,129,0.05); }
.tag { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.5px; }
.tag-core { background: rgba(113,112,255,0.15); color: var(--accent); }
.tag-mq { background: rgba(245,158,11,0.15); color: var(--orange); }
.tag-infra { background: rgba(16,185,129,0.15); color: var(--green-emerald); }
footer { padding: 40px 0; text-align: center; font-size: 13px; color: var(--text-muted); border-top: 1px solid var(--border-subtle); }
@media (max-width: 768px) {
  .card-grid-2, .card-grid-3 { grid-template-columns: 1fr; }
  .hero h1 { font-size: 32px; letter-spacing: -0.704px; }
}
</style>
</head>
<body>

<nav>
  <div class="logo">Agent <span>Harness</span> <span style="color:var(--text-muted);font-size:11px;margin-left:8px">v2</span></div>
  <div class="links">
    <a href="#architecture">架构</a>
    <a href="#why-langgraph4j">为什么是LangGraph4j</a>
    <a href="#workflow">工作流</a>
    <a href="#spec">Spec</a>
    <a href="#redis-mq">Redis MQ</a>
    <a href="#approval">审批</a>
    <a href="#monitoring">监控</a>
    <a href="#demo">Demo</a>
  </div>
</nav>

<div class="container">

<!-- ==================== HERO ==================== -->
<section class="hero">
  <div class="badge">LangGraph4j 原生编排 + Redis MQ + SDD</div>
  <h1>Forge<br>技术方案 v2</h1>
  <p class="subtitle">
    用 LangGraph4j 做全部编排，Redis MQ 做跨进程派发，
    Spec 驱动代码生成。不造轮子。
  </p>
</section>

<!-- ==================== 1. 整体架构 ==================== -->
<section id="architecture">
<h2>一、整体架构</h2>
<p class="lead">LangGraph4j 是唯一的编排引擎。没有自研 DAG，没有额外工作流框架。</p>

<div class="arch-diagram">
┌─────────────────────────────────────────────────────────────────────┐
│                    LangGraph4j StateGraph（编排层）                  │
│                                                                     │
│  主图 (MainAgent):                                                  │
│  ┌────────┐ ┌──────┐ ┌───────────┐ ┌──────────┐                   │
│  │analyze │→│ plan │→│reviewSpec │→│approval  │── interruptsBefore │
│  └────────┘ └──────┘ └───────────┘ └──────────┘                   │
│                                                   ↓ APPROVED       │
│  ┌──────────┐ ┌──────────┐ ┌────────┐ ┌──────────┐                │
│  │integrate │←│ collect  │←│dispatch│←│ schedule │                │
│  └──────────┘ └──────────┘ └────────┘ └──────────┘                │
│                                                                     │
│  Checkpoint: MySQL (状态持久化，重启自动恢复)                         │
│  interruptsBefore("approval"): 人工审批中断点                        │
│                                                                     │
├─────────────────────────────────────────────────────────────────────┤
│                    Redis Stream（跨进程通信层）                       │
│                                                                     │
│  dispatch 节点 ──XADD──→ agent:task:dispatch ──XREADGROUP──→ Worker │
│  Worker 完成  ──XADD──→ agent:task:result   ──读取──→ collect 节点  │
│                                                                     │
│  Redis 只做消息传递，不做编排。编排全部由 LangGraph4j 完成。           │
│                                                                     │
├─────────────────────────────────────────────────────────────────────┤
│                    基础设施层                                        │
│                                                                     │
│  MySQL (checkpoint) | Redis (MQ) | Prometheus+Grafana (监控)        │
│  LLM API (GPT/GLM)  | 飞书 Webhook (通知)                          │
└─────────────────────────────────────────────────────────────────────┘</div>

<h3>核心设计原则</h3>
<div class="card-grid card-grid-3">
  <div class="card">
    <h3>🎯 LangGraph4j 做全部编排</h3>
    <p>StateGraph 定义节点和边。addConditionalRoutes 做依赖路由。不需要自研 DAG 引擎。</p>
  </div>
  <div class="card">
    <h3>🧠 Checkpoint 驱动恢复</h3>
    <p>LangGraph4j 原生 Checkpoint 机制。releaseThread(false) 保持状态。重启后 lastStateOf() 恢复。</p>
  </div>
  <div class="card">
    <h3>📬 Redis MQ 只做传递</h3>
    <p>dispatch 节点发消息到 Redis Stream。Worker 消费执行。Redis 不做编排，只做跨进程通信。</p>
  </div>
  <div class="card">
    <h3>👁️ interruptsBefore 中断</h3>
    <p>LangGraph4j 原生中断机制。approval 节点前自动暂停。REST API 审批后 stream() 恢复。</p>
  </div>
  <div class="card">
    <h3>📋 Spec 驱动一切</h3>
    <p>Spec YAML 是单一数据源。代码、测试、监控都从 Spec 派生。</p>
  </div>
  <div class="card">
    <h3>📊 现有基础设施复用</h3>
    <p>Prometheus + Grafana 已有。加几个 Counter 就够。不造新的监控平台。</p>
  </div>
</div>
</section>

<!-- ==================== 2. 为什么是 LangGraph4j ==================== -->
<section id="why-langgraph4j">
<h2>二、为什么是 LangGraph4j，不自研 DAG</h2>

<table>
<thead>
<tr><th>能力</th><th>自研 DAG</th><th>LangGraph4j 原生</th></tr>
</thead>
<tbody>
<tr><td>节点定义</td><td>自定义 TaskNode record</td><td><span class="pill pill-green">addNode() 直接注册</span></td></tr>
<tr><td>边路由</td><td>自写 Kahn 拓扑排序</td><td><span class="pill pill-green">addConditionalEdges()</span></td></tr>
<tr><td>并行执行</td><td>自写 CompletableFuture</td><td><span class="pill pill-green">并行边自动并行</span></td></tr>
<tr><td>状态持久化</td><td>自建 checkpoint 表 + 序列化</td><td><span class="pill pill-green">CheckpointSaver 接口</span></td></tr>
<tr><td>重启恢复</td><td>自写 recovery 逻辑</td><td><span class="pill pill-green">lastStateOf() + stream()</span></td></tr>
<tr><td>人工审批</td><td>自定义中断状态机</td><td><span class="pill pill-green">interruptsBefore()</span></td></tr>
<tr><td>子任务</td><td>自定义 SubTask 追踪</td><td><span class="pill pill-green">Subgraph 或外部调用</span></td></tr>
<tr><td>可视化</td><td>自建 HTML 页面</td><td><span class="pill pill-green">LangGraph Studio</span></td></tr>
</tbody>
</table>

<div class="callout">
  <strong>结论：</strong>LangGraph4j 的 StateGraph 已经覆盖了 DAG 编排的所有能力。
  自研 Kahn 拓扑排序、自定义 DAGOrchestrator 都是重复造轮子。
  唯一需要自研的是：Spec 管理、代码生成、审批 API、Redis MQ 派发。
</div>

<h3>LangGraph4j 核心 API 速查</h3>
<pre>
<span class="comment">// 1. 定义图</span>
<span class="keyword">var</span> graph = <span class="keyword">new</span> <span class="type">StateGraph</span>&lt;&gt;(MainState.SCHEMA, serializer)
    .addNode(<span class="string">"analyze"</span>,    node_async(analyzeAction))
    .addNode(<span class="string">"plan"</span>,       node_async(planAction))
    .addNode(<span class="string">"reviewSpec"</span>, node_async(reviewSpecAction))
    .addNode(<span class="string">"approval"</span>,   node_async(approvalAction))
    .addNode(<span class="string">"schedule"</span>,   node_async(scheduleAction))
    .addNode(<span class="string">"dispatch"</span>,   node_async(dispatchAction))
    .addNode(<span class="string">"collect"</span>,    node_async(collectAction))
    .addNode(<span class="string">"integrate"</span>,  node_async(integrateAction))

    <span class="comment">// 2. 定义边（这就是 DAG 路由）</span>
    .addEdge(START, <span class="string">"analyze"</span>)
    .addEdge(<span class="string">"analyze"</span>, <span class="string">"plan"</span>)
    .addEdge(<span class="string">"plan"</span>, <span class="string">"reviewSpec"</span>)
    .addEdge(<span class="string">"reviewSpec"</span>, <span class="string">"approval"</span>)

    <span class="comment">// 3. 条件边（审批结果路由）</span>
    .addConditionalEdges(<span class="string">"approval"</span>,
        edge_async(state -> state.value(<span class="string">"decision"</span>).orElse(<span class="string">"PENDING"</span>)),
        Map.of(
            <span class="string">"APPROVED"</span>, <span class="string">"schedule"</span>,   <span class="comment">// 通过 → 调度</span>
            <span class="string">"REJECTED"</span>, <span class="string">"plan"</span>,       <span class="comment">// 拒绝 → 重新规划</span>
            <span class="string">"PENDING"</span>,  END            <span class="comment">// 未审批 → 挂起</span>
        ))

    .addEdge(<span class="string">"schedule"</span>, <span class="string">"dispatch"</span>)
    .addEdge(<span class="string">"dispatch"</span>, <span class="string">"collect"</span>)

    <span class="comment">// 4. 条件边（是否全部完成）</span>
    .addConditionalEdges(<span class="string">"collect"</span>,
        edge_async(state -> allDone(state) ? <span class="string">"done"</span> : <span class="string">"more"</span>),
        Map.of(
            <span class="string">"done"</span>, <span class="string">"integrate"</span>,
            <span class="string">"more"</span>, <span class="string">"schedule"</span>    <span class="comment">// 回环！继续调度下一批</span>
        ))

    .addEdge(<span class="string">"integrate"</span>, END);

<span class="comment">// 5. 编译（挂 checkpoint + 中断点）</span>
<span class="keyword">var</span> config = CompileConfig.builder()
    .checkpointSaver(<span class="keyword">new</span> MysqlSaver(...))
    .releaseThread(<span class="keyword">false</span>)
    .interruptsBefore(Set.of(<span class="string">"approval"</span>))  <span class="comment">// 人工审批中断</span>
    .build();

<span class="keyword">var</span> workflow = graph.compile(config);

<span class="comment">// 6. 执行（到 approval 自动中断，checkpoint 自动保存）</span>
workflow.stream(inputs, RunnableConfig.builder()
    .threadId(<span class="string">"task-001"</span>).build());

<span class="comment">// 7. 人工审批后恢复</span>
workflow.stream(updatedState, config);  <span class="comment">// 从 checkpoint 继续</span>
</pre>
</section>

<!-- ==================== 3. 工作流 ==================== -->
<section id="workflow">
<h2>三、工作流（LangGraph4j StateGraph）</h2>

<h3>主图节点</h3>
<table>
<thead>
<tr><th>节点</th><th>类型</th><th>输入</th><th>输出</th><th>LangGraph4j 实现</th></tr>
</thead>
<tbody>
<tr><td>analyze</td><td><span class="tag tag-core">LLM</span></td><td>用户需求</td><td>Business Spec + Technical Spec</td><td>NodeAction + ChatModel</td></tr>
<tr><td>plan</td><td><span class="tag tag-core">LLM</span></td><td>Technical Spec</td><td>子任务列表 + 依赖关系</td><td>NodeAction + ChatModel</td></tr>
<tr><td>reviewSpec</td><td><span class="tag tag-core">LLM</span></td><td>子任务列表</td><td>每个子任务的 SubSpec</td><td>NodeAction + ChatModel</td></tr>
<tr><td>approval</td><td><span class="tag tag-core">人工</span></td><td>SubSpecs</td><td>APPROVED / REJECTED</td><td>interruptsBefore 中断</td></tr>
<tr><td>schedule</td><td><span class="tag tag-core">算法</span></td><td>DAG + 完成状态</td><td>就绪任务列表</td><td>NodeAction（检查依赖）</td></tr>
<tr><td>dispatch</td><td><span class="tag tag-mq">MQ</span></td><td>就绪任务</td><td>Redis Stream 消息</td><td>NodeAction + RedisTemplate</td></tr>
<tr><td>collect</td><td><span class="tag tag-infra">DB</span></td><td>子任务 checkpoint</td><td>完成结果</td><td>NodeAction + CheckpointSaver</td></tr>
<tr><td>integrate</td><td><span class="tag tag-core">LLM</span></td><td>所有产物</td><td>集成代码 + 报告</td><td>NodeAction + ChatModel</td></tr>
</tbody>
</table>

<h3>回环机制（schedule ↔ collect）</h3>
<div class="arch-diagram">
LangGraph4j 的 addConditionalEdges 天然支持回环：

  dispatch → collect
               │
               ├─ allDone = true  → integrate → END
               │
               └─ allDone = false → schedule → dispatch → collect
                                                          (回环)

这就是 DAG 依赖调度！不需要自研 Kahn 拓扑排序。
LangGraph4j 的条件边 + 回环 = 自动的依赖感知调度。</div>
</section>

<!-- ==================== 4. Spec ==================== -->
<section id="spec">
<h2>四、Spec 管理（需要自研的部分）</h2>
<p class="lead">Spec 是唯一需要自研的。LangGraph4j 不管 Spec，它只管编排。</p>

<h3>Spec 在 Agent 中的位置</h3>
<div class="flow">
  <div class="flow-node">analyze</div>
  <div class="flow-arrow">→</div>
  <div class="flow-node active">生成 Spec YAML</div>
  <div class="flow-arrow">→</div>
  <div class="flow-node">plan</div>
  <div class="flow-arrow">→</div>
  <div class="flow-node active">解析 Spec 拆任务</div>
  <div class="flow-arrow">→</div>
  <div class="flow-node">reviewSpec</div>
  <div class="flow-arrow">→</div>
  <div class="flow-node active">生成子 Spec</div>
</div>

<h3>Spec YAML 结构</h3>
<pre>
<span class="keyword">spec</span>:
  <span class="keyword">version</span>: <span class="string">"1.0.0"</span>
  <span class="keyword">module</span>: <span class="string">order-service</span>
  <span class="keyword">interface</span>: <span class="type">com.gmf.order.facade.OrderFacade</span>

  <span class="keyword">methods</span>:
    - <span class="keyword">name</span>: <span class="string">createOrder</span>
      <span class="keyword">request</span>: { <span class="keyword">fields</span>: [...] }
      <span class="keyword">response</span>: { <span class="keyword">fields</span>: [...] }
      <span class="keyword">errors</span>: [...]
      <span class="keyword">spec_ref</span>: <span class="string">"AC-1,AC-2"</span>     <span class="comment"># 绑定验收标准</span>

  <span class="keyword">acceptance_criteria</span>:
    - <span class="keyword">id</span>: <span class="string">AC-1</span>
      <span class="keyword">description</span>: <span class="string">"商品必须存在且上架"</span>
      <span class="keyword">test_type</span>: <span class="string">unit</span>

  <span class="keyword">dependencies</span>:
    <span class="keyword">upstream</span>:
      - <span class="keyword">interface</span>: <span class="type">ProductFacade</span>
</pre>

<h3>Spec 文件（Demo）</h3>
<pre>
src/main/resources/specs/
├── order-service-spec.yaml           <span class="comment"># 订单服务 (AC-1~AC-6)</span>
└── warehouse-user-service-spec.yaml  <span class="comment"># 仓库+用户 (AC-1~AC-8)</span>
</pre>
</section>

<!-- ==================== 5. Redis MQ ==================== -->
<section id="redis-mq">
<h2>五、Redis MQ（跨进程通信）</h2>
<p class="lead">Redis Stream 做 Worker 派发。LangGraph4j 的 dispatch 节点发消息，Worker 消费执行。</p>

<div class="arch-diagram">
dispatch 节点 (LangGraph4j NodeAction):
  ┌──────────────────────────────────────────────────────┐
  │  // 找出就绪任务                                       │
  │  for (taskId : readyTasks) {                          │
  │      // 发 Redis Stream 消息                           │
  │      redisTemplate.opsForStream().add(                 │
  │          "agent:task:dispatch",                        │
  │          Map.of("taskId", taskId,                      │
  │                 "threadId", childThreadId,             │
  │                 "subSpec", subSpecYaml)                 │
  │      );                                                │
  │  }                                                     │
  │  // 返回，LangGraph4j 自动进入 collect 节点             │
  └──────────────────────────────────────────────────────┘

Worker (独立 JVM，@StreamMessageListener):
  ┌──────────────────────────────────────────────────────┐
  │  // 消费 Redis Stream                                 │
  │  @StreamMessageListener(stream = "agent:task:dispatch")│
  │  void onTask(TaskMessage msg) {                       │
  │      // 从 msg.subSpec 生成代码                        │
  │      // 结果写入 MySQL checkpoint                      │
  │      // collect 节点从 checkpoint 读取                  │
  │  }                                                     │
  └──────────────────────────────────────────────────────┘

collect 节点 (LangGraph4j NodeAction):
  ┌──────────────────────────────────────────────────────┐
  │  // 从 checkpoint 读取子任务结果                        │
  │  for (taskId : runningTasks) {                        │
  │      var cp = checkpointSaver.get(childConfig);       │
  │      if (cp.next().equals(END)) {                     │
  │          completed.put(taskId, cp.state().output);    │
  │      }                                                 │
  │  }                                                     │
  │  // LangGraph4j 条件边判断：                            │
  │  // allDone → integrate, !allDone → schedule (回环)    │
  └──────────────────────────────────────────────────────┘</div>
</section>

<!-- ==================== 6. 审批 ==================== -->
<section id="approval">
<h2>六、人工审批（LangGraph4j interruptsBefore）</h2>

<pre>
<span class="comment">// 编译时配置中断点</span>
CompileConfig.builder()
    .checkpointSaver(saver)
    .releaseThread(<span class="keyword">false</span>)
    .interruptsBefore(Set.of(<span class="string">"approval"</span>))  <span class="comment">// 一行搞定</span>
    .build();

<span class="comment">// 执行到 approval 前自动中断</span>
<span class="comment">// checkpoint 保存完整状态（Spec + DAG + 子任务）</span>
<span class="comment">// 审批通过后 stream() 恢复</span>
</pre>

<h3>审批 REST API</h3>
<table>
<thead><tr><th>方法</th><th>路径</th><th>说明</th></tr></thead>
<tbody>
<tr><td><span class="pill pill-green">GET</span></td><td class="mono">/api/approval/pending</td><td>待审批列表</td></tr>
<tr><td><span class="pill pill-green">GET</span></td><td class="mono">/api/approval/{threadId}/detail</td><td>Spec 详情</td></tr>
<tr><td><span class="pill pill-blue">POST</span></td><td class="mono">/api/approval/{threadId}/approve</td><td>通过 → 恢复执行</td></tr>
<tr><td><span class="pill pill-red">POST</span></td><td class="mono">/api/approval/{threadId}/reject</td><td>驳回 → 回到 plan</td></tr>
</tbody>
</table>
</section>

<!-- ==================== 7. 监控 ==================== -->
<section id="monitoring">
<h2>七、监控（现有 Prometheus + Grafana）</h2>
<p class="lead">加一个 AgentMetrics 类，4 个 Counter + 1 个 Timer。Dashboard 直接导入。</p>

<pre>
<span class="annotation">@Component</span>
<span class="keyword">public class</span> <span class="type">AgentMetrics</span> {
    <span class="keyword">private final</span> <span class="type">Counter</span> calls;    <span class="comment">// agent_llm_calls_total</span>
    <span class="keyword">private final</span> <span class="type">Counter</span> tokens;  <span class="comment">// agent_llm_tokens_total</span>
    <span class="keyword">private final</span> <span class="type">Counter</span> cost;    <span class="comment">// agent_llm_cost_cents_total</span>
    <span class="keyword">private final</span> <span class="type">Timer</span>   latency;<span class="comment">// agent_llm_latency_seconds</span>

    <span class="keyword">public</span> &lt;T&gt; T llmCall(String model, String agent,
        String node, String task, Supplier&lt;T&gt; call) {
        <span class="comment">// 一个方法覆盖所有 LLM 调用监控</span>
    }
}
</pre>

<h3>需要监控的文件</h3>
<pre>
src/main/resources/monitoring/
├── agent-alerts.yml              <span class="comment"># Prometheus 告警规则</span>
├── grafana-token-cost.json       <span class="comment"># Token 成本 Dashboard</span>
├── grafana-agent-value.json      <span class="comment"># 业务价值 Dashboard</span>
└── grafana-agent-health.json     <span class="comment"># 链路健康 Dashboard</span>
</pre>

<h3>SQL 报表</h3>
<pre>
src/main/resources/sql/init.sql
├── checkpoints           <span class="comment"># LangGraph4j checkpoint 表</span>
├── agent_task_metrics    <span class="comment"># 任务级指标</span>
├── agent_node_metrics    <span class="comment"># 节点级明细</span>
├── agent_interventions   <span class="comment"># 人工干预记录</span>
├── spec_versions         <span class="comment"># Spec 版本管理</span>
├── v_agent_weekly_report <span class="comment"># 周报视图（含 ROI）</span>
└── v_agent_node_performance <span class="comment"># 节点性能排行</span>
</pre>
</section>

<!-- ==================== 8. Demo ==================== -->
<section id="demo">
<h2>八、Demo 演示</h2>

<h3>订单服务 DAG</h3>
<div class="arch-diagram">
LangGraph4j 条件边实现：

  define-order-interface ──→ order-impl ──┐
         │                                ├──→ integration-test → END
         └──→ payment-adapter ───────────┘

  .addConditionalEdges("dispatch",
      edge_async(state -> findReadyTasks(state)),
      Map.of("define-order-interface", "worker-define",
             "order-impl",             "worker-order",
             "payment-adapter",        "worker-payment",
             "integration-test",       "worker-test"))

  不需要 Kahn 拓扑排序。
  LangGraph4j 的条件边 + 回环 = 自动依赖调度。</div>

<h3>工作流监控页面</h3>
<p>已创建独立的 DAG 可视化页面：</p>
<pre>
src/main/resources/static/workflow-monitor.html
├── 12 节点 DAG 图（SVG + CSS）
├── 6 种状态颜色 + 脉冲动画
├── SSE 实时推送（Spring Boot 启动后）
├── 离线 Demo 模拟（直接打开 HTML 就能看）
└── 点击节点查看详情（Token/费用/耗时）
</pre>
</section>

<!-- ==================== 总结 ==================== -->
<section>
<h2>总结：什么需要写，什么不需要</h2>

<div class="card-grid card-grid-2">
  <div class="card" style="border-color: var(--green-emerald);">
    <h3 style="color: var(--green-emerald);">✅ 不需要写（LangGraph4j 原生）</h3>
    <p>DAG 编排引擎<br>Kahn 拓扑排序<br>Checkpoint 持久化<br>重启恢复逻辑<br>人工审批中断机制<br>条件路由<br>回环调度<br>可视化（Studio）</p>
  </div>
  <div class="card" style="border-color: var(--accent);">
    <h3 style="color: var(--accent);">🔧 需要写（业务逻辑）</h3>
    <p>Spec YAML 定义<br>Spec → 代码骨架生成<br>审批 REST API（几个方法）<br>Redis MQ 派发（几十行）<br>AgentMetrics 拦截器<br>各节点的 NodeAction 实现</p>
  </div>
</div>

<div class="callout-success callout">
  <strong>核心观点：</strong>LangGraph4j 就是你的 DAG 引擎、工作流引擎、持久化引擎。
  你只需要写 NodeAction 里的业务逻辑。编排的事交给框架。
</div>
</section>

<footer>
  <div class="container">
    <p>Forge 技术方案 v2 · LangGraph4j 原生编排 · 2026</p>
  </div>
</footer>

</div>
</body>
</html>'''

pathlib.Path("/Users/zhangsongbo/Desktop/forge/docs/forge-architecture.html").write_text(html, encoding="utf-8")
print("Done:", len(html), "bytes")
