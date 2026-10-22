# 开发规范

> 只写**工具管不了**的判断。能被 Checkstyle/格式化/CI 检查的规则，不在这里重复。
> 目标长度：2 页以内。最后更新：2026-10-22

---

## 1. 分层职责

| 层 | 做什么 | 不做什么 |
|---|---|---|
| **Controller** | 参数校验、调 Service、组装 `BaseResponse` | 不写业务逻辑、不直接调 Mapper |
| **Service** | 业务逻辑、事务边界、编排多个 Mapper/Feign | 不处理 HTTP 细节 |
| **Mapper** | SQL / MyBatis-Plus 查询 | 不写业务判断 |
| **InnerController** | 只给服务间调用，路径 `/inner/{service}` | 不做登录校验（没有用户 Session） |

---

## 2. 对象与命名

| 类型 | 用途 | 约定 |
|---|---|---|
| **Entity** | 对应数据库表 | 不加业务字段；不直接作为请求体 |
| **DTO** | 接请求 | 放在 `model.dto`，按功能命名（`XxxAddRequest`） |
| **VO** | 返回前端 | 脱敏后的视图对象（`XxxVO`） |
| **Enum** | 状态/角色 | 值 + 描述，提供 `getEnumByValue` |

- 类：大驼峰；方法：动词开头；常量：全大写；包名小写。
- 布尔字段不要用 `isXxx` 作为 getter 前缀的坑（MyBatis/Jackson 序列化会出问题）。

---

## 3. 异常与返回

```java
// 业务失败：抛，不返回 null
throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数不合法");

// 对外统一返回
return ResultUtils.success(data);
```

| 规则 | 说明 |
|---|---|
| 不吞异常 | `catch` 后必须处理或包装再抛 |
| 不用异常做流程控制 | 可预期的分支用 if |
| 错误码集中 | `ErrorCode` 枚举，不自造数字 |
| 不把堆栈返回给前端 | 由 `GlobalExceptionHandler` 统一处理 |

---

## 4. 日志

| 级别 | 用途 |
|---|---|
| ERROR | 需要人处理（DB 连不上、外部服务挂了） |
| WARN | 可疑但可继续（重试成功、降级） |
| INFO | 关键节点（启动、判题开始/结束） |
| DEBUG | 排错细节，生产关闭 |

- 用 `slf4j`，**禁止 `System.out`**；
- 关键链路带可追踪标识（后续统一 traceId）；
- **禁止**打印密码、token、完整请求体、用户敏感信息。

---

## 5. 注释

- 公共方法/复杂逻辑：写**为什么**，不写"是什么"；
- 显而易见的代码不写废话注释；
- 类头写职责，不写作者信息之外的流水账；
- 临时方案必须写 `TODO` + 原因 + 触发条件。

---

## 6. 配置与依赖

| 规则 | 原因 |
|---|---|
| 密码/token 走环境变量或外部配置 | 不进代码库 |
| 不新增依赖（先问） | 父 pom 锁版本，新增易冲突 |
| 不改端口 / context-path / 服务名 | Nacos、网关、前端都依赖 |
| 服务地址用服务名（`lb://`） | 不写死 IP |

---

## 7. Git 与提交

- 从 `main` 切分支：`feat/xxx`、`fix/xxx`、`docs/xxx`；
- 一个提交只做一件事；
- commit 信息用 `type: 描述`（feat/fix/docs/refactor/test/chore）；
- 提交前：`mvn -pl <module> -am -DskipTests package` 通过；
- 不提交 `target/`、日志、IDE 配置、真实密钥。

---

## 8. 测试

- 新功能至少有一个能跑通的验证方式（单测或 curl 示例）；
- 修 bug 尽量补一个能复现的测试；
- 当前测试很少，新增测试优先覆盖：判题状态流转、权限校验、inner 拦截。

---

## 9. 禁止清单

1. Controller 里写业务/SQL；
2. 用 Entity 直接接请求或直接返回（泄漏字段）；
3. `System.out.println`；
4. 捕获异常后什么都不做；
5. 给 `judge-service` 开对外路由；
6. 在网关里写业务逻辑；
7. 把 `127.0.0.1` 写进需要跨机调用的注册地址；
8. 把密钥贴进 README / issue / 聊天。
