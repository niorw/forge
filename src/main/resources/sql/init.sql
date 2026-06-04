-- ============================================================
-- Agent Harness 数据库初始化脚本
-- 数据库: forge
-- 字符集: utf8mb4
-- MySQL 8.0+
--
-- 说明：
--   LangGraph4j MysqlSaver 的 checkpoint 表（LANGRAPH4J_THREAD、
--   LANGRAPH4J_CHECKPOINT）由框架自动创建，不需要手动建。
--   本脚本只建业务监控相关的表。
-- ============================================================

CREATE DATABASE IF NOT EXISTS forge
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE forge;

-- ============================================================
-- [框架自动创建] LangGraph4j Checkpoint 表
-- 以下两张表由 MysqlSaver 自动创建，不需要手动执行：
--
-- CREATE TABLE LANGRAPH4J_THREAD (
--     thread_id   VARCHAR(36) PRIMARY KEY,
--     thread_name VARCHAR(255),
--     is_released BOOLEAN DEFAULT FALSE NOT NULL
-- );
--
-- CREATE TABLE LANGRAPH4J_CHECKPOINT (
--     checkpoint_id VARCHAR(36) PRIMARY KEY,
--     thread_id     VARCHAR(36) NOT NULL,
--     node_id       VARCHAR(255),
--     next_node_id  VARCHAR(255),
--     state_data    JSON NOT NULL,
--     saved_at      TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
--     FOREIGN KEY (thread_id) REFERENCES LANGRAPH4J_THREAD(thread_id) ON DELETE CASCADE
-- );
--
-- 使用方式：
-- var saver = MysqlSaver.builder()
--     .dataSource(dataSource)
--     .createOption(CreateOption.CREATE_IF_NOT_EXISTS)  // 自动建表
--     .build();
-- ============================================================


