# 系统架构

> 这份文档回答：**系统由哪些部分组成、请求怎么流转、关键设计为什么这样。**
> 决策的"为什么"在 [`adr/`](adr/)；实现细节在 [`notes/`](notes/)。
>
> 最后更新：2026-10-22

---

## 1. 系统边界

```
┌──────────────────────────────────────────────────────────────┐
│                        外部（浏览器）                          │
│                  http://localhost:8001                        │
└───────────────────────────┬──────────────────────────────────┘
                            │
┌───────────────────────────▼──────────────────────────────────┐
│  haxoj-backend-gateway :8001（唯一对外入口，WebFlux/Netty）    │
│  路由 / 跨域 / inner 拦截 / 鉴权骨架 / 文档聚合                 │
└───────┬───────────────────────────────┬──────────────────────┘
        │ lb://user                     │ lb://question
        ▼                               ▼
┌───────────────────┐          ┌──────────────────────────────┐
│ user-service:8101 │◀──Feign──│ question-service:8102        │
│ 用户/登录/文件     │          │ 题目/提交                     │
└───────────────────┘          └──────────┬───────────────────┘
        ▲                                  │ Feign doJudge
        │ Feign 查用户                      ▼
        │                       ┌──────────────────────────────┐
        └───────────────────────│ judge-service:8103           │
                                │ 判题（无对外 Controller）      │
                                └──────────┬───────────────────┘
                                           │ HTTP POST /sandbox/execute
                                           ▼
                                ┌──────────────────────────────┐
                                │ haxoj-code-sandbox :8080      │
                                │ 独立部署（虚拟机 192.168.221.45）│
                                └──────────────────────────────┘

基础设施：Nacos 8848（注册发现）· MySQL 3306 · Redis 6379 db1（Session）
```

**边界原则**：

- 前端只认 `8001`，不知道 8101~8103 的存在；
- 业务服务只监听 `127.0.0.1`（单机开发），外部必须经网关；
- 沙箱是独立进程/独立机器，只接受判题服务的调用。

---

## 2. 模块与端口

| 模块 | 端口 | context-path | 职责 | 对外？ |
|---|---|---|---|---|
| `gateway` | 8001 | — | 路由、跨域、inner 拦截、鉴权骨架、文档聚合 | ✅ 唯一入口 |
| `user-service` | 8101 | `/api` | 注册、登录、用户管理、文件上传（COS） | 经网关 |
| `question-service` | 8102 | `/api` | 题目 CRUD、提交记录、判题调度 | 经网关 |
| `judge-service` | 8103 | `/api` | 判题、调用沙箱、回写结果 | ❌ 仅 inner |
| `common` | — | — | 工具、异常、常量、AOP、全局配置 | 库 |
| `model` | — | — | Entity / DTO / VO / 枚举 | 库 |
| `service-client` | — | — | Feign 契约（只放接口 + HTTP 注解） | 库 |

> 端口规划：8001 对外，8101~8103 对内，8080 被沙箱/Sentinel 占用。
> 改端口会同时影响 Nacos 注册、网关路由和前端配置，**不要随手改**。

---

## 3. 运行时视图

### 3.1 南北向：外部请求

```
浏览器 → 网关(8001) → 路由匹配 → lb:// 选实例 → 业务服务(810x)
```

- 路由：
  - `/api/user/**`、`/api/file/**` → `haxoj-backend-user-service`
  - `/api/question/**`、`/api/question_submit/**` → `haxoj-backend-question-service`
  - `/api/judge/**` **刻意不配**（judge 无对外接口）
- 跨域：网关统一处理（`globalcors`），下游不再配置。
- 超时：连接 5s、响应 30s。
- 文档：Knife4j 通过 `discover` 聚合，入口 `/doc.html`（生产应关闭）。

### 3.2 东西向：服务间调用

全部走 `service-client` 里的 Feign 契约：

| 调用方 | 契约 | 提供方 | 用途 |
|---|---|---|---|
| question | ~~`JudgeFeignClient`~~ | judge | **已改为 MQ 触发**，Feign 契约保留但不再用于提交链路 |
| question | `UserFeignClient` | user | 补全提交/题目的用户信息 |
| judge | `QuestionFeignClient` | question | 读提交/题目、回写状态与通过数、原子抢占 |

