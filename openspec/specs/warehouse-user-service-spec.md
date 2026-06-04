# 仓库服务与用户服务接口规格

- **版本**: 1.0.0
- **模块**: warehouse-user-service
- **负责人**: infra-team
- **协议**: HTTP/REST
- **更新日期**: 2026-06-02

---

## 一、WarehouseFacade 仓库服务

- **包名**: com.example.warehouseservice.facade
- **基础路径**: /api/v1/warehouse
- **说明**: 管理库存的入库、出库和查询

### inbound — 商品入库

商品入库，增加指定仓库的库存数量

- **方法**: POST /api/v1/warehouse/inbound

**请求参数**:

| 字段 | 类型 | 必填 | 说明 | 约束 |
|------|------|------|------|------|
| warehouseId | String | 是 | 仓库ID | 示例: WH001 |
| items | List\<InboundItemDTO\> | 是 | 入库商品列表 | 最少1个，最多100个 |
| items[].productId | String | 是 | 商品ID | 示例: P20001 |
| items[].quantity | Integer | 是 | 入库数量 | 1-99999 |
| items[].batchNo | String | 否 | 批次号 | - |
| operatorId | String | 是 | 操作人ID | - |

**响应**:

| 字段 | 类型 | 说明 |
|------|------|------|
| success | Boolean | 是否成功 |
| data.inboundNo | String | 入库单号，示例: IN20260602001 |
| data.processedItems | List | 处理结果列表 |
| data.processedItems[].productId | String | 商品ID |
| data.processedItems[].newStock | Integer | 入库后当前库存量 |
| data.createdAt | DateTime | 入库时间 |

**错误码**:

| 错误码 | 说明 | HTTP状态码 |
|--------|------|-----------|
| WH_001 | 仓库不存在 | 404 |
| WH_002 | 商品ID无效 | 400 |
| WH_003 | 入库数量超出单次上限 | 400 |
| WH_004 | 操作人无入库权限 | 403 |

### outbound — 商品出库

商品出库，扣减指定仓库的库存数量。依赖订单状态确认后方可执行

- **方法**: POST /api/v1/warehouse/outbound

**请求参数**:

| 字段 | 类型 | 必填 | 说明 | 约束 |
|------|------|------|------|------|
| warehouseId | String | 是 | 仓库ID | 示例: WH001 |
| orderId | String | 是 | 关联订单ID | 示例: ORD20260602001 |
| items | List\<OutboundItemDTO\> | 是 | 出库商品列表 | 最少1个，最多100个 |
| items[].productId | String | 是 | 商品ID | - |
| items[].quantity | Integer | 是 | 出库数量 | 1-99999 |
| operatorId | String | 是 | 操作人ID | - |

**响应**:

| 字段 | 类型 | 说明 |
|------|------|------|
| success | Boolean | 是否成功 |
| data.outboundNo | String | 出库单号，示例: OUT20260602001 |
| data.processedItems | List | 处理结果列表 |
| data.processedItems[].productId | String | 商品ID |
| data.processedItems[].newStock | Integer | 出库后当前库存量 |
| data.createdAt | DateTime | 出库时间 |

**错误码**:

| 错误码 | 说明 | HTTP状态码 |
|--------|------|-----------|
| WH_101 | 仓库不存在 | 404 |
| WH_102 | 库存不足，无法出库 | 400 |
| WH_103 | 关联订单不存在或状态异常 | 400 |
| WH_104 | 出库数量超出可用库存 | 400 |
| WH_105 | 操作人无出库权限 | 403 |

### queryStock — 库存查询

查询指定仓库的商品库存

- **方法**: GET /api/v1/warehouse/stock

**请求参数**:

| 字段 | 类型 | 必填 | 说明 | 来源 |
|------|------|------|------|------|
| warehouseId | String | 是 | 仓库ID | query |
| productId | String | 否 | 商品ID（不传则查询该仓库所有商品） | query |

**响应**:

| 字段 | 类型 | 说明 |
|------|------|------|
| success | Boolean | 是否成功 |
| data | List\<StockDTO\> | 库存列表 |
| data[].productId | String | 商品ID |
| data[].warehouseId | String | 仓库ID |
| data[].availableQuantity | Integer | 可用库存量 |
| data[].lockedQuantity | Integer | 锁定库存量（待出库） |
| data[].totalQuantity | Integer | 总库存量 |
| data[].updatedAt | DateTime | 最后更新时间 |

**错误码**:

| 错误码 | 说明 | HTTP状态码 |
|--------|------|-----------|
| WH_201 | 仓库不存在 | 404 |
| WH_202 | 商品不存在于该仓库 | 404 |

---

## 二、UserFacade 用户服务

- **包名**: com.example.userservice.facade
- **基础路径**: /api/v1/users
- **说明**: 管理用户注册、查询和更新

### register — 用户注册

用户注册，创建新用户账号

- **方法**: POST /api/v1/users/register

**请求参数**:

| 字段 | 类型 | 必填 | 说明 | 约束 |
|------|------|------|------|------|
| username | String | 是 | 用户名 | 3-30字符，仅字母数字下划线 |
| phone | String | 是 | 手机号 | 正则: ^1[3-9]\d{9}$ |
| email | String | 否 | 邮箱 | - |
| password | String | 是 | 密码（前端加密传输） | 8-64字符 |
| nickname | String | 否 | 昵称 | 最长50字 |

**响应**:

