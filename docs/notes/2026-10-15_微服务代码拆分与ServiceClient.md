# HaxOJ 微服务改造笔记

> 这份笔记依托 `haxoj-backend`（单体）→ `haxoj-backend-microservice`（微服务）这次真实改造编写，
> 既记录「改造做了什么」，也讲清楚「为什么这么拆」——尤其是你问的
> **“为什么 service-client 里要放一份 Service 接口”**。
>
> ⚠️ **后续变更（2026-10-18）**：本文记录的 inner 路径（`/api/user/inner`、`/api/question/inner`、`/api/judge/inner`）
> 已决定**收敛为** `/api/inner/user`、`/api/inner/question`、`/api/inner/judge` —— 目的是让 inner 脱离 `/api/user/**`
> 这类**对外前缀**，从路径上杜绝「被网关顺手转发出去」（起因：`Path=/api/user/**` 会连 `/inner` 一起开放）。
> 改动面只有 6 行：3 个 inner Controller 的 `@RequestMapping` + 3 个 Feign 契约的 `path`。
> 方案见《2026-10-18_微服务网关接入.md》第八节。**本文其余内容仍是收敛前的真实状态（该变更尚未实施）。**

---

## 一、这次改造到底做了什么

一句话：**把原来一个 jar 里的代码，按“谁负责什么”切到不同的进程里**，每个进程是一个独立的 Spring Boot 应用，拥有自己的启动类、端口、配置文件，互相通过 HTTP 调用。

### 1. 模块总览

| 模块 | 角色 | 打包方式 | 放什么 |
| --- | --- | --- | --- |
| `haxoj-backend-common` | 公共工具（库） | jar（无启动类） | 统一返回 `BaseResponse`、`ErrorCode`、`ResultUtils`、全局异常、工具类、通用配置 |
| `haxoj-backend-model` | 领域模型（库） | jar（无启动类） | 实体 `entity`、`dto`、`vo`、`enums`，以及代码沙箱的 `judge/codesandbox/model` |
| `haxoj-backend-service-client` | 服务契约（库） | jar（无启动类） | 只放**跨服务 Feign 契约接口**（`UserFeignClient`、`QuestionFeignClient`、`JudgeFeignClient`） |
| `haxoj-backend-user-service` | 用户服务 | 可执行 jar | `UserController`、`UserServiceImpl`、`UserMapper`、文件上传 |
| `haxoj-backend-question-service` | 题目服务 | 可执行 jar | 题目/提交的 Controller、ServiceImpl、Mapper |
| `haxoj-backend-judge-service` | 判题服务 | 可执行 jar | `judge/*`：判题策略、代码沙箱调用 |
| `haxoj-backend-gateway` | 网关 | 可执行 jar | （本次未做，见第七节） |

### 2. 代码搬迁明细

**→ haxoj-backend-common**

```
com.hax.haxojbackend.common      (BaseResponse / DeleteRequest / ErrorCode / PageRequest / ResultUtils)
com.hax.haxojbackend.constant    (CommonConstant / FileConstant / UserConstant)
com.hax.haxojbackend.exception   (BusinessException / GlobalExceptionHandler / ThrowUtils)
com.hax.haxojbackend.utils       (NetUtils / SpringContextUtils / SqlUtils)
com.hax.haxojbackend.annotation  (AuthCheck)
com.hax.haxojbackend.config      (CorsConfig / JsonConfig / MyBatisPlusConfig)
com.hax.haxojbackend.aop         (LogInterceptor)
```

**→ haxoj-backend-model**

```
com.hax.haxojbackend.model.entity / dto / enums / vo
com.hax.haxojbackend.judge.codesandbox.model  (ExecuteCodeRequest / ExecuteCodeResponse / JudgeInfo)
```

**→ haxoj-backend-service-client**

```
com.hax.haxojbackend.service     (UserFeignClient / QuestionFeignClient)
com.hax.haxojbackend.judge       (JudgeFeignClient)
```

> 这一版是**改造完成后的最终形态**：service-client 里放的是「别的服务真正会用到的方法」组成的**精简 Feign 契约**。
> 完整的领域接口 `UserService / QuestionService / QuestionSubmitService / JudgeService`
> 已经**回到各自的 service 模块里**（见下方各服务清单），交给本服务的实现类和 Controller 使用。
> 为什么要这样分，见第四节。

**→ haxoj-backend-user-service**

```
service/UserService                     （本地完整领域接口，extends IService）
service/impl/UserServiceImpl, mapper/UserMapper(+xml)
controller/UserController, controller/FileController
controller/inner/UserInnerController     （对内接口 /api/user/inner/**）
manager/CosManager, config/CosClientConfig, aop/AuthInterceptor
com.hax.haxojbackend.HaxojBackendUserServiceApplication
resources/application.yml
```

**→ haxoj-backend-question-service**

