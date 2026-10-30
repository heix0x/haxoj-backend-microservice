# AGENTS.md

> 给 AI 与新同事的工作手册。**先读这页**，需要细节再看 `docs/README.md` 的索引。
> 最后更新：2026-10-27

---

## 1. 项目是什么

HaxOJ —— 在线判题系统后端（微服务版）。

用户提交代码 → 判题服务调用**远程代码沙箱**执行 → 结果回写数据库。
前端在独立仓库 `haxoj-frontend`，通过网关 `http://localhost:8001` 访问。

7 个 Maven 模块：`common` / `model` / `service-client` / `gateway` / `user-service` / `question-service` / `judge-service`。

---

## 2. 当前状态（写文档/改代码前先看）

| 状态 | 内容 |
|---|---|
| ✅ 已完成 | 模块拆分、Nacos 注册、Feign 跨服务调用、网关路由 + 跨域 + inner 拦截、Redis 共享 Session、Knife4j 文档聚合、**RabbitMQ 异步判题 + 幂等** |
| 🚧 进行中 | 链路追踪、压测数据 |
| ⛔ 未启用 | Nacos 配置中心、Sentinel 规则 |

> 状态以本节为准；`README.md` 里的"当前进度"如果与这里冲突，以这里为准（README 正在同步）。

---

## 3. 环境与命令

| 项 | 值 |
|---|---|
| JDK | 11（源码级别 `java.version=1.8`） |
| 构建 | Maven 3.9 |
| 依赖服务 | MySQL 3306、Redis 6379、Nacos 8848、RabbitMQ 5672 |
| 沙箱 | 远程，地址由 `SANDBOX_EXECUTE_URL` 注入（默认 `http://192.168.221.45:8080/sandbox/execute`） |
| **一键运行** | `.\ops\dev-up.ps1`（在仓库外层 `haxoj\ops\`；`-SkipBuild` / `-NoVm` / `-RebuildSandbox`） |

> **配置不要再写死**：地址、账号、密钥一律写成 `${VAR:默认值}`，
> 真实值放 `ops/env/dev.env`（不入库）+ 运行时环境变量。
> 分层规矩与理由见 `ops/README.md` §6。**IP 会漂（虚拟机）、密码会换，
> 任何要「改代码重新编译」才能换的东西都是错的。**

```bash
# 全量构建
mvn clean install -DskipTests

# 单模块（-am 会带上依赖模块）
mvn -pl haxoj-backend-user-service -am -DskipTests package

# 启动顺序
# Nacos → user(8101) → question(8102) → judge(8103) → gateway(8001)
mvn -pl haxoj-backend-gateway spring-boot:run

