# 订单服务接口规格

- **版本**: 1.0.0
- **模块**: order-service
- **负责人**: order-team
- **接口**: OrderFacade
- **协议**: HTTP/REST
- **基础路径**: /api/v1/orders
- **更新日期**: 2026-06-02

---

## 接口方法

### createOrder

创建订单，校验商品库存、计算价格、生成订单号

- **方法**: POST /api/v1/orders/create

**请求参数**:

| 字段 | 类型 | 必填 | 说明 | 约束 |
|------|------|------|------|------|
| userId | String | 是 | 下单用户ID | 示例: U10001 |
| items | List\<OrderItemDTO\> | 是 | 订单商品列表 | 最少1个，最多50个 |
| items[].productId | String | 是 | 商品ID | 示例: P20001 |
| items[].quantity | Integer | 是 | 购买数量 | 1-999 |
| items[].unitPrice | BigDecimal | 是 | 商品单价（分） | 最小1 |
| shippingAddress | AddressDTO | 是 | 收货地址 | - |
| shippingAddress.province | String | 是 | 省份 | - |
| shippingAddress.city | String | 是 | 城市 | - |
| shippingAddress.detail | String | 是 | 详细地址 | 最长200字 |
| couponCode | String | 否 | 优惠券编码 | - |
| remark | String | 否 | 订单备注 | 最长500字 |

**响应**:

| 字段 | 类型 | 说明 |
|------|------|------|
| success | Boolean | 是否成功 |
| data.orderId | String | 订单ID，示例: ORD20260602001 |
| data.totalAmount | BigDecimal | 订单总金额（分） |
| data.status | String | 订单状态: PENDING_PAYMENT / CANCELLED |
| data.createdAt | DateTime | 创建时间 |

**错误码**:

| 错误码 | 说明 | HTTP状态码 |
|--------|------|-----------|
| ORDER_001 | 商品不存在或已下架 | 400 |
| ORDER_002 | 商品库存不足 | 400 |
| ORDER_003 | 用户信息无效 | 400 |
| ORDER_004 | 优惠券无效或已过期 | 400 |
| ORDER_005 | 订单金额计算异常 | 500 |

### cancelOrder

取消订单，释放库存、处理退款

- **方法**: POST /api/v1/orders/{orderId}/cancel

**请求参数**:

| 字段 | 类型 | 必填 | 说明 | 来源 | 约束 |
|------|------|------|------|------|------|
| orderId | String | 是 | 订单ID | path | 示例: ORD20260602001 |
| userId | String | 是 | 用户ID（校验归属） | header | - |
| reason | String | 是 | 取消原因 | body | 枚举: USER_CANCEL / PAYMENT_TIMEOUT / SYSTEM_CANCEL |

**响应**:

| 字段 | 类型 | 说明 |
|------|------|------|
| success | Boolean | 是否成功 |
| data.orderId | String | 订单ID |
| data.status | String | 订单状态: CANCELLED |
| data.refundAmount | BigDecimal | 退款金额（分） |
| data.cancelledAt | DateTime | 取消时间 |

**错误码**:

| 错误码 | 说明 | HTTP状态码 |
|--------|------|-----------|
| ORDER_101 | 订单不存在 | 404 |
| ORDER_102 | 订单状态不允许取消（已发货/已完成） | 400 |
| ORDER_103 | 无权取消该订单 | 403 |
| ORDER_104 | 退款处理失败 | 500 |

### queryOrder

查询订单详情

- **方法**: GET /api/v1/orders/{orderId}

**请求参数**:

| 字段 | 类型 | 必填 | 说明 | 来源 |
|------|------|------|------|------|
| orderId | String | 是 | 订单ID | path |
| userId | String | 是 | 用户ID（校验归属） | header |

**响应**:

| 字段 | 类型 | 说明 |
|------|------|------|
| success | Boolean | 是否成功 |
| data.orderId | String | 订单ID |
| data.userId | String | 用户ID |
| data.items | List\<OrderItemDTO\> | 订单商品列表 |
| data.totalAmount | BigDecimal | 订单总金额（分） |
| data.status | String | 订单状态: PENDING_PAYMENT / PAID / SHIPPING / DELIVERED / COMPLETED / CANCELLED |
| data.createdAt | DateTime | 创建时间 |
| data.updatedAt | DateTime | 更新时间 |
| data.shippingAddress | AddressDTO | 收货地址 |

**错误码**:

| 错误码 | 说明 | HTTP状态码 |
|--------|------|-----------|
| ORDER_201 | 订单不存在 | 404 |
| ORDER_202 | 无权查看该订单 | 403 |

---

## 验收标准

| ID | 优先级 | 说明 | 关联方法 | 测试类型 |
|----|--------|------|----------|----------|
| AC-1 | P0 | 创建订单时必须校验所有商品存在且在售状态，商品不存在时返回 ORDER_001 错误 | createOrder | unit |
| AC-2 | P0 | 创建订单时必须校验商品库存充足，库存不足时返回 ORDER_002 错误并提示可购买数量 | createOrder | unit |
| AC-3 | P0 | 创建订单成功后订单状态为 PENDING_PAYMENT，订单金额为所有商品单价×数量之和（扣除优惠券） | createOrder | unit |
| AC-4 | P0 | 取消订单时校验订单状态，只有 PENDING_PAYMENT 和 PAID 状态可取消，已发货/已完成不可取消 | cancelOrder | unit |
| AC-5 | P1 | 取消订单时必须校验订单归属，非本人订单返回 ORDER_103 无权操作 | cancelOrder | unit |
| AC-6 | P1 | 查询订单接口返回完整的订单信息，包括商品列表、金额、状态、地址等全部字段 | queryOrder | integration |

---

## 依赖关系

### 上游依赖

- **ProductFacade** (product-service): 商品查询服务 - 查询商品详情、校验商品状态和价格
  - 使用方法: queryProduct, checkProductOnSale
- **PaymentFacade** (payment-service): 支付服务 - 创建支付单、处理退款
  - 使用方法: createPayment, refund
- **WarehouseFacade** (warehouse-service): 仓库服务 - 查询库存、锁定库存、释放库存
  - 使用方法: queryStock, lockStock, releaseStock

### 下游回调

- **WarehouseFacade** (warehouse-service): 订单状态变更时通知仓库服务执行出库
  - 事件: ORDER_STATUS_CHANGED
  - 关联方法: createOrder

---

## 非功能性要求

### SLA

| 方法 | P99延迟(ms) | P95延迟(ms) | 可用性 |
|------|------------|------------|--------|
| createOrder | 500 | 200 | 99.9% |
| cancelOrder | 300 | 100 | 99.95% |
| queryOrder | 100 | 50 | 99.99% |

### 监控

| 指标 | 类型 | 说明 | 阈值 |
|------|------|------|------|
| order_create_success_rate | gauge | 下单成功率 | >= 99% |
| order_create_qps | counter | 下单 QPS | <= 1000 |
| order_cancel_rate | gauge | 订单取消率 | <= 5% |

### 安全

- 所有接口需校验用户 Token
- 取消订单需校验订单归属权
- 敏感字段（手机号、地址）需脱敏日志