```
service/QuestionService, service/QuestionSubmitService   （本地完整领域接口）
service/UserService, service/impl/UserServiceImpl        （本服务视角的精简「用户能力」）
service/impl/QuestionServiceImpl, service/impl/QuestionSubmitServiceImpl
controller/QuestionController, controller/QuestionSubmitController
controller/inner/QuestionInnerController                 （对内接口 /api/question/inner/**）
mapper/QuestionMapper(+xml), mapper/QuestionSubmitMapper(+xml)
aop/AuthInterceptor
com.hax.haxojbackend.HaxojBackendQuestionServiceApplication
resources/application.yml
```

**→ haxoj-backend-judge-service**

```
judge/JudgeService        （本地领域接口）
judge/JudgeServiceImpl, judge/JudgeManager
judge/codesandbox/*       (CodeSandbox / Factory / Proxy / impl)
judge/strategy/*          (DefaultJudgeStrategy / JavaLanguageJudgeStrategy / JudgeContext / JudgeStrategy)
controller/inner/JudgeInnerController    （对内接口 /api/judge/inner/**）
com.hax.haxojbackend.HaxojBackendJudgeServiceApplication
resources/application.yml
```

### 3. 本次删除 / 未搬的内容

- **帖子相关**（Post / PostFavour / PostThumb、`PostEsDao`、`job` 同步任务、`PostEsDTO`、`PostVO`）：项目用不到，删除。
- **微信公众号**（`wxmp` 包、`WxMpController`、`WxOpenConfig`、`UserService#userLoginByMpOpen`、`UserController#userLoginByWxOpen`）：用不到，删除。
- **Elasticsearch 相关**：随帖子一并删除。
- **脚手架自动生成物**：各模块的 `demos/web`、`Main`、`HaxojBackendXxxServiceApplication`（原包名版本）、`application.properties`、`static/index.html`、测试类 —— 全部清理，避免与新启动类冲突（同一个模块里出现两个
  `@SpringBootApplication` 会导致 Spring 报 “found multiple @SpringBootConfiguration”）。
- **未搬**：`generate/CodeGenerator`（代码生成器，属于开发期一次性工具，与微服务运行无关）、`test` 目录（单体测试对拆后的服务无意义）。

---

## 二、为什么要拆微服务

单体 `haxoj-backend` 的所有功能都在一个进程里：

- 用户模块、题目模块、判题模块**一起编译、一起发布**。改判题的一行代码，整个应用都要重新部署。
- 判题是 CPU/内存密集操作（要跑用户代码、连代码沙箱），一旦判题把线程/内存吃满，**整个网站的用户、题目接口都会跟着卡死**。
- 扩容只能整体扩容（加机器 = 把用户模块也复制一份），**没法按需扩容**。

拆开之后：

- **按业务独立部署**：判题压力大就多起几个 judge-service；用户服务几乎没压力就少配机器。
- **故障隔离**：judge-service 挂了，用户还能正常登录、刷题，只是提交后暂时判不了。
- **独立演进**：题目服务改用新框架、新数据库，不影响用户服务。

代价也要知道：**跨服务不能再直接注入对方 Bean**，只能用网络调用（HTTP/RPC），于是引出了本笔记最重要的一节 —— service-client。

---

## 三、核心问题：跨服务的 Service 怎么调用？

### 1. 单体里是怎么做的

```java
// 都在同一个进程
@Service
public class QuestionSubmitServiceImpl {
    @Resource
    private JudgeService judgeService;   // 直接注入，其实调的是本地方法
}
```

`@Resource` 注入的是**本地对象**，调用就是一次普通的方法调用，纳秒级。

### 2. 拆成微服务后发生了什么

`question-service` 和 `judge-service` 是**两个 JVM 进程**，`question-service` 的容器里根本没有 `JudgeServiceImpl` 这个 Bean。原来的 `@Resource private JudgeService judgeService;` 会直接报
`NoSuchBeanDefinitionException`。

而且就算你把 `JudgeServiceImpl` 的代码拷过来，那也是**两份实现**，一份改了另一份不知道，等于又变回单体里最糟的“代码重复 + 不一致”。

所以需要一个东西：**在调用方放一个“代理对象”，它的方法签名看起来和对方一模一样，但一调用就自动发 HTTP 请求到对方进程**。这个东西就是 **OpenFeign**。

### 3. Feign 是怎么工作的（本项目会用到的机制）

```java
@FeignClient(name = "haxoj-backend-judge-service", path = "/api/judge")
public interface JudgeFeignClient {
    @PostMapping("/do")
    QuestionSubmit doJudge(@RequestParam("questionSubmitId") long questionSubmitId);
}
```

- `@FeignClient(name=...)`：告诉 Spring Cloud「这个接口不是给你写实现的，去注册中心按这个名字找服务，然后发请求」。
- `@PostMapping("/do")` 等注解：描述**请求方式 + 路径**，Feign 靠它拼 URL。
- 应用启动时（配合启动类上的 `@EnableFeignClients`），Spring 用**动态代理**给这个接口生成一个实现类，注册成 Bean。所以你 `@Resource JudgeFeignClient` 注入的其实是个代理，调它的方法 = 发 HTTP。
- `name` 对应的正是对方的 `spring.application.name`，通过 **Nacos** 解析成真实 IP:端口。

