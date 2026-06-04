---
name: gmcf-business-context
description: GMCF 业务上下文与系统映射
category: context
roles:
  - planner
  - architect
  - worker
  - all
---

# GMCF 业务上下文

## 系统名称映射（必须使用 Wiki 官方名称）
- CMC = 资管（Capital Management Center）
- LSC = 贷后（Loan Service Center）
- COF = 资金网关（Capital Operation Framework）
- PEC = 产品（Product Engine Center）
- PMC = 新支付系统（Payment Management Center）
- BEC = 标的（Business Engine Center）
- OFC = 单据（Order Framework Center）
- ACC = 账户（Account Center）
- COC = 出入款（Cash Operation Center）
- VFC = 对账（Verification Framework Center）
- CTC = 信贷交易（Credit Transaction Center）
- LMC = 贷中（Loan Management Center）
- ECC = 合同（Electronic Contract Center）

## 贷款业务全生命周期
激活 → 进件 → 路由 → 授信 → 放款 → 还款 → 代偿/回购 → 对账 → 清结算

## OFC 订单状态码 f_status
- 15 = 处理中
- 17 = 放款成功
- 18 = 放款失败
- 19 = 放款成功
- 20 = 放款成功

## 技术栈
- 后端：Java (Spring Boot)
- 前端：HTML, JavaScript
- 数据库：MySQL
- 消息队列：Redis Stream
- 办公：WPS / 飞书

## 代码规范
- 代码注释使用中文
- 数据量大时导出 Excel
- 优先用星云（Nebula）平台查询数据库
- 日志查询用 Elasticsearch
