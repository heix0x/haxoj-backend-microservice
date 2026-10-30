# 已知问题与风险

> 这份文档回答：**现在还有什么问题没解决，严重到什么程度。**
>
> 性质是**快照**，不是长期结论。每条都能复查（文件 + 符号或片段），修复后改状态而不是删掉，
> 便于后来人知道「这里曾经有过什么坑」。
>
> 来龙去脉见 [changelog.md](./changelog.md)，处置顺序见 [roadmap.md](./roadmap.md)。
> 最后核对：2026-10-27。

## 0. 怎么读

**状态**

| 标记 | 含义 |
|---|---|
| 未修 | 核对时代码仍然是这个样子 |
| 已修 | 已改，修复记录见 changelog 对应章节 |
| 未确认 | 有迹象但没验证，不当作事实 |

**级别**

| 级别 | 判据 |
|---|---|
| P0 | 会让用户拿到**错误结论**，或直接暴露数据/服务 |
| P1 | 不会立刻出错，但在并发、重试、扩容时会出错 |
| P2 | 工程债、文档债，不修会持续消耗接手成本 |

ID 是稳定标识（`SEC-3` 这种），排序变化不改 ID —— [roadmap.md](./roadmap.md) 按 ID 引用。

## 1. 结论先行

未修的 P0 有四条：三条在**安全边界**，一条在**判题正确性**。

安全：

1. 登录请求的完整请求体（含明文密码）会写进日志（`SEC-1`）。
2. 网关跨域允许任意来源携带 Cookie（`SEC-2`）。
3. 沙箱密钥硬编码在源码里，且有两个 GET 接口不校验（`SEC-3`）。

判题：

4. Docker 容器内存上限写死 100MB，而题目默认限制是 256MB（`JUD-4`）。配置承诺的资源和实际能用的资源对不上，用户按 256MB 优化永远过不了。

判题正确性的三条 P0（语言误判、超时永不触发、通过数重复累加）已在 2026-10-27 修复，见 §9。

结构性的一条需要单独记住：**服务只监听 `127.0.0.1` 挡的是别的机器，挡不住本机进程**（`STR-2`）。内部接口免鉴权（`SEC-4`）之所以"暂时安全"，靠的是这一层，而不是网络隔离。

## 2. 安全与鉴权

| ID | 级别 | 问题 | 证据 | 影响 | 状态 |
|---|---|---|---|---|---|
| SEC-1 | P0 | 请求日志打印完整参数，登录接口会记录明文密码 | `haxoj-backend-common/.../aop/LogInterceptor.java`：`String reqParam = "[" + StringUtils.join(args, ", ") + "]"`，随后 `log.info(..., params: {})` | 日志被读即泄露凭据；违反项目自己的日志规范（禁止打印完整请求体） | 未修 |
| SEC-2 | P0 | 网关跨域允许任意来源且允许携带凭证 | `haxoj-backend-gateway/src/main/resources/application.yml`：`allowedOriginPatterns: "*"` + `allowCredentials: true` | 任意站点可发起带 Cookie 的请求；配合业务接口无 CSRF 防护，等于把登录态交给第三方页面 | 未修 |
| SEC-3 | P0 | 沙箱鉴权密钥硬编码，且两个调试接口无鉴权 | `SandboxController`：`AUTH_REQUEST_SECRET = "secretKey"`；`RemoteCodeSandbox` 同值。`GET /sandbox/docker`、`GET /sandbox/native` 不校验 `auth` 头 | 拿到源码即可调 `/sandbox/execute`；调试接口可被用来消耗 VM 资源。沙箱 README §7 已自认此问题 | 未修 |
| SEC-4 | P1 | inner 接口无内部凭证 | `docs/architecture.md` §7 第 3 条；三个 `InnerController` 均无鉴权 | 本机任意进程（含被攻陷的其它服务、可 SSRF 的组件）可直调 `127.0.0.1:8101/api/inner/**` | 未修 |
| SEC-5 | P1 | 网关鉴权只有骨架 | `GlobalAuthFilter.isLoggedIn()` 固定 `return false`；开关 `haxoj.gateway.auth.enabled` 默认 `false` | 打开开关会全站 401；不打开则鉴权完全依赖各接口是否记得加 `@AuthCheck` | 未修 |

## 3. 判题正确性

