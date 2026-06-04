# 资管服务 — 资产池查询接口规格

## 用户故事
As a 资金方
I want 查询资管平台的资产池列表
So that 了解可用资产池的余额、状态和产品关联

验收场景：
Given 产品 P5551 存在且已上架，下有 3 个资产池
When 按产品编码查询资产池列表，page=1, size=10
Then 返回总数 3，列表包含池ID、可用余额、状态

Given 查询的产品不存在
When 按不存在的产品编码查询
Then 返回错误码 CMC_001，提示产品不存在

## 依赖关系
上游依赖：
- PEC（产品服务）：获取产品基本信息，校验产品是否存在
- ACC（账户服务）：获取账户余额信息

下游被依赖：
- COF（资金网关）：调用本接口获取资产池信息用于资金路由

## 非功能性要求
SLA: 99.9%
并发: 200 TPS
P99: < 500ms

## 接口方法

### queryAssetPool
查询资产池列表（分页）

Facade: com.gmf.cmc.facade.AssetPoolFacade

请求参数 AssetPoolQueryRequest:
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| productCode | String | 是 | 产品编码 |
| status | String | 否 | 资产池状态过滤 (ACTIVE/FROZEN/CLOSED) |
| page | int | 是 | 页码，从1开始 |
| size | int | 是 | 每页大小，最大100 |

响应参数 AssetPoolQueryResponse:
| 字段 | 类型 | 说明 |
|------|------|------|
| total | long | 总记录数 |
| list | List<AssetPoolVO> | 资产池列表 |

AssetPoolVO:
| 字段 | 类型 | 说明 |
|------|------|------|
| poolId | String | 资产池ID |
| productCode | String | 产品编码 |
| poolName | String | 资产池名称 |
| availableBalance | BigDecimal | 可用余额 |
| frozenBalance | BigDecimal | 冻结余额 |
| status | String | 状态 ACTIVE/FROZEN/CLOSED |
| createdAt | LocalDateTime | 创建时间 |

错误码:
| 错误码 | 说明 | 触发条件 |
|--------|------|----------|
| CMC_001 | 产品不存在 | productCode 在 PEC 中查不到 |
| CMC_002 | 参数校验失败 | productCode 为空或 page/size 不合法 |
| CMC_003 | 查询超时 | PEC 或 ACC 调用超时 |

## 验收标准
AC-1: productCode 为空时返回 CMC_002
AC-2: 产品不存在时返回 CMC_001
AC-3: 正常查询返回分页数据，total 准确
AC-4: status 过滤生效，只返回匹配状态的资产池
AC-5: page < 1 或 size < 1 或 size > 100 时返回 CMC_002
AC-6: 接口响应 P99 < 500ms

## 安全要求
- productCode 需防 SQL 注入（参数化查询）
- 返回数据不包含内部敏感字段
- 接口限流 200 TPS，超限返回 429