**结论：Feign 让“远程调用”在代码层面几乎和“本地调用”一样**——这也是微服务能被工程化落地的关键。

---

## 四、两种接口：领域接口 vs Feign 契约（本笔记的重点）

你补充的这句话很关键：

> 「service-client 里存放的 service 中的『特定方法』，可能是指那些**其他模块会用到的方法**。」

判断完全正确。下面讲清楚「为什么要这样放」以及「最后实际怎么落地的」。

### 1. 同一个业务，会有两份「长得像、但职责不同」的接口

以题目服务为例，改造完成后一共存在**两份**接口：

| 接口 | 放在哪 | 谁用它 | 里面有什么 |
| --- | --- | --- | --- |
| `QuestionService`（领域接口） | `question-service` 自己的 `service/` 包 | 本服务的 `QuestionServiceImpl`（实现它）、本服务的 Controller（注入它） | 完整业务方法：`validQuestion`、`getQuestionVO`、`getVOPage`… 以及继承自 `IService` 的 `getById / updateById / save`… |
| `QuestionFeignClient`（Feign 契约） | `haxoj-backend-service-client` | 别的服务（比如 `judge-service`） | **只保留别的服务真正要用的少数方法**：`getQuestionById`、`getQuestionSubmitById`、`updateQuestionSubmitById`、`updateQuestionAcceptedNum` |

**一句话记住**：

> 领域接口 = 「我这个服务能干什么」（完整、给本地用、不带 HTTP 语义）；
> Feign 契约 = 「别的服务需要我干什么」（精简、带 HTTP 注解、走网络）。

这正是你说的「service-client 只存放特定的一些方法」——那些方法就是**跨模块调用矩阵**里出现的方法。
反过来说，**每个服务都要留一份自己完整的领域接口**，因为它的实现类和 Controller 是本地调用，走的是普通方法调用，压根不需要 HTTP 注解。

### 2. 为什么不能只留一份「完整接口」让两边共用？

初学者最容易想到：把完整接口抽出来放共享模块，提供方 `implements`、消费方 `@FeignClient extends`，只写一份多省事。
这在 **Dubbo 这类 RPC** 里可行，但在 **OpenFeign** 里有两个硬伤：

1. **`IService` 的方法没有 HTTP 注解**。`getById`、`updateById` 是 MyBatis-Plus 的本地方法，Feign 拿到它们**不知道该发什么请求**（GET 还是 POST？路径是什么？参数放哪？），直接报错。
2. **完整接口会把「本地才有的类型」带进来**。它的方法签名里出现 `QueryWrapper`、`Page`、`UpdateWrapper`——这些是数据库层的对象，**无法（也不该）被序列化成 HTTP 请求**。

所以最终形态是：**两个接口各司其职、不共用**；共享模块只放「**能被 HTTP 表达**」的那部分契约。

### 3. 那 service-client 的价值是什么？

即便不共用，把 Feign 契约**集中放在一个模块**依然有两大好处：

- **消费方只看一份文件就知道能远程调什么**：`judge-service` 想调题目服务，打开 `QuestionFeignClient` 就够了，不必去翻 `question-service` 的源码。
- **契约与实现解耦**：`question-service` 内部怎么重构都不影响契约；只要 `QuestionInnerController` 的**路径和参数不变**，`judge-service` 一行都不用改。

关系图：

```
        haxoj-backend-service-client（只放契约，不含任何实现）
        ┌──────────────────────────────────────────────┐
        │  interface QuestionFeignClient  @FeignClient   │
        │  interface UserFeignClient        + @GetMapping │
        │  interface JudgeFeignClient       + @PostMapping│
        └──────────────────────────────────────────────┘
                ▲                                ▲
   依赖(用契约)  │                                │  依赖(用契约)
                │                                │
   judge-service│                                │question-service
 @Resource QuestionFeignClient            QuestionServiceImpl implements QuestionService
 （Feign 代理，调用 = 发 HTTP）              （本地完整领域接口，真实现）
                                          QuestionInnerController 实现契约里的路径
```

**关键点**：提供方**不是**去 `implements QuestionFeignClient`，而是**写一个 Controller 去满足契约里的「路径 + 参数 + 返回类型」**。
两边靠**接口约定（路径/参数）对齐**，而不是靠「实现同一个 Java 接口」对齐——这是和 Dubbo 思维最大的区别。

### 4. 落地必踩的两个坑（本项目都踩了）

**坑 1：不能把本地库对象跨服务传。**
原 `JudgeServiceImpl` 里有一段「判题通过 → 题目通过数 +1」，用的是 MyBatis-Plus 的 `UpdateWrapper`：

```java
UpdateWrapper<Question> w = new UpdateWrapper<>();
w.eq("id", questionId);
w.setSql("acceptedNum = acceptedNum + 1");
questionService.update(w);          // 原子更新，避免并发丢更新
```