| ID | 级别 | 问题 | 证据 | 影响 | 状态 |
|---|---|---|---|---|---|
| JUD-1 | P0 | 前端可选 C++/Go，沙箱只支持 Java | 曾见 `ProblemDetail.vue` 三个语言选项 + `SandboxController` 非 java 返回 `status=2` | 用户被判「系统错误」，会等平台而不是换语言 | 已修（10-27） |
| JUD-2 | P0 | Java 超时永不触发；四处空指针 | 曾见 `(time - JAVA_PROGRAM_TIME_COST) > needTimeLimit`，常量 10000 > 沙箱上限 5000 | 死循环判不出来；`memory`/`limit`/`judgeConfig`/预期输出为 null 时抛 NPE，提交卡住 | 已修（10-27） |
| JUD-3 | P0 | 题目无测试用例时判 Accepted | `outputList.size() == inputList.size()`，两者都是 0 | 任何代码都能通过，比判错更糟 | 已修（10-27） |
| JUD-4 | P0 | Docker 容器内存上限 100MB，题目默认 memoryLimit 256MB | `JavaDockerCodeSandbox.createAndStartContainer`：`hostConfig.withMemory(100 * 1000 * 1000L)`；`CreateQuestion.vue` 默认 `memoryLimit: 262144`（KB） | 配置写着 256MB、实际 100MB 就被杀。用户按 256MB 优化内存，永远过不了 | 未修 |
| JUD-5 | P1 | 沙箱 `status=3` 混着编译失败、运行时异常、超时 | `JavaCodeSandboxTemplate.collectOutput` 与编译失败分支都写 3；`JudgeServiceImpl.buildFailureJudgeInfo` 只能靠 message 里是否含「执行超时」区分，其余归编译错误 | 运行时异常会显示「编译错误」，用户去补 import 而不是修数组越界 | 未修 |
| JUD-6 | P1 | native 沙箱没有超时 | `JavaNativeCodeSandbox1.runFile`：`TIME_OUT` 与超时线程均被注释；`ProcessUtils.runProcessAndGetMessage` 用无上限的 `waitFor()` | 死循环会占满沙箱服务线程。生产走 Docker 模式，此路径仅在 `?type=native` 或本地调试时可达 | 未修 |

## 4. 一致性与并发

| ID | 级别 | 问题 | 证据 | 影响 | 状态 |
|---|---|---|---|---|---|
| CON-1 | P0 | 通过数重复累加 | 原先是「写状态」+「通过数 +1」两次 Feign 调用 | 重放或重试会让通过率虚高，且不报错 | 已修（10-27） |
| CON-2 | P0 | 抢占后抛业务异常导致永久 RUNNING | `JudgeMessageConsumer` 的 `catch (BusinessException)` 只 `basicAck` | 前端永远转圈，且没有消息能再触发判题 | 已修（10-27） |
| CON-3 | P1 | `submitNum + 1` 用 JVM 内锁 + 读改写 | `QuestionSubmitServiceImpl`：`synchronized (this)` 块内 `question.getSubmitNum() + 1` 再 `updateById` | 多实例部署时丢更新；单实例下也会被别的写路径覆盖 | 未修（已加 TODO） |
| CON-4 | P1 | 落库与发 MQ 非原子 | `doQuestionSubmit`：先 `this.save()`，再 `judgeMessageProducer.sendJudgeMessage()`，无本地消息表 | 发送失败会留下永久 WAITING 的提交，没有任何补偿任务 | 未修 |
| CON-5 | P2 | DLQ 没有重放工具 | `haxoj.judge.dlq` 只进不出；笔记 §7 已列为待办 | 故障恢复需要人工在管理台逐条重投 | 未修 |

## 5. 功能缺口

这类不是"坏了"，是"说好了但没做"。它们不影响判题正确性，但直接影响用户对系统的信任。

| ID | 级别 | 问题 | 证据 | 影响 | 状态 |
|---|---|---|---|---|---|
| FEAT-1 | P1 | 提交记录页缺失/假数据 | 原状：`User.vue` 注释「模拟提交记录数据」，做题页提示指向不存在的页面。**10-30 已新增独立页 `/submissions`**（真实接口 + 详情抽屉），同日下午 `User.vue` 改为真实 `get/login` + 最近 5 条 `my/list/page`，删除无接口支撑的「邮箱」「解题数」 | 提交历史与用户信息均有真实数据来源 | 已修（10-30） |
| FEAT-2 | P2 | 比赛、排名、系统设置为占位页 | `Contests.vue`、`Ranking.vue` 只有标题与一句说明；`AdminPlaceholder.vue` 注明「后端暂未实现」 | 导航里有入口，点进去是空壳 | 未修（有意占位） |

## 6. 工程与文档债

