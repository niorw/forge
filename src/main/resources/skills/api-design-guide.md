---
name: api-design-guide
description: RESTful API 设计指南
category: convention
roles:
  - architect
  - worker
---

# API 设计指南（GMCF 项目）

## URL 规范
- 基础路径：/api/v1/{module}/{resource}
- 查询：GET /api/v1/orders/{orderId}
- 列表：GET /api/v1/orders?page=1&size=20
- 创建：POST /api/v1/orders
- 修改：PUT /api/v1/orders/{orderId}
- 删除：DELETE /api/v1/orders/{orderId}

## 请求规范
- Content-Type: application/json
- 请求体使用 @Valid 校验
- 分页参数：page（从1开始）、size（默认20、最大100）
- 排序参数：sort=field,order（如 sort=createTime,desc）

## 响应规范
- 成功：{"code": 200, "message": "success", "data": {...}}
- 失败：{"code": 40001, "message": "订单不存在", "data": null}
- 分页：{"code": 200, "data": {"list": [...], "total": 100, "page": 1, "size": 20}}

## 错误码规范
- 200: 成功
- 400xx: 参数错误（40001=缺少必填参数, 40002=参数格式错误）
- 401xx: 认证错误（40101=未登录, 40102=token过期）
- 403xx: 权限错误（40301=无权限, 40302=越权访问）
- 404xx: 资源不存在
- 500xx: 系统错误（50001=数据库异常, 50002=RPC调用失败）

## 幂等性
- 所有写操作必须支持幂等（通过 requestId 或业务唯一键）
- 重复请求返回相同结果，不产生副作用