契约方法只保留"别的服务真正会用到"的少数几个，路径统一为 `/api/inner/{service}/**`。

### 3.3 一次判题的链路

```
前端 POST /api/question_submit/
  → question-service 落库（状态：WAITING）
  → 发 MQ 消息（haxoj.judge.exchange / judge.submit，消息体只带 submitId）
  → judge-service 消费：
       ① 幂等检查 + 原子抢占（WAITING → RUNNING）
       ② Feign 读 submission / question
       ③ HTTP POST 沙箱 /sandbox/execute（带鉴权头）
       ④ 比对输出，得出通过/失败
       ⑤ Feign 回写提交状态 + 题目通过数
  → 成功 ack；系统异常重置 WAITING + nack → 死信队列
```

> MQ 把「提交」和「判题」解耦：提交接口不再等待判题服务，服务重启也不会丢在途任务
> （消息还在队列里）。幂等靠状态检查 + 原子抢占（见 §4.6）。

---

## 4. 关键设计

### 4.1 网关是唯一入口，服务只监听回环

| 决定 | 理由 | 代价 |
|---|---|---|
| 前端只连 8001 | 入口唯一、跨域/鉴权/限流集中 | 网关成为单点，需要多实例 |
| 业务服务 `server.address: 127.0.0.1` | 外部无法直连业务端口 | **只在单机成立**；服务分机后必须改为内网 IP + 安全组 |

> 回环只限制"别的机器"，不限制"本机其他进程"；inner 的兜底另有过滤器（§4.3）。

### 4.2 认证与会话

```
登录：user-service 写 Session 到 Redis（db1）→ 浏览器拿到 Cookie
请求：任一服务从共享 Session 取 userId → @AuthCheck 校验角色
网关：GlobalAuthFilter 预留统一鉴权入口，开关 haxoj.gateway.auth.enabled 默认 false
```

- 网关是 WebFlux，拿不到 `HttpServletRequest`，服务里 `getLoginUser(request)` **不能直接复用**；
- 打开网关鉴权前，必须先接响应式 Session 或 JWT，并逐个核对白名单。

### 4.3 inner 接口：默认不可达

| 措施 | 说明 |
|---|---|
| 路径收敛 | 从 `/api/user/inner/**` 改为 `/api/inner/{service}/**`，天然落不进任何对外路由 |
| 网关过滤器 | `GlobalAuthFilter` 对两种形状都返回 **404**（不暴露存在性） |
| 不开路由 | judge 没有 `/api/judge/**` 路由 |
| 网络 | 服务只监听回环（单机） |

> inner 接口**免鉴权**是设计前提（Feign 没有用户 Session），所以安全依赖"外部到不了"。
> 详细推导见 [`adr/0002-inner接口不对外.md`](adr/0002-inner接口不对外.md)。

### 4.4 Feign 契约单独成模块

| 为什么 | 说明 |
|---|---|
| 消费方只看一份文件 | 打开 `service-client` 就知道能远程调什么 |
| 避免循环依赖 | 契约不依赖实现，提供方写 InnerController 满足契约 |
| 不共用领域接口 | `IService` 方法没有 HTTP 注解，Feign 无法翻译 |

### 4.5 代码沙箱独立部署

- 沙箱在虚拟机 `192.168.221.45:8080`，通过 `/sandbox/execute` 调用；
- 请求带 `auth` 头（密钥的 MD5），防止裸调；
- 沙箱负责编译/运行用户代码、资源限制、黑白名单；
- 判题服务只负责"调"和"比对结果"。

### 4.6 判题异步化：RabbitMQ

| 项 | 值 |
|---|---|
| 交换机 | `haxoj.judge.exchange`（direct） |
| 队列 | `haxoj.judge.queue`（durable，绑定 DLX） |
| 路由键 | `judge.submit` |
| 死信 | `haxoj.judge.dlx` → `haxoj.judge.dlq` |
| 消息体 | `JudgeMessage { questionSubmitId, timestamp }`（只带 id） |
| 确认 | 手动 ack；prefetch=1 |

**三种消费结果**

| 情况 | 处理 |
|---|---|
| 成功 | `basicAck` |
| 业务异常（提交不存在等） | `basicAck`（重试无意义，避免毒消息） |
| 系统异常（沙箱挂了/网络） | 状态重置为 WAITING + `basicNack(requeue=false)` → DLQ |

