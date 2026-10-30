# 改造日志

> 这份文档回答：**这个项目一共改了什么、什么时候改的、验证到什么程度。**
>
> 与 `notes/` 的分工：本篇是项目级时间线，只写「做了什么 + 结果如何 + 细节在哪」；
> 单次改造的技术推导、踩坑记录和代码讨论在 `notes/` 的对应笔记里，本篇不重复论证。
> 两处若表述冲突，**以笔记为准**（笔记是当时的第一手记录），并应修正本篇。
>
> 维护方式：新改造加在最前面，时间倒序；旧条目结论有误**就地修正并标注核对日期**，不另起新文档重写（历史交给 git）。
> 当前遗留问题见 [known-issues.md](./known-issues.md)，后续计划见 [roadmap.md](./roadmap.md)。
>
> 最后核对：2026-10-27。

## 0. 怎么读

| 标记 | 含义 |
|---|---|
| **实测** | 核对时实际执行过命令、读过代码或看过输出 |
| **据笔记** | 来自 `docs/notes/` 或 git 提交记录，本次未重新复现 |
| **未验证** | 没有可执行证据，只有推断 |

时间线用 **git 提交日期**。`notes/` 的文件名日期与提交日期可能差一天（例如 MQ 笔记命名为 10-22，提交在 10-23），引用笔记时保留原文件名，不做「纠正」。

## 1. 总览

| 日期 | 主题 | 影响范围 | 细节 | 验证 |
|---|---|---|---|---|
| 2026-10-30 | 前端提交记录页 + API 生成链修复 + 用户中心接真实数据 | haxoj-frontend | 本篇 §2 | **实测**：生成链路实跑 + type-check + build；判题端到端未验证 |
| 2026-10-27 | 判题正确性与幂等修复 | judge / question / service-client / model / 前端做题页 | [笔记](./notes/2026-10-27_判题正确性与幂等修复.md) | **实测**：全量构建 + 9 个单测 + 前端 type-check |
| 2026-10-22 ~ 10-23 | 文档体系、网关过滤器合并、RabbitMQ 异步判题 | 全仓库 / gateway / question / judge | [MQ 笔记](./notes/2026-10-22_RabbitMQ异步判题.md) | **据笔记**：MQ 实测记录见笔记 §5 |
| 2026-10-18 | 网关接入、inner 收口、服务只监听回环 | gateway / 三个业务服务 / 前端 | [网关笔记](./notes/2026-10-18_微服务网关接入.md)、[文档聚合](./notes/2026-10-18_Knife4j网关文档聚合.md) | **据笔记** |
| 2026-10-16 | 微服务七模块初始化；前端基础功能与 API 代码生成 | 后端全量 / 前端 | [多模块](./notes/2026-10-15_Maven多模块微服务.md)、[拆分](./notes/2026-10-15_微服务代码拆分与ServiceClient.md) | **据笔记** |
| 2026-10-08 ~ 10-10 | Docker 沙箱落地、两端联调 | haxoj-code-sandbox / 判题链路 | [联调设计](./notes/2026-10-10_代码沙箱两端联调设计.md) | **据笔记** |
| 2026-09-23 ~ 09-24 | 沙箱工程起步与 Docker 连通性验证 | haxoj-code-sandbox | 无 | **据提交记录** |

更早的 2026-09 上旬至中旬（前端工程化、API 代码生成、Monaco 集成、创建题目、题库缓存、判题链路梳理、SecurityManager 实验）发生在单体阶段，见 §9。

---

## 2. 2026-10-30 前端提交记录页

| 项 | 内容 |
|---|---|
| 状态 | **工作区未提交**（haxoj-frontend 仓库） |
| 影响范围 | haxoj-frontend（路由 / 页面 / 组件 / 工具函数）+ 本文档同步 |
| 细节 | 无独立笔记（页面结构与口径见本节与代码注释） |

**背景与问题**

做题页提示「可前往提交记录查看判题结果」，但这个页面并不存在；用户中心那张"提交记录"是写死的假数据（见 [known-issues.md](./known-issues.md) `FEAT-1`）。判题改为 MQ 异步之后，用户没有任何地方能看到「我的提交现在怎么样了」。

**改了什么**