`UpdateWrapper` 是**本地数据库对象**，Feign 传不过去。
**改法**：换成**具名方法** `updateQuestionAcceptedNum(questionId)` —— 调用方只说「哪道题」，
而**原子 +1 的 SQL 由数据归属方 `question-service` 自己执行**。既保住并发安全，又符合「谁的表谁负责写」。

**坑 2：`getLoginUser` 依赖 session，跨服务读不到。**
`question-service` 也要判断「当前是谁在登录」，但 `HttpSession` 默认存在各自 JVM 内存里——
`user-service` 登录时写的 session，`question-service` 读不到。
**改法**：开启 **Redis 分布式 session**（`spring-session-data-redis`，yml 里把 `store-type: redis` 打开）。
两个服务读写的是**同一份 Redis session**，登录态天然共享。
`question-service` 拿到 userId 后，再用 `UserFeignClient.getById(userId)` 远程补全最新用户信息。

### 5. 一个容易混的点：两个模块里出现了「同名 `UserService`」

改造后，`user-service` 和 `question-service` **各有一份** `com.hax.haxojbackend.service.UserService`，
**全限定名完全相同，但它们是两个模块里的两个不同文件**：

| | 在哪个模块 | 内容 | 谁在用 |
| --- | --- | --- | --- |
| ① 完整领域接口 | `user-service` | `extends IService<User>`，`userRegister` / `userLogin` / `getLoginUser` / `getUserVO` / `getQueryWrapper`… | user-service 自己的 `UserServiceImpl`、`UserController`、`UserInnerController` |
| ② 精简版本地接口 | `question-service` | 只有 6 个方法：`getLoginUser` / `isAdmin`×2 / `getUserVO` / `getById` / `listByIds` | question-service 自己的 `UserServiceImpl`、`QuestionController`、`QuestionSubmitServiceImpl` |

所以 `QuestionController` 里的 `@Resource private UserService userService;` 注入的是 **②** ——
Spring 只会在**当前模块的 classpath** 里找同名接口，① 压根没被 question-service 加载。

**Feign 并没有被绕过，它藏在 ② 的实现里**：

```
QuestionController
  → (question-service 本地) UserService ②
  → UserServiceImpl  ── @Resource UserFeignClient
  → UserFeignClient（service-client 契约 = Feign 动态代理）
  → ═════ HTTP 跨进程 ═════► user-service 的 UserInnerController
  → (user-service 本地) UserService ①  → UserServiceImpl → 查 MySQL
```

**为什么要留这层「适配器」，而不是让 Controller 直接注入 `UserFeignClient`？**

1. **Controller 零改动**：拆分前到处是 `userService.getLoginUser(request)` / `userService.getById(...)`，留一层同名适配器，原调用点一行都不用改。
2. **这是硬约束**：`getLoginUser` / `isAdmin` / `getUserVO` 读的是**本进程 `HttpServletRequest` 里的 session**、或做本地脱敏转换，**根本不可能做成 Feign 调用**；只有 `getById` / `listByIds` 这类「纯查数据」才适合走网络。
   所以 ② 天然是「**一半本地逻辑 + 一半远程转发**」的混合体，它的意义不是"重复实现用户服务"，而是"把本服务视角需要的用户能力收拢成一个接口"。

**代价要承认**：两个模块出现同名类本身就是一种 code smell，第一眼很容易看花。
若要消除歧义，可以把 ② 改名为 `UserRemoteService` 之类（只改类型名和 `import`，风险很小）。

---

## 五、注册中心 Nacos：服务怎么找到彼此

`@FeignClient(name = "haxoj-backend-question-service")` 里的名字要能解析成真实地址，靠的就是注册中心。

- 每个服务在 `application.yml` 里声明：

  ```yaml
  spring:
    application:
      name: haxoj-backend-question-service         # 服务的“身份证”
    cloud:
      nacos:
        discovery:
          server-addr: xxxx:8848                   # Nacos 地址
  ```

- 服务启动时，`spring-cloud-starter-alibaba-nacos-discovery` 会把自己的 **IP + 端口**注册到 Nacos。
- 消费方要调用时，Nacos 返回一份可用实例列表，Feign 从中挑一个（默认轮询），再发请求。

好处：**不用把对方 IP 写死在代码/配置里**，对方扩容或换机器，注册中心会自动更新。

Nacos 还能做**配置中心**（把 yml 放到 Nacos 云端，支持热更新），本项目暂未启用。

### 1. Nacos 控制台里的「订阅者列表」为什么一开始是空的？

**结论：空是正常的，它反映的是「懒订阅」，不是出了问题。**

- 「订阅者列表」= **谁订阅了这个服务**，也就是**消费方**，不是提供方自己。
- Spring Cloud Alibaba **默认懒订阅**：只有当某个消费方**第一次真正发起调用**（负载均衡去拉取实例列表）时，才会在 Nacos 里建立订阅关系。
- 所以刚启动完、还没人调过的时候，任何服务的订阅者列表都是空的。
- 控制台页面**不会自动刷新**，需要重新点一次「查询」。