| 字段 | 类型 | 说明 |
|------|------|------|
| success | Boolean | 是否成功 |
| data.userId | String | 用户ID，示例: U10001 |
| data.username | String | 用户名 |
| data.createdAt | DateTime | 注册时间 |

**错误码**:

| 错误码 | 说明 | HTTP状态码 |
|--------|------|-----------|
| USR_001 | 用户名已存在 | 409 |
| USR_002 | 手机号已注册 | 409 |
| USR_003 | 用户名格式不合法 | 400 |
| USR_004 | 手机号格式不合法 | 400 |
| USR_005 | 密码强度不足 | 400 |

### queryUser — 查询用户

查询用户信息

- **方法**: GET /api/v1/users/{userId}

**请求参数**:

| 字段 | 类型 | 必填 | 说明 | 来源 |
|------|------|------|------|------|
| userId | String | 是 | 用户ID | path |

**响应**:

| 字段 | 类型 | 说明 |
|------|------|------|
| success | Boolean | 是否成功 |
| data.userId | String | 用户ID |
| data.username | String | 用户名 |
| data.phone | String | 手机号（脱敏展示） |
| data.email | String | 邮箱（脱敏展示） |
| data.nickname | String | 昵称 |
| data.status | String | 用户状态: ACTIVE / DISABLED / DELETED |
| data.createdAt | DateTime | 注册时间 |
| data.updatedAt | DateTime | 最后更新时间 |

**错误码**:

| 错误码 | 说明 | HTTP状态码 |
|--------|------|-----------|
| USR_101 | 用户不存在 | 404 |
| USR_102 | 用户已被禁用 | 403 |

### updateUser — 更新用户

更新用户信息（昵称、邮箱等非关键字段）

- **方法**: PUT /api/v1/users/{userId}

**请求参数**:

| 字段 | 类型 | 必填 | 说明 | 来源 | 约束 |
|------|------|------|------|------|------|
| userId | String | 是 | 用户ID | path | - |
| nickname | String | 否 | 昵称 | body | 最长50字 |
| email | String | 否 | 邮箱 | body | - |
| avatar | String | 否 | 头像URL | body | 最长500字 |

**响应**:

| 字段 | 类型 | 说明 |
|------|------|------|
| success | Boolean | 是否成功 |
| data.userId | String | 用户ID |
| data.updatedAt | DateTime | 更新时间 |

**错误码**:

| 错误码 | 说明 | HTTP状态码 |
|--------|------|-----------|
| USR_201 | 用户不存在 | 404 |
| USR_202 | 邮箱格式不合法 | 400 |
| USR_203 | 用户已被禁用，无法更新 | 403 |

---

## 验收标准

| ID | 优先级 | 说明 | 关联方法 | 测试类型 |
|----|--------|------|----------|----------|
| AC-1 | P0 | 入库时商品数量必须大于0，入库后 availableQuantity 正确增加 | inbound | unit |
| AC-2 | P0 | 出库时校验库存充足，库存不足返回 WH_102，不允许负库存 | outbound | unit |
| AC-3 | P0 | 出库时校验关联订单状态，订单必须为 PAID 状态才允许出库 | outbound | integration |
| AC-4 | P1 | 库存查询返回 availableQuantity、lockedQuantity、totalQuantity 三个维度数据 | queryStock | unit |
| AC-5 | P0 | 用户注册时校验用户名唯一性和手机号唯一性，重复注册返回 409 冲突 | register | unit |
| AC-6 | P0 | 用户注册时密码必须满足强度要求（至少8位，包含大小写和数字），密码必须加密存储 | register | unit |
| AC-7 | P1 | 查询用户接口返回脱敏手机号和邮箱，手机号中间4位用 * 替换 | queryUser | unit |
| AC-8 | P1 | 更新用户信息只允许修改非关键字段（昵称、邮箱、头像），不允许修改用户名和手机号 | updateUser | unit |

---

## 依赖关系

### 上游依赖

- **OrderFacade** (order-service): 订单服务 - 出库时校验订单状态，订单支付成功后触发出库
  - 使用方法: queryOrder
  - 回调事件: ORDER_PAID（订单支付成功事件，触发仓库出库流程）

### 下游回调

- **OrderFacade** (order-service): 仓库出库完成后更新订单状态为 SHIPPING
  - 事件: OUTBOUND_COMPLETED
  - 关联方法: outbound

---

## 非功能性要求

### SLA

| 方法 | P99延迟(ms) | P95延迟(ms) | 可用性 |
|------|------------|------------|--------|
| inbound | 300 | 100 | 99.9% |
| outbound | 500 | 200 | 99.9% |
| queryStock | 50 | 20 | 99.99% |
| register | 500 | 200 | 99.9% |
| queryUser | 50 | 20 | 99.99% |
| updateUser | 200 | 100 | 99.95% |

### 监控

| 指标 | 类型 | 说明 | 阈值 |
|------|------|------|------|
| stock_outbound_success_rate | gauge | 出库成功率 | >= 99.5% |
| stock_consistency_check | gauge | 库存一致性检查 | 异常率 <= 0.1% |
| user_register_qps | counter | 注册 QPS | <= 500 |
| user_query_cache_hit_rate | gauge | 用户查询缓存命中率 | >= 90% |

### 安全

- 所有接口需校验用户 Token
- 入库/出库需校验操作人权限
- 用户手机号、邮箱查询时脱敏展示
- 密码不可明文存储和传输