| 改动 | 落点 |
|---|---|
| 新增路由 `/submissions`（需登录，顶栏菜单自动出现） | `src/router/routes.ts` |
| 提交记录页：题目 / 状态 / 语言 / 耗时 / 内存 / 提交时间，服务端分页（上限 20），按状态筛选 | 新增 `src/views/Submissions.vue` |
| 存在待判题/判题中记录时每 3 秒自动刷新，最多 60 秒，全部出结果即停 | 同上 |
| 点行打开详情抽屉：只读回看提交源码与判题信息 | 同上；`MonacoEditor` 新增 `readonly` 属性 |
| 状态文案、判题结论中文映射、内存/耗时格式化的单一来源 | 新增 `src/utils/submission.ts`；做题页本地 `formatMemory` 改为复用 |
| 做题页提交成功文案改为指向真实入口 | `src/views/ProblemDetail.vue` |

**怎么验证**

| 验证 | 命令 | 结果 |
|---|---|---|
| 类型检查 | `npm run type-check` | 通过（**实测**） |
| 生产构建 | `npm run build-only` | 通过（**实测**）。`submission` chunk 约 3.9MB，内含 Monaco，按路由懒加载，仅访问该页时下载 |
| 端到端 | — | **未验证**：需要 Nacos/MySQL/Redis/RabbitMQ + VM 沙箱在线 |

**影响面与回退**

- 接口层零改动：前端生成的 `listMyQuestionSubmitVoByPageUsingPost` 与后端 `/question_submit/my/list/page` 本来就对齐，**没有执行 `npm run api:generate`**——该脚本拉的是 user-service 文档，执行会删掉题库接口（`ENG-2`）。
- `User.vue` 的 mock 提交记录本次未动（用户选择只做独立页），作为 `FEAT-1` 的残留记录在案。
- 回退：删除 `Submissions.vue`、`utils/submission.ts`，还原 `routes.ts` 与 `ProblemDetail.vue` 两处即可。

**同日追加（2026-10-30 下午）**

上面交付后，用户提出三件事：生成脚本只拉 8101 早就该修、用户中心还是假数据、新页面的技术点要写进文档库。

| 改动 | 落点 |
|---|---|
| API 生成链改为「双服务拉取 + 脚本内合并」 | 新增 `scripts/fetch-api-docs.cjs`、`scripts/generate-api.cjs`；改 `package.json` scripts 与 `.gitignore`；重写 `API_GENERATION_GUIDE.md` |
| 用户中心接真实数据 | `User.vue` 改为 `get/login` + 最近 5 条 `my/list/page`，删除无接口支撑的「邮箱」「解题数」 |
| 页面技术点归档 | 新增 `docs/anatomy/提交记录页.md`（轮询、分页上限、状态口径、抽屉生命周期） |
| 修复生成顺序陷阱 | `generate-api.cjs` 内置调用 postprocess；`package.json` 去掉重复步骤。详见下方「事故记录」 |

**怎么验证**

| 验证 | 结果 |
|---|---|
| `npm run api:generate` | 通过（**实测**）：user 13 paths/21 definitions，question 12/27；合并后 43 models / 4 services |
| 产物差异 | 只删除 `Post*`（15 个 model）与 `Post*`/`WxMp*`（4 个 service）等死代码；`UserControllerService` 仅少一个微信登录方法（当前后端无此接口） |
| 失败路径 | 把 question 地址指向空端口 → 退出码 1 + 可操作提示（**实测**，未停用户正在跑的服务） |
| `npm run type-check` / `build-only` | 通过（**实测**） |
| `/submissions` 端到端 | **未验证**（判题仍需 MQ 与沙箱，当前 5672 未通） |
| 网关路径实测 | `POST :8001/api/question_submit/my/list/page` → **HTTP 200 + `code:40100`（未登录）**，确认路径不再 404（**实测**） |

**事故记录：一次"能编译但全站 404"的坑**

- 现象：浏览器请求路径变成 `/api/api/question_submit/my/list/page`，返回 Spring 默认 404 JSON。
- 根因：生成器的原始 `BASE` 是「服务 host + basePath」= `http://localhost:8101/api`，与 paths 自带的 `/api` 拼成双前缀。
  正常流程靠 `postprocess-api.cjs` 把它改成读 `VITE_API_BASE_URL`；但后处理当时**只挂在 npm script 上**，
  调试时单独执行 `node scripts/generate-api.cjs` 就把修好的 `BASE` 覆盖回原值。
- 为什么难发现：`type-check`、`build` 全部通过——这是运行时配置问题，不是类型或构建问题。
- 修法：把 postprocess 内置进 `generate-api.cjs` 末尾，单独跑生成也必然得到可用产物；
  `package.json` 去掉重复步骤；`API_GENERATION_GUIDE.md` 的排错表补上这个症状。