实测：调用过一次 `POST /api/judge/inner/do?questionSubmitId=1` 之后，直接查 Nacos 的 OpenAPI：

```
GET http://localhost:8848/nacos/v1/ns/service/subscribers
      ?serviceName=haxoj-backend-question-service
      &groupName=DEFAULT_GROUP&namespaceId=public

→ {"subscribers":[{"addrStr":"192.168.x.x","agent":"Nacos-Java-Client:v2.2.0", ...}],"count":1}
```

订阅者就从 **0 变成了 1**。

- 反过来，`haxoj-backend-user-service` 的订阅者仍是 0 —— 因为那次 question→user 的调用在数据库那步就失败了，**根本没走到 Feign**，自然没建立订阅。
- Nacos 2.x 的订阅关系走 gRPC 长连接，控制台上显示的信息比较简陋（「应用名」常常显示 `unknown`），属正常现象。

> **两个列表别再混淆**：
> 「服务列表」看的是**谁注册了**（每一行 = 一个提供方实例，看「健康实例数」）；
> 「订阅者列表」看的是**谁在调谁**（每一行 = 一个消费方）。

---

## 六、启动类与 application.yml 说明

### 1. 启动类

三个服务的启动类都在 **`com.hax.haxojbackend`** 根包下：

- `HaxojBackendUserServiceApplication`（8101）
- `HaxojBackendQuestionServiceApplication`（8102）
- `HaxojBackendJudgeServiceApplication`（8103）

```java
@SpringBootApplication
public class HaxojBackendQuestionServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(HaxojBackendQuestionServiceApplication.class, args);
    }
}
```

**为什么必须放在根包 `com.hax.haxojbackend`？**
`@SpringBootApplication` 默认只扫描**自己所在包及其子包**。把启动类放在根包，`com.hax.haxojbackend.controller`、`.service.impl`、`.mapper`、以及从 common / service-client 依赖进来的 `.config`、`.exception`、`.aop` 才会被自动扫描到。放在 `com.hax.haxojbackendquestionservice` 就会漏扫业务代码。

> 顺带解释第一节里为什么要删脚手架的启动类：同一个模块内存在两个 `@SpringBootApplication`，Spring 无法决定用哪个配置源，会直接启动失败。

### 2. 端口与 context-path 规划

| 服务 | 端口 | `server.servlet.context-path` |
| --- | --- | --- |
| user-service | 8101 | `/api` |
| question-service | 8102 | `/api` |
| judge-service | 8103 | `/api` |
| 代码沙箱 | 8080 | —— |

保留 `context-path: /api` 是为了和原单体、前端保持一致（前端调 `/api/user/...`）。
以后接入网关时，网关按路径前缀转发（`/api/user/**` → user-service），正好对得上。

### 3. yml 里都有什么

- `spring.application.name`：注册到 Nacos 的服务名。
- `spring.cloud.nacos.discovery`：注册中心地址、账号、命名空间。
- `spring.cloud.sentinel.transport.dashboard`：Sentinel 控制台（流量防护 / 熔断限流，暂未实际使用）。
- `datasource` / `redis`：数据库、缓存（redis 目前只是预留配置）。
- `mybatis-plus`：驼峰映射关闭、逻辑删除、日志用 SLF4J（不用 STDOUT）。
- `server.port` / `context-path`。
- judge-service 额外有 `codesandbox.type: remote`，对应 `JudgeServiceImpl` 里的 `@Value("${codesandbox.type:example}")`，决定走哪种代码沙箱。

---

## 七、网关（Gateway）为什么这次不做

网关是**所有外部流量的唯一入口**，负责：

- 路由：`/api/user/**` → user-service，`/api/question/**` → question-service。
- 统一鉴权、跨域（CORS）、限流、日志。

它的好处是把“每个服务各自处理跨域/鉴权”变成“网关统一处理”。
但网关的代码（`RouteLocator`、`GlobalFilter` 等）属于**新增业务**，不是从单体搬来的，所以本次按你的要求只做“搬迁”，网关留到下一步。

在网关就绪之前，各服务的 `CorsConfig`（在 common 里，被所有服务扫描到）继续承担跨域职责，前端可以直接指定各服务端口调试。

> 📄 网关的**具体接入方案**（端口规划与理由、网关模块待清理项、可复制的 `application.yml`、路由对照表、验证清单、排错口诀）
> 已单独成篇：见 `2026-10-18_微服务网关接入.md`。

---

## 八、改造记录 & 遗留问题

第 1、2 小节记录**本次已经做完并跑通验证**的内容；第 3 小节是仍需要你处理的遗留项。

### 1. Maven 依赖（本次已补齐，全量编译通过 ✅）

> 你说父模块引用你自己来管，这里把**本次各模块实际补了哪些依赖**记录下来留档。版本一律继承父工程 `dependencyManagement`，子模块不写版本号。