| ID | 级别 | 问题 | 证据 | 影响 | 状态 |
|---|---|---|---|---|---|
| ENG-1 | P1 | 仓库缺 `question` / `question_submit` 建表 SQL | `haxoj-backend/sql/create_table.sql` 只有 `user` / `post` / `post_thumb` / `post_favour` 四张模板表 | 新人无法从零启动；表结构只能从实体类反推 | 未修 |
| ENG-2 | P1 | 前端 API 生成脚本只拉 user-service 文档 | 原状：`api:download` 只请求 8101。**10-30 改为** `scripts/fetch-api-docs.cjs` 拉两份 spec + `scripts/generate-api.cjs` 合并，并加 `/api` 前缀断言 | 重新生成会丢掉题库接口的问题已消除 | 已修（10-30） |
| ENG-3 | P2 | `JudgeManager` 标了 `@Service` 却被 `new` 出来 | `JudgeManager` 类上有 `@Service`；`JudgeServiceImpl` 中 `new JudgeManager().doJudge(...)` | 容器里有一个永远不用的 Bean；策略无法注入依赖 | 未修 |
| ENG-4 | P2 | judge-service 引入 MyBatis/MySQL 但没有任何 Mapper | `haxoj-backend-judge-service/pom.xml` 有 `mybatis-plus-boot-starter` 与 `mysql-connector-j`；`application.yml` 有 datasource | 误导读者以为判题服务会直接写库；多一份无用的连接配置 | 未修 |
| ENG-5 | P2 | 每次判题新建 `DockerClient` | `JavaDockerCodeSandbox.createDockerClient()` 每次 `runFile` 调用一次；`config/DockerConfig` 的 Bean 未被使用 | 多一次建连开销；连接数随并发线性增长 | 未修 |
| ENG-6 | P2 | 编译在宿主机执行，无隔离 | `JavaCodeSandboxTemplate.compileCode()` 用 `Runtime.exec("javac ...")` 在宿主机跑 | `javac` 会加载注解处理器，等于在宿主机执行了用户可控的代码 | 未修 |
| ENG-7 | P2 | 测试仍然很少 | 目前只有 `AbstractJudgeStrategyTest`（9 个用例）；判题状态流转、权限、inner 拦截均无测试 | 改动依赖手测；回归全靠人记得点哪里 | 部分改善（10-27） |
| ENG-8 | P2 | 临时调试接口仍在仓库 | `DockerTestController`：`/docker/test` 会拉 nginx 镜像 | 误调用会消耗磁盘与带宽 | 未修 |
| DOC-1 | P2 | 长期记忆文件已过期 | `.workbuddy/memory/MEMORY.md` 仍写「沙箱未接入后端、`codesandbox.type: example`」（09-18 的状态） | 下一个接手的 AI 会被误导 | 未修 |
| DOC-2 | P2 | 10-23 的沙箱修复没有笔记 | 提交 `f06b237`（编译失败归类、stdin 与 args 同时喂）在 `docs/notes/` 无对应记录 | 为什么"写 Scanner 的代码会超时"只能从代码注释里找 | 未修 |
| DOC-3 | P2 | README 提交链路描述过期 | `README.md` §2 曾写「`CompletableFuture` + `JudgeFeignClient`」，与 MQ 实现冲突 | 新人按 README 理解链路会得出错误结论 | 已修（10-27） |
| DOC-4 | P2 | 文档库缺横向视图 | 本次核对前只有 notes（纵深的单点记录）与 architecture（系统地图），没有「改了什么/还剩什么/先做什么」 | 接手者需要翻 20 篇笔记自行拼装 | 已修（本次新增三份文档） |

## 7. 结构性风险

这类问题不是 bug，是当前架构的取舍。改它们的成本明显高于前几节，但不清算会限制后续所有改动。

| ID | 级别 | 问题 | 证据 | 影响 | 状态 |
|---|---|---|---|---|---|
| STR-1 | P1 | 三个业务服务共用一个库 `my_db` | 三个 `application.yml` 的 `spring.datasource.url` 均为 `jdbc:mysql://localhost:3306/my_db`；`docs/architecture.md` §7 第 1 条 | 表结构无法独立演进；一次 DDL 影响全部服务；所谓"微服务"只在进程层面成立 | 未修 |
| STR-2 | P1 | 「只监听回环」不等于「外部不可达」 | `server.address: 127.0.0.1` 只拒绝跨机器的 TCP 连接 | 本机任意进程仍可直连 8101~8103 并调用免鉴权的 inner 接口。真正的边界是 OS 账号与部署隔离，不是网络 | 未修（认知已纠正） |
| STR-3 | P1 | 沙箱与 Sentinel Dashboard 都用 8080 | 沙箱 `application.properties`：`server.port=8080`；三个服务 `spring.cloud.sentinel.transport.dashboard: localhost:8080` | 同机部署时冲突。当前沙箱在 VM 上，冲突未实际发生 | 待处理（用户已决定让 Sentinel 换端口，等 dashboard 落地） |

## 8. 环境与部署