**影响面与回退**

- 生成脚本不再依赖 `curl` 与固定端口，地址可用 `API_DOC_USER_URL` / `API_DOC_QUESTION_URL` 覆盖。
- 脚本内置 `/api` 前缀断言：换成经网关的文档源会直接失败，避免全站 404。
- 回退：`git checkout -- src/api` 恢复生成产物；删除两个新脚本并还原 `package.json` 即回到旧链路。

---

## 3. 2026-10-27 判题正确性与幂等修复

| 项 | 内容 |
|---|---|
| 状态 | **工作区未提交**（`git status` 全部为已修改/未跟踪） |
| 影响范围 | `judge-service`、`question-service`、`service-client`、`model`、`ProblemDetail.vue` |
| 细节 | [2026-10-27_判题正确性与幂等修复.md](./notes/2026-10-27_判题正确性与幂等修复.md) |

**背景与问题**

接手后逐文件核查判题链路，发现三处会给出与事实不符的结论，外加一处会把提交永久卡住：

1. 前端可选 C++/Go，但沙箱只实现 Java。沙箱对非 java 返回 `status=2`（平台故障语义），判题侧据此判成「系统错误」——用户会等平台恢复，而实际该换语言。
2. Java 超时判定写成 `(time - 10000) > timeLimit`。沙箱自身执行上限是 5000ms，这个式子永远不成立，TLE 这个结论从未出现过。同一处还有四处空指针路径（memory / memoryLimit / judgeConfig / 预期输出为 null）。
3. 「写状态」和「通过数 +1」是两次 Feign 调用。中间失败就会重试，而 +1 不幂等，重放一次就多加一次。
4. 消费者对 `BusinessException` 只 ack 不落定。异常若发生在抢占（WAITING→RUNNING）之后，提交永久停在「判题中」。

**改了什么**

| 改动 | 落点 |
|---|---|
| 判定逻辑去重，判定顺序改为 内存 → 时间 → 条数 → 内容 | 新增 `AbstractJudgeStrategy`，两个策略退化成「只说宽限多少」 |
| 时间宽限改为 `timeLimit + 1000ms`（Java）/ `+ 0`（默认） | `JavaLanguageJudgeStrategy` 常量 `JVM_STARTUP_ALLOWANCE_MS` |
| 空值语义统一为「未设置限制即不判」 | `AbstractJudgeStrategy` |
| 无用例的题目判 `System Error`（原来 `0 == 0` 会放行成 Accepted） | `AbstractJudgeStrategy` |
| 状态 + 通过数改为单事务落定 | 新增 `QuestionSubmitFinishRequest`；`finishQuestionSubmit` 条件更新 `WHERE status=RUNNING`，成功且通过才 +1 |
| 删除 `updateQuestionAcceptedNum` 的内外两端 | `QuestionFeignClient`、`QuestionInnerController` |
| 业务异常先落定 `FAILED + System Error` 再 ack | `JudgeMessageConsumer.markFailed` |
| 语言只保留 Java | 前端删两个选项；提交入口拦截非 Java，返回 `PARAMS_ERROR`「当前仅支持 Java 提交」 |

**怎么验证**

| 验证 | 命令 | 结果 |
|---|---|---|
| 全量构建 | `mvn -DskipTests package` | 8 个模块 BUILD SUCCESS（**实测**） |
| 单测 | `mvn -pl haxoj-backend-judge-service -am test "-Dtest=AbstractJudgeStrategyTest" "-Dsurefire.failIfNoSpecifiedTests=false"` | `Tests run: 9, Failures: 0, Errors: 0`（**实测**） |
| 前端类型 | `npm run type-check` | 通过（**实测**） |
| 端到端判题 | — | **未验证**：需要 Nacos / MySQL / Redis / RabbitMQ / VM 沙箱同时在线 |

单测覆盖：成功、答案错误、内存溢出、Java 宽限边界（等于不超、多 1ms 超）、默认策略无宽限、配置缺失、预期输出为 null、time/memory 为 null、无用例。

**影响面与回退**

- 契约是删除式变更，**question-service 与 judge-service 必须同时重启**。只重启 judge 会让它打到已删除的 `/question_submit/update/accepted_num`，404 后每次判题都进死信。
- 队列里的在途消息不需要清：新代码用 finish 会把停在 RUNNING 的提交落定为终态。
- 回退需两个服务同时回滚，不能只回滚一个。