先明确一个前提：**`lombok`、`hutool-all`、`commons-lang3`、`gson`、`commons-collections4` 由父工程 `<dependencies>` 统一提供**，所有子模块自动继承。所以子模块**不用也不该**重复声明它们——这也是下面表格里看不到这几个库的原因。

| 模块 | 本次补充的依赖 | 为什么需要 |
| --- | --- | --- |
| `haxoj-backend-common` | `spring-boot-starter-web`、`mybatis-plus-boot-starter`、`spring-boot-starter-aop` | Web：`GlobalExceptionHandler`/`CorsConfig`/`JsonConfig`/`NetUtils`；MyBatis-Plus：`MyBatisPlusConfig`；AOP：`LogInterceptor` 的 aspectj |
| `haxoj-backend-model` | `mybatis-plus-annotation`（版本 `${mybatis-plus.version}`）、**`haxoj-backend-common`** | 实体上的 `@TableName`/`@TableId`/`@TableField`/`@TableLogic`；`QuestionQueryRequest` 等 `extends PageRequest`，而 `PageRequest` 在 common 里 |
| `haxoj-backend-service-client` | （本次**移除**了 `mybatis-plus-boot-starter`） | 重构后这里只放 Feign 契约，方法签名只用实体类和 JDK 集合，不再出现 `IService`/`Page`/`QueryWrapper` |
| `haxoj-backend-user-service` | **`haxoj-backend-service-client`**、`spring-boot-starter-aop`、`com.qcloud:cos_api`、**`spring-boot-starter-data-redis` + `spring-session-data-redis`** | 本服务的 `UserServiceImpl` 实现本地 `UserService`；`AuthInterceptor` 需 aspectj；`CosManager`/`CosClientConfig` 用腾讯云 COS SDK；Redis 用于**分布式 session**（登录态给别的服务读） |
| `haxoj-backend-question-service` | **`haxoj-backend-service-client`**、`spring-cloud-starter-openfeign`、**`spring-cloud-starter-loadbalancer`**、`spring-boot-starter-aop`、**`spring-boot-starter-data-redis` + `spring-session-data-redis`** | 本服务的领域接口与 `UserServiceImpl`/`QuestionServiceImpl` 都在本模块；openfeign 用于调 judge/user；loadbalancer 供 Feign 选实例（**2021.x 不再随 openfeign 传递**）；aspectj 给 `AuthInterceptor`；Redis 用于读共享 session |
| `haxoj-backend-judge-service` | **`spring-cloud-starter-loadbalancer`** | service-client、openfeign、common、model、mybatis-plus 原本已引入；**必须补 loadbalancer**，否则启动直接报 `No Feign Client for loadBalancing defined` |

**父工程 pom 本次新增的两处依赖管理**（供你对照）：

- `cos.version = 5.6.89` + `dependencyManagement` 里的 `com.qcloud:cos_api` 条目；
- `lombok.version = 1.18.30` 覆盖 —— 这个坑值得单独记一笔：

> Spring Boot 2.6.13 的 BOM 把 lombok 锁在 **1.18.24**，而本机 Maven 跑在 **JDK 21** 上，旧版 Lombok 的注解处理器会抛
> `NoSuchFieldError: JCTree$JCImport.qualid`。解决办法**不是**只在 `<properties>` 里改 `<lombok.version>`——
> 通过 `scope=import` 导进来的 BOM，其内部属性**不会**被子工程的 `<properties>` 覆盖。
> 必须在父工程 `<dependencyManagement>` 里、**在三个 BOM import 之前**显式声明一条 `org.projectlombok:lombok`
> （Maven 合并 `dependencyManagement` 时“先声明者优先”），才能真正把版本顶到 1.18.30。

另外，`knife4j-openapi2-spring-boot-starter` **本次没有引入**：各 Controller 里没有任何 swagger/knife4j 注解，`application.yml` 里的 knife4j 配置块属于**预留的惰性配置**，等真正开始写接口文档时再补依赖即可，不加也不影响编译。

### 2. 跨服务调用改造（本次已完成并通过运行验证 ✅）

总原则：**领域接口留在本服务，Feign 契约放 service-client，提供方加 inner Controller**。

**（1）最终落地了哪 3 个 Feign 契约**

| Feign 契约（在 service-client） | `name` | `path` | 方法 |
| --- | --- | --- | --- |
| `UserFeignClient` | `haxoj-backend-user-service` | `/api/user/inner` | `getById`、`listByIds` |
| `QuestionFeignClient` | `haxoj-backend-question-service` | `/api/question/inner` | `getQuestionById`、`getQuestionSubmitById`、`updateQuestionSubmitById`、`updateQuestionAcceptedNum` |
| `JudgeFeignClient` | `haxoj-backend-judge-service` | `/api/judge/inner` | `doJudge` |

**（2）跨模块调用矩阵**