**幂等**

- 先检查状态：非 WAITING 直接跳过；
- 再原子抢占：`UPDATE ... SET status=RUNNING WHERE id=? AND status=WAITING`；
- 只有抢占成功的消费者继续判题。

> 生产端开了 `publisher-confirm-type: correlated` + `publisher-returns`，
> 发送失败会记日志；消费端异常分类是这一块最关键的设计。
> 完整原理与排错见 `docs/notes/2026-10-22_RabbitMQ异步判题.md`。

---

## 5. 数据与配置

| 项 | 值 | 说明 |
|---|---|---|
| MySQL | `localhost:3306/my_db` | 三个服务共用同一个库（**微服务里属于反模式**，见 §7） |
| Redis | `localhost:6379` db1 | 共享 Session；后续可做缓存/限流 |
| RabbitMQ | `127.0.0.1:5672`（SSH 隧道到 VM） | 判题消息；管理台 `15672` |
| Nacos | `127.0.0.1:8848`，namespace `public` | 仅服务发现，**未启用配置中心** |
| Sentinel | dashboard `localhost:8080` | 依赖已引入，**未配规则** |
| Knife4j | user/question 开启 | 网关聚合在 `/doc.html` |

**敏感配置**：数据库密码走 `${MYSQL_PASSWORD:}` 环境变量；沙箱密钥目前硬编码在代码里（待外置，见 §7）。

---

## 6. 部署视图

### 6.1 本地开发（当前）

```
一台 Windows：
  Nacos + MySQL + Redis
  user/question/judge（127.0.0.1:8101-8103）
  gateway（0.0.0.0:8001）
  5672/15672 ← SSH 隧道到 VM
虚拟机：
  code-sandbox（192.168.221.45:8080）
  rabbitmq（Docker，--network host）
```

### 6.2 生产（目标）

| 组件 | 部署 |
|---|---|
| 网关 | 公网/负载均衡后面，多实例 |
| 业务服务 | 内网，绑内网 IP，安全组只放行网关 |
| MySQL/Redis/Nacos | 内网，不对公网 |
| 沙箱 | 独立隔离环境，只允许判题服务访问 |

> 从单机切到多机时，**必须同步改**：`server.address`、`nacos.discovery.ip`、安全组规则。

---

## 7. 已知限制与技术债

| # | 问题 | 影响 | 计划 |
|---|---|---|---|
| 1 | 三个服务共用一个数据库 | 不能独立演进，join 耦合 | 先分表，后分库 |
| 2 | 网关鉴权只有骨架 | 未登录请求依赖各服务自己拦 | 第 2 步接入 |
| 3 | inner 无内部凭证 | 网络被打穿即裸奔 | `X-Internal-Token` 或 mTLS |
| 4 | 沙箱地址/密钥硬编码 | 换环境要改代码 | 移到配置 + 环境变量 |
| 5 | 测试很少 | 改动靠手测 | 先补核心链路测试 |
| 6 | 无链路追踪 | 跨服务排错靠日志 | 引入 traceId |
| 7 | 发送消息与落库非原子 | 落库成功、发消息失败会留下 WAITING | 本地消息表 + 定时补偿 |

---

## 8. 文档与决策

| 文档 | 内容 |
|---|---|
| [`../README.md`](../README.md) | 项目介绍、快速开始 |
| [`../AGENTS.md`](../AGENTS.md) | AI/新人的工作手册 |
| [`conventions.md`](conventions.md) | 开发规范 |
| [`adr/`](adr/) | 关键决策记录 |
| [`notes/`](notes/) | 历史实现记录 |

**变更记录**

| 日期 | 变更 |
|---|---|
| 2026-10-22 | 判题改为 RabbitMQ 异步（DLQ + 幂等抢占）；建立 docs 体系 |
| 2026-10-21 | 网关过滤器合并为 `GlobalAuthFilter`（inner 404 + 鉴权骨架） |
| 2026-10-18 | inner 路径收敛；网关路由/跨域/文档聚合落地 |
| 2026-10-17 | Feign 契约与跨服务调用打通 |
| 2026-10-15 | 单体拆分为 7 模块 |