---

## 4. 2026-10-22 ~ 10-23 文档体系、网关过滤器合并、RabbitMQ 异步判题

| 项 | 内容 |
|---|---|
| 状态 | 已提交（`d578213`、`7856284`、`eb25481`、`60bf7fe`、`473dedc`） |
| 影响范围 | 全仓库文档 / `gateway` / `question-service` / `judge-service` |
| 细节 | [RabbitMQ 异步判题](./notes/2026-10-22_RabbitMQ异步判题.md) |

**背景与问题**

提交接口原先用 `CompletableFuture.runAsync(() -> judgeFeignClient.doJudge(id))` 触发判题：判题服务重启或变慢会直接影响提交接口；服务重启时在途任务丢失；提交接口依赖判题服务的地址。

同时，上一轮判题链路存在三处错误：重复消费会把同一份提交判两次、异常分类把「沙箱挂了」当成业务失败 ack 掉、编译失败被吞成「答案错误」。

文档方面，ADR、架构说明、开发规范散落在对话与笔记里，没有统一入口。

**改了什么**

| 改动 | 落点 |
|---|---|
| 判题改 MQ 触发 | `MqConstants`、`JudgeMessage`、两个 `RabbitMqConfig`、`JudgeMessageProducer`、`JudgeMessageConsumer` |
| 幂等 | 状态检查 + `claimQuestionSubmit`（`WHERE status=WAITING` 原子抢占） |
| 异常分类 | 业务异常 ack、系统异常重置 WAITING + `nack(requeue=false)` → DLQ |
| 网关过滤器合并 | `InnerPathBlockFilter` 并入 `GlobalAuthFilter`（inner 404 + 鉴权骨架） |
| 文档体系 | `AGENTS.md`、`docs/architecture.md`、`docs/conventions.md`、`docs/adr/`、`docs/notes/` |

**怎么验证**

**据笔记** §5：

- 队列 `haxoj.judge.queue` 0 条、1 个消费者；`haxoj.judge.dlq` 1 条（故障演练残留）。
- 提交后状态 `0 WAITING → 1 RUNNING → 3 FAILED`，`judgeInfo` 为 `{"message":"Wrong Answer","memory":0,"time":0}`。
- 停沙箱 → 消费者捕获异常 → 重置 WAITING → 消息进 DLQ。

本次未重新复现（未启动 MQ 与业务服务）。

**影响面与回退**

- 回退到 Feign 触发需要恢复 `CompletableFuture` 调用并停掉消费者；消息拓扑可以保留（不消费即不影响）。
- DLQ 里的消息目前只能人工处理，没有重放工具（见 [known-issues.md](./known-issues.md)）。

---

## 5. 2026-10-18 网关接入与 inner 收口

| 项 | 内容 |
|---|---|
| 状态 | 已提交（`3bf776d`、`fce34fd`、`91d9fc1`、`f80b6a8` 等） |
| 影响范围 | `gateway`、三个业务服务的网络绑定、前端 API 地址 |
| 细节 | [微服务网关接入](./notes/2026-10-18_微服务网关接入.md)、[Knife4j 网关文档聚合](./notes/2026-10-18_Knife4j网关文档聚合.md) |

**背景与问题**

拆成微服务后，三个业务端口直接暴露；跨域配置分散在各个服务；内部接口（Feign 用）与对外接口混在同一路径前缀下，外人可以直接调内部接口。

**改了什么**

- 引入 Spring Cloud Gateway（WebFlux），前端只认 `8001`。
- 路由：`/api/user/**`、`/api/file/**` → user；`/api/question/**`、`/api/question_submit/**` → question；**judge 刻意不开路由**。
- 跨域统一在网关（`globalcors`），下游不再配。
- inner 路径从 `/api/user/inner/**` 收敛到 `/api/inner/{service}/**`，网关 `GlobalAuthFilter` 对两种形状都返回 404。
- 三个业务服务 `server.address: 127.0.0.1` + `nacos.discovery.ip: 127.0.0.1`。
- Knife4j 用 `discover` 聚合到 `/doc.html`；子服务文档地址与真实端点差一层，用两条 `RewritePath` 对齐。
- 前端 `VITE_API_BASE_URL` 切到 8001，同时修掉 12 个类型错误。

**怎么验证**