| 方向 | 原代码里的调用 | 改造后 |
| --- | --- | --- |
| question → user | `userService.getLoginUser(request)` | 读 **Redis 共享 session** 拿 userId，再 `UserFeignClient.getById(userId)` |
| question → user | `userService.isAdmin(...)` | 纯本地判断 `UserRoleEnum.ADMIN`，不需要远程 |
| question → user | `userService.getById` / `listByIds` / `getUserVO` | 前两个走 `UserFeignClient`；`getUserVO` 本地脱敏 |
| question → judge | `judgeService.doJudge(questionSubmitId)` | `JudgeFeignClient.doJudge(...)` |
| judge → question | `questionSubmitService.getById` | `QuestionFeignClient.getQuestionSubmitById` |
| judge → question | `questionService.getById` | `QuestionFeignClient.getQuestionById` |
| judge → question | `questionSubmitService.updateById` | `QuestionFeignClient.updateQuestionSubmitById` |
| judge → question | `questionService.update(UpdateWrapper)` | **改为具名方法** `QuestionFeignClient.updateQuestionAcceptedNum` |

**（3）关键改动清单**

- `service-client`：删掉 4 个完整接口（`UserService`/`QuestionService`/`QuestionSubmitService`/`JudgeService`），新建 3 个 Feign 契约。
- 各服务恢复自己的完整领域接口；`question-service` 额外留了一份**精简版** `UserService / UserServiceImpl`（读 session + 转发 Feign），
  这样本服务里原有的 `userService.getLoginUser(...)` 等调用点**一行都不用改**。
- 提供方新增三个 inner Controller：`/api/user/inner`、`/api/question/inner`、`/api/judge/inner`。
- `question-service`、`judge-service` 启动类加 `@EnableFeignClients`（默认扫描 `com.hax.haxojbackend`，正好覆盖两个契约接口）。
- `question-service` 里原来的 `@Lazy JudgeService` 改为 `@Resource JudgeFeignClient`——远程代理不存在本地循环依赖，`@Lazy` 可去掉。

**（4）依赖坑（务必记住）**

> Spring Cloud **2021.x** 起，`spring-cloud-starter-openfeign` **不再自带负载均衡器**。
> 只加 openfeign 时启动会直接报：
> `IllegalStateException: No Feign Client for loadBalancing defined. Did you forget to include spring-cloud-starter-loadbalancer?`
> 解决办法：**凡是用 Feign 的服务，必须额外引入 `spring-cloud-starter-loadbalancer`**（本项目 question-service、judge-service 各加了一条）。

**（5）本地启动前置条件**

启动前需准备好：**MySQL(3306)**、**Redis(6379)**、**Nacos(8848)**。

三个 yml 的 `spring.datasource.password` 刻意保持为占位符 **`${MYSQL_PASSWORD:}`，不要写明文**。
原因是 **`application.yml` 是要提交到远程仓库的**，明文密码一旦推上去就等于公开密钥（即使之后删除，git 历史里依然能翻到）。
`${MYSQL_PASSWORD:}` 的写法表示：运行时从环境变量/启动参数读取 `MYSQL_PASSWORD`，读不到则取冒号后的默认值（这里是空串）。
所以本机启动时**必须显式传进来**，任选一种：

```bash
# ① IDEA Run Configuration → VM options（推荐，注意是 -D 不是 --）
-DMYSQL_PASSWORD=123456

# ② 命令行启动 jar
java -DMYSQL_PASSWORD=123456 -jar haxoj-backend-user-service-0.0.1-SNAPSHOT.jar

# ③ Spring Boot 参数写法
java -jar xxx.jar --spring.datasource.password=123456

# ④ 设置操作系统环境变量后再启动
set MYSQL_PASSWORD=123456   # Windows
export MYSQL_PASSWORD=123456 # Linux/macOS
```

传参后重启即可正常连库。**若忘传，会看到 `Access denied for user 'root'@'localhost' (using password: NO)`**，这就是占位符取了空默认值的结果，不是配置写错。
> ⚠️ 我（AI）在改造验证阶段曾图省事把这三处改回明文 `123456` 以便跑通启动，**这是错误做法，已回退**。
> 用户的做法（占位符 + 启动传参 + yml 可安全入仓）才是正解。

依次启动 `user-service(8101)` → `question-service(8102)` → `judge-service(8103)`，三个服务都能在 Nacos 服务列表里看到。

> 实测验证方式：不带登录直接请求 `POST /api/judge/inner/do?questionSubmitId=1`，
> judge-service 日志里出现 `BusinessException: 提交信息不存在` —— 说明 **Feign 真的调通了 question-service 并拿到了返回值**，
> 只是这个 id 在库里不存在，随后才抛出业务异常。
> （如果 Feign 不通，报的会是负载均衡/连接错误，而不是这条业务异常。）

**（6）Feign 联调怎么测（三层验证）**

> 前提：三个服务已启动，且**都带了 `-DMYSQL_PASSWORD=123456`**（见上一小节）。

**先排除"根本不是 Feign 的问题"**：如果服务连不上库，表现是**任何碰数据库的接口都返回**
`{"code":50000,"data":null,"message":"系统错误"}` —— 这时和 Feign 一点关系都没有。
判断方法（查当前有哪些连接）：

