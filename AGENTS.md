# AGENTS.md

> 给 AI 与新同事的工作手册。**先读这页**，需要细节再看 `docs/README.md` 的索引。
> 最后更新：2026-10-22

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
| ✅ 已完成 | 模块拆分、Nacos 注册、Feign 跨服务调用、网关路由 + 跨域 + inner 拦截、Redis 共享 Session、Knife4j 文档聚合 |
| 🚧 进行中 | MQ 异步判题、判题幂等、链路追踪、压测数据 |
| ⛔ 未启用 | Nacos 配置中心、Sentinel 规则 |

> 状态以本节为准；`README.md` 里的"当前进度"如果与这里冲突，以这里为准（README 正在同步）。

---

## 3. 环境与命令

| 项 | 值 |
|---|---|
| JDK | 11（源码级别 `java.version=1.8`） |
| 构建 | Maven 3.9 |
| 依赖服务 | MySQL 3306、Redis 6379、Nacos 8848 |
| 沙箱 | 远程 `http://192.168.221.45:8080/sandbox/execute` |

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
| 登录态 | Redis 共享 Session（`spring.session.store-type: redis`，db 1） |
| 判题 | question → Feign `doJudge` → judge → HTTP 调沙箱 |
| 文档 | Knife4j 聚合在网关 `/doc.html`（生产应关闭） |

---

## 7. 边界（不要做，除非先问）

1. 不要新增依赖（父 pom 已锁版本，新增容易引发冲突）。
2. 不要改端口、`context-path`、`spring.application.name`（Nacos/路由都依赖）。
3. 不要动 `common` / `model` 的公共字段（全服务受影响）。
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

- 文档索引：`docs/README.md`
- 系统地图：`docs/architecture.md`
- 开发规范：`docs/conventions.md`
- 历史实现记录：`docs/notes/`（按日期，**不要改历史，只追加**）
- 决策记录：`docs/adr/`