**据笔记**：路由与跨域按 curl 验证过；文档聚合入口 `/doc.html` 可列出 user / question 两组；`/api/inner/**` 返回 404。

**影响面与回退**

- `server.address: 127.0.0.1` 只在单机成立。它挡住的是**别的机器**，挡不住**同一台机器上的任何进程**——这一点在 10-27 核对时被明确指出，是结构性问题的来源之一（见 [known-issues.md](./known-issues.md)）。
- 迁到多机必须同步改 `server.address`、`nacos.discovery.ip` 和安全组。
- 回退：前端指回各服务端口，但 inner 路径收敛无法简单回退（三个服务的 `@RequestMapping` 都改了）。

---

## 6. 2026-10-16 微服务七模块初始化；前端基础功能与 API 代码生成

| 项 | 内容 |
|---|---|
| 状态 | 已提交（`b9a335b` 之前的初始化提交） |
| 影响范围 | 后端全量、前端工程 |
| 细节 | [Maven 多模块微服务](./notes/2026-10-15_Maven多模块微服务.md)、[代码拆分与 ServiceClient](./notes/2026-10-15_微服务代码拆分与ServiceClient.md)、[Feign 与服务契约](./notes/2026-10-17_Feign声明式服务调用与service-client.md) |

**背景与问题**

起点是单体 `haxoj-backend`（课程模板改造而来，包含帖子/点赞/收藏等无关模块）。单体里判题、用户、题目代码耦合在同一个进程，判题执行用户代码与业务服务共享资源，无法独立扩容，也无法把判题服务单独放到更强/更隔离的机器上。

**改了什么**

- 拆成 7 个 Maven 模块：`common` / `model` / `service-client` / `gateway` / `user-service` / `question-service` / `judge-service`。
- 父 pom 统一版本：Spring Boot 2.6.13、Spring Cloud 2021.0.5、Spring Cloud Alibaba 2021.0.5.0；Lombok 显式锁 1.18.30（旧版在 JDK 21 上注解处理器会抛 `NoSuchFieldError`）。
- 契约单独成模块：Feign 接口只放 HTTP 注解，提供方写 `InnerController` 对齐而不 `implements`，避免循环依赖。
- 前端：完成登录注册与菜单；用 OpenAPI 生成 `src/api` 的模型与服务层；新增题目管理页、Monaco 代码编辑器、ByteMD Markdown 编辑器。

**怎么验证**

**据笔记**：`mvn clean install -DskipTests` 构建通过；Feign 调用打通（笔记中有调试过程）。前端类型检查在 10-18 修掉 12 个错误后通过。

**影响面与回退**

- 拆分是一次性大改，无法部分回退；旧单体 `haxoj-backend` 目录仍保留，但已不作为运行目标（也是建表 SQL 的唯一来源）。
- `service-client` 里的契约成为跨服务唯一入口，改契约要同步「契约 + inner 实现 + 调用方」。

---

## 7. 2026-10-08 ~ 10-10 Docker 沙箱落地与两端联调

| 项 | 内容 |
|---|---|
| 状态 | 已提交（`a10bb98`、`9ea0afe`，10-08 两个提交） |
| 影响范围 | `haxoj-code-sandbox`、判题链路 |
| 细节 | [代码沙箱两端联调设计](./notes/2026-10-10_代码沙箱两端联调设计.md) |

**背景与问题**

本地沙箱（`JavaNativeCodeSandbox1`）直接在宿主机跑用户代码，能读写宿主文件系统、联网、吃满 CPU 和内存。判题系统不能这样跑别人的代码。

**改了什么**

- 抽出 `JavaCodeSandboxTemplate`（模板方法）：存代码 → 编译 → 执行 → 整理输出 → 清理，只把「怎么执行」留成 `runFile()`。
- 新增 `JavaDockerCodeSandbox`：容器四道锁（内存上限、CPU 核数、断网、只读根文件系统）+ `statsCmd` 采集内存峰值 + `finally` 删容器。
- `withMemorySwap` 与内存上限同量，禁止用 swap 顶替（否则内存超了会滑到硬盘上慢慢跑，永远等不到超时）。
- 输出末尾换行统一去掉，否则每道题都会判「答案错误」。
- 编译失败归为 `status=3`（用户代码问题）而不是 `status=2`（平台故障）。
- 沙箱对外暴露 `POST /sandbox/execute`，请求头 `auth = MD5("secretKey")`。

**怎么验证**