# 单测（目前测试很少）
mvn -pl <module> test
```

---

## 4. 模块地图

| 模块 | 端口 | 职责 | 改之前注意 |
|---|---|---|---|
| `common` | — | 工具、异常、常量、AOP、全局配置 | ⚠️ 所有服务都依赖 |
| `model` | — | Entity / DTO / VO / 枚举 | ⚠️ 字段变更影响所有服务 |
| `service-client` | — | **Feign 契约**（只放接口 + HTTP 注解） | ⚠️ 契约变更要同步调用方 |
| `gateway` | 8001 | 唯一对外入口：路由、跨域、inner 拦截、鉴权骨架 | ⚠️ 不能引 `spring-boot-starter-web` |
| `user-service` | 8101 | 注册/登录/用户管理/文件上传 | 登录态写 Redis |
| `question-service` | 8102 | 题目、提交记录 | 调 judge / user |
| `judge-service` | 8103 | 判题逻辑、调沙箱 | **无对外 Controller** |

所有业务服务：`server.address: 127.0.0.1`、`context-path: /api`、注册到同一个 Nacos namespace。

---

## 5. 代码约定（详见 `docs/conventions.md`）

- **分层**：Controller（参数校验 + 调 Service）→ Service（业务 + 事务）→ Mapper（SQL）。Controller 不写业务。
- **对象**：Entity 对应表；DTO 接请求；VO 返回前端。**不混用**，不拿 Entity 直接当请求体。
- **异常**：`BusinessException` + `ErrorCode`；禁止用 `null` 表示失败。
- **返回**：统一 `BaseResponse<T>`。
- **日志**：slf4j，禁止 `System.out`；关键路径带可追踪信息；**禁止打印密码/token/完整请求体**。
- **注释**：公共方法写"为什么"；显而易见的代码不写废话注释。
- **权限**：需要登录/角色的接口加 `@AuthCheck`。

---

## 6. 关键设计（详见 `docs/architecture.md`）

| 主题 | 约定 |
|---|---|
| 入口 | 前端只连 **8001 网关**；业务服务只监听 `127.0.0.1` |
| inner 接口 | 路径 `/api/inner/{service}/**`；网关 `GlobalAuthFilter` 一律 404；**不要**给 inner 开路由 |
| 服务调用 | 用 `service-client` 的 Feign 契约，**不要**手写 HTTP 调其他服务 |
| 判题触发 | 提交后发 MQ（`haxoj.judge.exchange` / `judge.submit`），**不要**再改回 `CompletableFuture + Feign` |
| MQ 异常 | 业务异常 ack 丢弃；系统异常重置 WAITING + nack → DLQ；**不要** `requeue=true` |
| MQ 幂等 | 状态检查 + 原子抢占（`claimQuestionSubmit`），重复消息只能有一个消费者继续 |
| 登录态 | Redis 共享 Session（`spring.session.store-type: redis`，db 1） |
| 判题 | 提交 → MQ → judge 消费 → HTTP 调沙箱 → **单事务 `finish` 落定**（状态 + 通过数） |
| 文档 | Knife4j 聚合在网关 `/doc.html`（生产应关闭） |

---

## 7. 边界（不要做，除非先问）

> 这些是**默认约束，不是不可讨论的宪法**。当初的取舍可能有短视之处（例如「服务只绑 127.0.0.1
> 就安全」只挡住了别的机器，挡不住本机进程）。要突破某一条时，说清目标与代价，改完同步文档，
> 不要默默绕过。

1. 不要新增依赖（父 pom 已锁版本，新增容易引发冲突）。
2. 不要改端口、`context-path`、`spring.application.name`（Nacos/路由都依赖）。
3. 不要动 `common` / `model` 的公共字段而不评估影响（全服务共用）。**可以改**，但要在同一个改动里
   同步 `service-client` 契约、`inner` 实现、所有调用方，并说明影响面。
4. 不要把真实密钥、密码、token 写进代码、配置、文档、日志。
5. 不要绕过 Feign 契约直接写 HTTP 调别的服务。
6. 不要给 `judge-service` 开对外路由。
7. 不要在网关里写业务逻辑。

---

## 8. 新增一个接口的 checklist

1. 判断归属：user / question / judge？内部还是对外？
2. 对外：`controller/` 下加方法；内部：`inner/` 包 + `/inner/{service}` 前缀。
3. Service 写业务，Mapper 写 SQL。
4. 需要登录 → `@AuthCheck`；需要跨服务 → 改 `service-client` 契约。
5. 返回 `BaseResponse`，异常走 `BusinessException`。
6. 自测：给出 `curl` 与期望响应。
7. 需要的话补 Knife4j 注解。
8. 改完更新 `docs/` 里对应的设计说明（如果行为变了）。

---

## 9. 验收标准（AI 完成任务时必须给出）

- [ ] 编译通过：`mvn -pl <module> -am -DskipTests package`
- [ ] 给出改动的文件清单与原因
- [ ] 给出验证方式（curl / 测试 / 日志）与期望结果
- [ ] 没有新增依赖、没有改端口、没有提交密钥

---

## 10. 文档入口

**接手顺序建议**：`docs/known-issues.md`（现在有什么坑）→ `docs/roadmap.md`（先做什么）→ `docs/architecture.md`（系统怎么组织）→ 需要细节时再翻 `docs/notes/`。

- 文档索引：`docs/README.md`
- 系统地图：`docs/architecture.md`
- 开发规范：`docs/conventions.md`
- 改造日志：`docs/changelog.md`（时间倒序，新条目在前；旧结论可就地修正并标注核对日期）
- 已知问题与风险：`docs/known-issues.md`（快照，修复后改状态、不删条目）
- 下一步建议：`docs/roadmap.md`（快照，含验收标准与"已否决"记录）
- 实现记录：`docs/notes/`（**单一正文**：同一主题只有一份，过时就地更新，不留旧版、不另写平行文档）
- 功能解剖：`docs/anatomy/`（极小颗粒度功能讲解，**随代码更新**；命名与规范见其 README）
- 决策记录：`docs/adr/`
