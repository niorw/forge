---
name: java-naming-convention
description: Java 命名与编码规范
category: convention
roles:
  - worker
  - architect
  - all
---

# Java 命名与编码规范（GMCF 项目）

## 类命名
- Service 层：XxxService / XxxServiceImpl
- Controller 层：XxxController
- DAO 层：XxxMapper / XxxRepository
- 工具类：XxxUtils / XxxHelper
- 常量类：XxxConstants
- 枚举类：XxxEnum / XxxStatus

## 方法命名
- 查询：get / find / list / query / select
- 新增：create / add / insert / save
- 修改：update / modify / edit
- 删除：delete / remove
- 判断：is / has / can / should

## 字段命名
- 数据库字段：f_xxx（如 f_order_id, f_status, f_create_time）
- Java 字段：camelCase（如 orderId, status, createTime）
- DTO/VO 字段：camelCase，与数据库字段通过 MapStruct 转换

## 包命名
- com.gmf.{system}.controller
- com.gmf.{system}.service / .service.impl
- com.gmf.{system}.mapper
- com.gmf.{system}.model / .dto / .vo
- com.gmf.{system}.config
- com.gmf.{system}.enums

## 注释规范
- 类注释：@author + @since + 功能描述
- 方法注释：@param + @return + @throws + 功能描述
- 复杂逻辑：行内注释说明 why，而非 what

## 异常处理
- 业务异常：继承 BusinessException，带错误码
- 系统异常：全局 @ExceptionHandler 统一处理
- 禁止：catch 后不处理、catch 后只打日志不抛出