-- ============================================================
-- 1. 任务指标表（业务级追踪）
--    每个 Agent 任务一行，记录产出、质量、成本
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_task_metrics (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id             VARCHAR(64) NOT NULL COMMENT '任务唯一ID',
    requirement         TEXT COMMENT '原始需求描述',
    thread_id           VARCHAR(36) COMMENT '主Agent的threadId（对应LANGRAPH4J_THREAD.thread_id）',
    started_at          DATETIME NOT NULL COMMENT '任务开始时间',
    completed_at        DATETIME COMMENT '任务完成时间',
    status              VARCHAR(20) NOT NULL DEFAULT 'RUNNING'
                        COMMENT 'RUNNING/SUCCESS/FAILED/CANCELLED/WAITING_APPROVAL',

    -- 产出指标
    spec_count          INT DEFAULT 0 COMMENT '生成的 Spec 数量',
    code_lines          INT DEFAULT 0 COMMENT '生成的代码行数',
    test_count          INT DEFAULT 0 COMMENT '生成的测试用例数',
    spec_coverage       DECIMAL(5,2) DEFAULT 0 COMMENT 'Spec 覆盖率(%)',

    -- 质量指标
    human_interventions INT DEFAULT 0 COMMENT '人工干预次数',
    approval_rounds     INT DEFAULT 0 COMMENT '审批轮次',
    dag_node_count      INT DEFAULT 0 COMMENT 'DAG 节点总数',

    -- 成本指标
    total_llm_calls     INT DEFAULT 0 COMMENT 'LLM 调用总次数',
    total_input_tokens  BIGINT DEFAULT 0 COMMENT '输入 Token 总量',
    total_output_tokens BIGINT DEFAULT 0 COMMENT '输出 Token 总量',
    total_tokens        BIGINT DEFAULT 0 COMMENT 'Token 总量',
    total_cost_cents    INT DEFAULT 0 COMMENT '费用(分)',
    duration_seconds    INT DEFAULT 0 COMMENT '总耗时(秒)',

    -- 模型使用
    models_used         VARCHAR(512) COMMENT '使用的模型列表，逗号分隔',

    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_task_id (task_id),
    INDEX idx_status (status),
    INDEX idx_started_at (started_at),
    INDEX idx_thread_id (thread_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 任务级指标表';


-- ============================================================
-- 2. 节点指标表（每个节点的执行明细）
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_node_metrics (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id         VARCHAR(64) NOT NULL COMMENT '关联的任务ID',
    thread_id       VARCHAR(36) COMMENT '子Agent的threadId',
    node_name       VARCHAR(64) NOT NULL COMMENT '节点名: analyze/plan/dispatch/worker-xxx/...',
    node_type       VARCHAR(32) COMMENT '节点类型: llm/algorithm/human/mq',
    agent_id        VARCHAR(64) COMMENT 'Agent标识: main-agent/child-xxx',
    model           VARCHAR(64) COMMENT '使用的LLM模型: gpt-4o/glm-5.1/qwen2.5',

    -- 执行状态
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                    COMMENT 'PENDING/RUNNING/COMPLETED/FAILED/BLOCKED',
    started_at      DATETIME COMMENT '开始执行时间',
    completed_at    DATETIME COMMENT '完成时间',
    duration_ms     INT DEFAULT 0 COMMENT '执行耗时(毫秒)',

    -- Token 消耗
    input_tokens    BIGINT DEFAULT 0 COMMENT '输入Token',
    output_tokens   BIGINT DEFAULT 0 COMMENT '输出Token',
    total_tokens    BIGINT DEFAULT 0 COMMENT '总Token',
    cost_cents      INT DEFAULT 0 COMMENT '费用(分)',
    llm_call_count  INT DEFAULT 0 COMMENT 'LLM调用次数',

    -- 结果
    result_summary  VARCHAR(1024) COMMENT '执行结果摘要',
    error_message   TEXT COMMENT '错误信息',

    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_task_id (task_id),
    INDEX idx_node_name (node_name),
    INDEX idx_status (status),
    INDEX idx_thread_id (thread_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 节点级执行明细表';


-- ============================================================
-- 3. 人工干预记录表
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_interventions (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id         VARCHAR(64) NOT NULL COMMENT '关联的任务ID',
    thread_id       VARCHAR(36) COMMENT 'checkpoint threadId',
    phase           VARCHAR(32) NOT NULL COMMENT '阶段: approval/plan/collect/integrate',
    action          VARCHAR(32) NOT NULL COMMENT '动作: approve/reject/modify/cancel',
    operator        VARCHAR(64) COMMENT '操作人',
    comment         TEXT COMMENT '审批意见/修改原因',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_task_id (task_id),
    INDEX idx_phase (phase)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人工干预记录表';


-- ============================================================
-- 4. Spec 版本管理表
-- ============================================================
CREATE TABLE IF NOT EXISTS spec_versions (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    spec_id         VARCHAR(128) NOT NULL COMMENT 'Spec标识，如 order-service-spec',
    version         VARCHAR(32) NOT NULL COMMENT '语义化版本 1.2.0',
    status          VARCHAR(32) NOT NULL DEFAULT 'DRAFT'
                    COMMENT 'DRAFT/REVIEW/APPROVED/ACTIVE/DEPRECATED/ARCHIVED',
    spec_yaml       MEDIUMTEXT NOT NULL COMMENT '完整 Spec YAML 内容',
    change_summary  VARCHAR(512) COMMENT '变更摘要',
    author          VARCHAR(64) COMMENT '作者',
    reviewer        VARCHAR(64) COMMENT '审批人',
    approved_at     DATETIME COMMENT '审批时间',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_spec_version (spec_id, version),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Spec 版本管理表';


-- ============================================================
-- 5. Redis MQ 任务审计日志（可选）
--    Redis Stream 本身不持久化，重要任务同步写 MySQL 做审计
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_task_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id         VARCHAR(64) NOT NULL,
    thread_id       VARCHAR(36) NOT NULL,
    task_type       VARCHAR(64) COMMENT '任务类型: interface-design/implementation/testing',
    status          VARCHAR(32) NOT NULL COMMENT 'DISPATCHED/CONSUMED/COMPLETED/FAILED',
    worker_id       VARCHAR(128) COMMENT '消费该任务的 Worker 标识',
    redis_stream_id VARCHAR(64) COMMENT 'Redis Stream 消息ID',
    dispatched_at   DATETIME COMMENT '派发时间',
    consumed_at     DATETIME COMMENT '消费时间',
    completed_at    DATETIME COMMENT '完成时间',
    duration_ms     INT DEFAULT 0 COMMENT '执行耗时',
    error_message   TEXT COMMENT '失败原因',

    INDEX idx_task_id (task_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Redis MQ 任务审计日志';


-- ============================================================
-- 6. 周报视图（含 ROI 计算）
-- ============================================================
CREATE OR REPLACE VIEW v_agent_weekly_report AS
SELECT
    DATE_FORMAT(completed_at, '%Y-%u') AS week,
    COUNT(*) AS task_count,
    SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS success_count,
    SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed_count,
    ROUND(AVG(duration_seconds) / 3600, 2) AS avg_duration_hours,
    SUM(total_tokens) AS total_tokens,
    ROUND(SUM(total_cost_cents) / 100, 2) AS total_cost_yuan,
    ROUND(AVG(total_tokens), 0) AS avg_tokens_per_task,
    ROUND(AVG(total_cost_cents) / 100, 2) AS avg_cost_per_task_yuan,
    ROUND(AVG(spec_coverage), 1) AS avg_spec_coverage,
    SUM(human_interventions) AS total_interventions,
    -- ROI: 每个成功任务节省 2 人天 * 500元
    SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) * 2 * 500 AS saved_cost_yuan,
    ROUND(
        (SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) * 2 * 500)
        / NULLIF(SUM(total_cost_cents) / 100, 0),
        1
    ) AS roi_multiple
FROM agent_task_metrics
WHERE completed_at IS NOT NULL
GROUP BY DATE_FORMAT(completed_at, '%Y-%u')
ORDER BY week DESC;


-- ============================================================
-- 7. 节点性能排行视图
-- ============================================================
CREATE OR REPLACE VIEW v_agent_node_performance AS
SELECT
    node_name,
    node_type,
    model,
    COUNT(*) AS call_count,
    ROUND(AVG(duration_ms), 0) AS avg_duration_ms,
    ROUND(AVG(total_tokens), 0) AS avg_tokens,
    ROUND(AVG(cost_cents) / 100, 4) AS avg_cost_yuan,
    SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS fail_count,
    ROUND(
        SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 1
    ) AS fail_rate_pct
FROM agent_node_metrics
WHERE status IN ('COMPLETED', 'FAILED')
GROUP BY node_name, node_type, model
ORDER BY avg_duration_ms DESC;