**据笔记**：两端联调跑通；`MemoryError` / `SleepError` / `WriteFileError` 三类测试代码的行为有记录；末尾换行问题是在联调中定位并修掉的（笔记 §十）。

**影响面与回退**

- 沙箱成为独立部署单位（VM `192.168.221.45:8080`），换环境要改 `RemoteCodeSandbox.SANDBOX_EXECUTE_URL`（硬编码，见 known-issues）。
- 沙箱 jar 必须在 VM 上重新 `mvn package` 并重启，否则会出现「接口 404 但进程活着」。
- 本地沙箱仍保留，仅用于调试；它没有超时、没有黑名单、没有 SecurityManager。

---

## 8. 2026-09-23 ~ 09-24 沙箱工程起步

| 项 | 内容 |
|---|---|
| 状态 | 已提交（`第一次提交`、`Add README.md`、`feat: 新增 Docker 连通性测试`） |
| 影响范围 | `haxoj-code-sandbox` |
| 细节 | 无独立笔记 |

**背景与问题**

需要一个能在隔离环境里编译、运行 Java 源码并回收输出的服务。起点是课程示例：单文件 `main` 方法 + 手写 `Runtime.exec`。

**改了什么**

- 建立 Spring Boot 工程，定义 `CodeSandbox` 接口与 `ExecuteCodeRequest` / `ExecuteCodeResponse` / `ExecuteMessage`。
- 注册 `DockerClient` Bean，提供 `/docker/ping` 与 `/docker/test` 两个连通性接口（拉 nginx 镜像验证链路）。
- 四个 `SecurityManager` 演示类（`Default` / `Deny` / `My` / `Test`）——**均未挂载到运行链路**，属于学习产物。

**怎么验证**

**据提交记录**：`/docker/ping`、`/docker/test` 在 VM 上跑通。本次未复现。

**影响面与回退**

- `DockerTestController` 是临时接口，跑通后可删（仍在仓库里）。
- `security/` 目录不参与判题，保留会让人误以为有权限隔离。

---

## 9. 更早：单体阶段（2026-09 上旬 ~ 中旬）

这一阶段的代码不在微服务仓库里（`haxoj-backend` 无版本控制），只有笔记可查：

| 日期 | 笔记 | 主题 |
|---|---|---|
| 09-08 | [前端构建工具](./notes/2026-09-08_前端构建工具.md) | Vite 工程化 |
| 09-09 | [API 代码生成](./notes/2026-09-09_API代码生成.md) | OpenAPI → 前端请求代码 |
| 09-09 | [Monaco 编辑器集成](./notes/2026-09-09_Monaco编辑器集成.md) | 代码编辑器接入 |
| 09-09 | [创建题目功能](./notes/2026-09-09_创建题目功能.md) | 表单、校验、接口 |
| 09-09 | [运行时调试技巧](./notes/2026-09-09_运行时调试技巧.md) | 前后端联调排错 |
| 09-13 | [题库列表缓存机制](./notes/2026-09-13_题库列表缓存机制.md) | 前端列表缓存 |
| 09-16 | [代码编辑器-语言切换](./notes/2026-9-16_代码编辑器-语言切换.md) | 多语言骨架切换 |
| 09-17 | [判题功能全景解析](./notes/2026-09-17_判题功能全景解析.md) | 一次提交到出结果的完整链路 |
| 09-17 | [字典树与代码沙箱黑白名单](./notes/2026-09-17_字典树与代码沙箱黑白名单.md) | 敏感词结构 |
| 09-17 | [Java 编码问题总结](./notes/2026-09-17_Java编码问题总结.md) | GBK / UTF-8 踩坑 |
| 09-18 | [Java 安全管理器](./notes/2026-09-18_Java安全管理器.md) | JDK 17 弃用、JDK 24 禁用 |

> 09-17 的《判题功能全景解析》第 8 节列出的 5 个严重问题（编译错误误判、时间恒为 0、内存 NPE、安全防护缺失、无超时强杀），是 10-08 起这一系列改造的起点。其中「编译错误」「超时」「内存」三项在 10-27 才真正闭环。

---

## 10. 相关文档

- 文档索引：[README.md](./README.md)
- 系统架构：[architecture.md](./architecture.md)
- 开发规范：[conventions.md](./conventions.md)
- 决策记录：[adr/](./adr/)
- 当前遗留：[known-issues.md](./known-issues.md)
- 后续计划：[roadmap.md](./roadmap.md)