| ID | 级别 | 问题 | 证据 | 影响 | 状态 |
|---|---|---|---|---|---|
| ENV-1 | P2 | 沙箱地址硬编码 | `RemoteCodeSandbox.SANDBOX_EXECUTE_URL = "http://192.168.221.45:8080/sandbox/execute"` | 换环境要改代码重新打包 | 未修 |
| ENV-2 | P2 | 沙箱 jar 必须在 VM 上重新构建 | 10-22 笔记踩坑记录：VM 上是旧 jar，`/sandbox/execute` 返回 404 | 本地改完不等于线上生效，容易误判成代码 bug | 未修（属于流程） |
| ENV-3 | P2 | RabbitMQ 经 SSH 隧道到 VM，容器需 `--network host` | 10-22 笔记 §6：Docker `-p` 的 proxy 转发不到容器 | 用 `-p` 映射会出现「TCP 通但 AMQP 握手超时」 | 未修（属于流程） |
| ENV-4 | P2 | 全部按单机假设配置 | 三个服务 `server.address` 与 `nacos.discovery.ip` 均为 `127.0.0.1` | 拆机部署时服务之间连不上；必须同步改两处 + 安全组 | 未修 |

## 9. 本次已修复（2026-10-27）

细节在 [changelog.md](./changelog.md) §3 与 [笔记](./notes/2026-10-27_判题正确性与幂等修复.md)。

| ID | 修复方式 | 验证 |
|---|---|---|
| JUD-1 | 前端删掉 C++/Go 选项；提交入口对非 Java 返回 `PARAMS_ERROR`，不再流到沙箱 | 编译通过；接口行为**未端到端验证** |
| JUD-2 | 抽出 `AbstractJudgeStrategy`，宽限改为 `timeLimit + 1000ms`（Java）；空值按「未设置限制」处理 | 9 个单测覆盖边界 |
| JUD-3 | 用例为空直接判 `System Error` | 单测 `noJudgeCaseIsSystemError` |
| CON-1 | 新增单事务 `finishQuestionSubmit`，条件更新 `WHERE status=RUNNING`，成功才 +1，失败整笔回滚 | 逻辑单测**未覆盖**（需要 DB），仅代码审查 |
| CON-2 | 消费者业务异常分支先落定 `FAILED + System Error` 再 ack | 逻辑单测**未覆盖**（需要 MQ），仅代码审查 |
| DOC-3 | README §2 改为 MQ 链路，并同步 Feign 调用矩阵 | 人工核对 |
| DOC-4 | 新增 changelog / known-issues / roadmap 三份文档并接入索引 | 本次交付 |

**2026-10-30 追加修复**

| ID | 修复方式 | 验证 |
|---|---|---|
| ENG-2 | 生成脚本改为「双服务拉取 + 合并」，并断言所有 `url` 以 `/api/` 开头 | `npm run api:generate` 实跑：43 models / 4 services；`type-check` + `build` 通过；错误地址实测退出码 1 |
| FEAT-1 | 新增 `/submissions`；用户中心接真实用户信息与最近 5 条提交 | 构建与类型检查通过；**接口行为未端到端验证**（未登录态手测） |

## 10. 未确认与空白

这些不是"没有问题"，是**没有证据**。列出来是为了避免把推断当成事实。

| 项 | 现状 | 怎么验证 |
|---|---|---|
| 端到端判题链路 | 10-27 的改动只到构建与单测，没有跑过真实提交；10-30 的提交记录页同样只有构建验证 | 启动 Nacos/MySQL/Redis/RabbitMQ + VM 沙箱，跑 changelog §3 的 curl，并在 `/submissions` 手测 |
| `CON-1` / `CON-2` 的实际幂等效果 | 依赖 `UPDATE ... WHERE status=RUNNING` 的 0 行语义，未在真实 MySQL 上验证过重复投递 | 同一条消息重复投递三次，观察 `acceptedNum` |
| 判题吞吐与队列堆积 | 从未压测 | 固定并发提交，观察队列长度与消费速率 |
| `question` / `question_submit` 的权威表结构 | 只能从实体类与 `map-underscore-to-camel-case: false` 推断 | 导出线上表结构补进 `sql/`（对应 `ENG-1`） |
| VM 上沙箱的实际版本 | 不确定是否包含 10-23 的 stdin 修复 | 在 VM 上看 jar 时间戳，或调 `/sandbox/execute` 用 Scanner 写法验证 |
| `judgeInfo.memory` 的口径 | 容器 `stats` 的 `usage` 含 JVM 自身开销，与「用户程序用了多少内存」不是一回事 | 用固定堆大小的程序对比 stats 与 `-Xmx` |
| 多实例部署行为 | 所有并发问题（`CON-3`、`STR-2`、`ENV-4`）都只在单实例下被"掩盖" | 起两个 question-service 实例复测 |

## 11. 相关文档

- 改造时间线：[changelog.md](./changelog.md)
- 处置顺序与验收标准：[roadmap.md](./roadmap.md)
- 系统地图与架构取舍：[architecture.md](./architecture.md) §7
- 开发规范：[conventions.md](./conventions.md)
- 文档索引：[README.md](./README.md)