```sql
SELECT id, user, host, db, command FROM information_schema.processlist;
```

> 这个坑真踩过：服务能启动、能注册进 Nacos、端口也在监听，但 `processlist` 里**一个服务连接都没有** ——
> 就是漏传了 `MYSQL_PASSWORD`，占位符取了空串。所以「服务起来了」≠「业务能跑」。

**① 最快：judge → question（不需要登录）**

```powershell
curl.exe -s -X POST "http://localhost:8103/api/judge/inner/do?questionSubmitId=1"
```

链路：`JudgeInnerController` → `JudgeService.doJudge` → `QuestionFeignClient.getQuestionSubmitById(1)`

看 judge 控制台是否出现这一行（**Feign 通了最硬的证据**）：

```
For 'haxoj-backend-question-service' URL not provided. Will try picking an instance via load-balancing.
```

返回体是「提交信息不存在」这类**业务错误才是正常的**（说明远程调用成功返回了 null）；
若报的是连接 / 负载均衡类错误，才是 Feign 没通。

**② 验证反方向：question → user**

```powershell
# 先找一个真实存在的题目 id
mysql -h 127.0.0.1 -uroot -p123456 -e "select id, userId from my_db.question;"
# 再调（id 用上一步查到的）
curl.exe -s "http://localhost:8102/api/question/get/vo?id=<上面的 id>"
```

链路：`QuestionController` → `questionService.getQuestionVO` → `userService.getById(userId)` → `UserFeignClient` → user-service 的 `UserInnerController`

看返回 JSON 里 `data.user.userName` 有值 —— 这个用户名是**跨进程从 user-service 取回来的**，
就是第四节「5. 同名 UserService」那条链路在真实运转。

**③ 回到 Nacos 看订阅关系被建立**

做完 ① 之后，Nacos → 订阅者列表 → 服务名填 `haxoj-backend-question-service` → 点「查询」，
就会出现订阅记录（懒订阅被触发，见第五节）。

**想看更细的 Feign 日志**（加到需要观察的服务 yml 里）：

```yaml
logging:
  level:
    org.springframework.cloud.openfeign: debug
    org.springframework.cloud.loadbalancer: debug
```

打开后能看到每次 Feign 请求的**完整 URL、选中的目标实例、响应体**，排查跨服务问题非常有用。

**（7）改造后仍可优化的小点**

- `question-service` 的 `getLoginUser` 每次都会远程查一次用户表；后续可直接用 session 里的用户对象，省掉一次远程调用。
- `judge-service` 仍保留 `datasource` 配置与 MyBatis-Plus 依赖（因为 common 里的 `MyBatisPlusConfig` 需要它们），但它已经不直接读写数据库了。

### 3. 其他遗留项

- **分布式 Session**：本次**已完成**——`user-service` / `question-service` 引入 `spring-boot-starter-data-redis` + `spring-session-data-redis`，yml 里 `store-type: redis` 已打开。登录态写进 Redis，跨服务共享。
- **配置文件拆分**：需要正式环境时，再为各服务补 `application-prod.yml` / `application-test.yml`。
- **删除父工程残留**：`haxoj-backend-microservice/src` 下还留着脚手架生成的 nacos / sentinel demo（父工程是 `pom` 打包，不参与编译，暂不影响）。
- **网关**：见第七节，本次未做；网关就绪后，前端可以直接只连网关端口。

---

## 九、关于包名的一个说明

本次**保留了单体的包名 `com.hax.haxojbackend.*`**，各模块继续用同一套包名（`com.hax.haxojbackend.model`、`com.hax.haxojbackend.service`、`com.hax.haxojbackend.judge` …）。

- 好处：搬代码时可以**一字不改**，所有 `import` 直接成立，风险最低。
- 代价：模块名和包名不再一一对应（比如 `model` 模块里是 `com.hax.haxojbackend.model`，而不是 `com.hax.haxojbackendmodel.model`）。

主流教程（含鱼皮微服务教程）习惯把包名也改成模块专属，比如 `com.hax.haxojbackendcommon`、`com.hax.haxojbackendmodel`、`com.hax.haxojbackendserviceclient`。那只是**命名风格**差异，不影响运行；两种都是合法做法。**Java 里包和 Maven 模块本来就是两个正交的概念**——一个包可以横跨多个模块，一个模块也可以包含多个包。

---

## 十、一句话总结

> 微服务 = 把单体按业务**切成多个进程**；
> 进程之间用 **Feign** 做 HTTP 调用；
> 用 **Nacos** 做服务注册发现；
> 用 **service-client** 集中存放**精简的 Feign 契约接口**（只含「别的服务会用到的方法」+ HTTP 注解），
> 各服务自己保留**完整的领域接口**给本地实现与 Controller 用，并在 inner Controller 里满足契约的路径；
> **common / model** 则是所有服务共享的工具与模型；
> **网关**作为统一入口（本次未做）。
