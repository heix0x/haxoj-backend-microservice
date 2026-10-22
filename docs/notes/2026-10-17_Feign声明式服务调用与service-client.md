# Feign 声明式服务调用 & service-client 契约模块

> 这份笔记专门讲透 `haxoj-backend-service-client` 这一个模块背后的「一整套知识」：
> 它依赖什么、为什么存在、里面那个 `@FeignClient` 接口到底靠什么魔法变成了 HTTP 请求、
> 以及落地时真实踩过的坑。
>
> 与另一份《微服务代码拆分与 ServiceClient》互补：那份记录**这次改造做了什么**，
> 这份讲**Feign 这门技术本身**。两篇一起看，先看结构、再看原理。

***

## 0. 先定位：service-client 在整个架构里的坐标

```
                    haxoj-backend-service-client  ← 本笔记的主角
                    （一个 jar 库，只放「接口」，没有实现、没有启动类、不跑进程）
        ┌──────────────────────────────────────────────────────────┐
        │  interface UserFeignClient      @FeignClient + @GetMapping │
        │  interface QuestionFeignClient  @FeignClient + @PostMapping│
        │  interface JudgeFeignClient     @FeignClient + @PostMapping│
        └──────────────────────────────────────────────────────────┘
                 ▲                                        ▲
      依赖(拿契约)│                                        │依赖(拿契约)
                 │                                        │
   question-service                                  judge-service
   @EnableFeignClients                               @EnableFeignClients
   @Resource JudgeFeignClient  ← 代理                     @Resource QuestionFeignClient ← 代理
        │                                                     │
        │ 发 HTTP（经 Nacos 找到实例）                          │
        ▼                                                     ▼
   judge-service 的 JudgeInnerController ←────────→ question-service 的 QuestionInnerController
      （真正干活的实现方）                                （真正干活的实现方）
```

一句话记住这个模块的定位：

> **service-client 不是服务，是「服务之间打交道的说明书」。**
> 它只回答一个问题：**「别的服务可以远程调用我的哪些能力，用什么 HTTP 方式调用。」**

***

## 1. 问题的起点：一次本地方法调用，拆开后就「断了」

单体时代，跨模块调用就是一行 Java 方法调用：

```java
@Service
public class QuestionSubmitServiceImpl {
    @Resource
    private JudgeService judgeService;   // 同进程，注入的是真对象
    ...
    judgeService.doJudge(id);            // 普通方法调用，纳秒级
}
```

拆成微服务后，`question-service` 和 `judge-service` 是**两个独立 JVM 进程**：

- `question-service` 的容器里**根本没有** **`JudgeServiceImpl`** **这个 Bean**，上面那行 `@Resource` 会抛 `NoSuchBeanDefinitionException`。
- 就算把 `JudgeServiceImpl` 抄一份过来，也变成「两份实现」，改一份另一份不知道 —— 又退化成单体里最糟的状态。

于是需要一个东西：**在调用方放一个「假对象」（代理），它长得和对方一模一样，但一调用就自动发 HTTP 到对方进程。**

> 这个「假对象」有两种技术实现：
>
> - **RPC 派**（Dubbo）：像调本地方法一样调远程，走私有协议；
> - **HTTP 派**（Feign）：把方法调用翻译成标准 RESTful HTTP 请求。
>
> Spring Cloud 体系选的是 **OpenFeign**（HTTP 派）。

***

## 2. Feign 的理念：声明式 HTTP 客户端

### 2.1 什么是「声明式」

同样是「发一个 HTTP 请求拿到题目」，两种写法：

**编程式（RestTemplate / HttpClient / OkHttp）——你要自己拼每一个细节：**

```java
String url = "http://" + 实例IP + ":" + 端口 + "/api/question/inner/get/id?questionId=" + id;
ResponseEntity<Question> resp = restTemplate.getForEntity(url, Question.class);
Question q = resp.getBody();          // 还要手动选址、手动拼串、手动解包
```

**声明式（Feign）——你只描述「长什么样」，Feign 负责「怎么发」：**

```java
@GetMapping("/get/id")
Question getQuestionById(@RequestParam("questionId") long questionId);
```

调用方只管 `questionFeignClient.getQuestionById(1L)`，**看不见 URL、看不见序列化、看不见负载均衡**。

> **「声明式」的核心思想**：把「描述意图」（我要调 GET /get/id）和「实现细节」（拼 URL、编码、选址、解码）彻底分离。
> 你写的是**契约**，框架生成的是**执行代码**。

### 2.2 为什么必须写成「接口 + 注解」

因为 Feign 的魔法是靠**动态代理**实现的：

1. 接口上写了 `@FeignClient` 和各方法的 `@GetMapping/@PostMapping`；
2. 应用启动时框架读这些注解，为**每个方法**建一条「HTTP 请求模板」（是什么方法、什么路径、参数放哪）；
3. 用 JDK 动态代理生成接口的实现类，注册成 Spring Bean；
4. 你 `@Resource` 注入的其实是这个**代理对象**，调它的方法 = 触发一次真实的 HTTP 调用。

所以：**没有接口就没有代理，没有注解就不知道怎么拼请求。** 这也解释了后面会讲的一个硬约束——不能把 MyBatis-Plus 那种「本地方法」放进契约里。

### 2.3 它在微服务技术图谱里的位置

| 关注点       | 本项目的技术                        | 作用                            |
| --------- | ----------------------------- | ----------------------------- |
| 服务注册 / 发现 | **Nacos Discovery**           | 服务启动时登记 IP:端口；调用方按「服务名」查到实例列表 |
| 服务间通信     | **OpenFeign**                 | 把接口方法翻译成 HTTP 请求              |
| 客户端负载均衡   | **Spring Cloud LoadBalancer** | 从实例列表里挑一个（默认轮询）               |
| 流量防护 / 熔断 | Sentinel（已引入，**尚未真正启用**）      | 限流、熔断降级                       |

**Feign 只负责「翻译」，不负责「找到对方」。** 「找到对方」是 Nacos（注册中心）+ LoadBalancer（选址）干的活。这几块经常被混为一谈，一定要分清。

***

## 3. 原理：一次 Feign 调用到底发生了什么

### 3.1 启动阶段：把接口「变成」Bean

```
@EnableFeignClients                        ← 启动类上的开关，开启扫描
        │
        ▼
扫描 @FeignClient 注解的接口
        │
        ▼
为每个接口注册一个 FeignClientFactoryBean
        │
        ▼
生成 JDK 动态代理对象 → 注册进 Spring 容器
        │
        ▼
业务代码 @Resource QuestionFeignClient 拿到的就是这个代理
```

> **为什么启动类** **`@EnableFeignClients`** **不写** **`basePackages`** **也能扫到？**
> 该项目启动类位于 `com.hax.haxojbackend`（根包），而不带参数的 `@EnableFeignClients` 默认扫描**启动类所在包及其子包**。
> `service-client` 里的契约正好在 `com.hax.haxojbackend.service` 和 `com.hax.haxojbackend.judge` 下，被自然覆盖。
> 如果契约放在别的包名下，就必须显式写 `@EnableFeignClients(basePackages = "...")`。

### 3.2 调用阶段：从方法调用到网络往返

以本项目「判题服务查题目」为例（`questionFeignClient.getQuestionById(questionId)`）：

```
业务代码调用 getQuestionById(1L)
   │
   ① 代理拦截 → 取出这条方法对应的「方法元数据」（MethodMetadata）：
   │          POST 还是 GET？路径 /api/question/inner/get/id？参数 questionId 放 query 还是 body？
   ▼
② 用元数据生成请求模板（RequestTemplate），把实参填进去
   │          → GET http://haxoj-backend-question-service/api/question/inner/get/id?questionId=1
   ▼
③ 把逻辑名 "haxoj-backend-question-service" 交给 LoadBalancer
   │          → 向 Nacos 要实例列表 → 挑一个 → 替换成真实 IP:端口
   ▼
④ 编码（Encoder）：请求体对象 → JSON
   ▼
⑤ 真·HTTP 请求（默认走 HTTP 客户端）发出去
   ▼
⑥ 解码（Decoder）：响应 JSON → Question 对象（由 Jackson 完成）
   ▼
方法返回 Question 实例（你甚至感觉不到发生过网络往返）
```

**关键结论：Feign 的「透明」是动态代理 + 注解元数据 + 编解码器 + 负载均衡四件套协作的结果，不是黑魔法。**

***

## 4. 注解逐条讲透

### 4.1 `@FeignClient` 常用属性

| 属性                             | 含义                                            | 本项目用法                                     |
| ------------------------------ | --------------------------------------------- | ----------------------------------------- |
| `name` / `value`               | **逻辑服务名**，必须等于目标服务的 `spring.application.name` | `name = "haxoj-backend-question-service"` |
| `path`                         | 所有方法共用的**路径前缀**（相当于类级 `@RequestMapping`）      | `path = "/api/question/inner"`            |
| `url`                          | 写死地址（`http://ip:port`），**跳过注册发现**；调试或调外部服务时用  | 本项目未使用                                    |
| `configuration`                | 该客户端专属配置类（编码器、拦截器、超时等）                        | 本项目未使用（走默认）                               |
| `fallback` / `fallbackFactory` | 熔断降级实现类，配合 Hystrix/Sentinel                   | 本项目未使用                                    |

> `name` 与 `path` 的**双重对齐**是本项目最重要的一条约定：
>
> - `name` 对齐 **provider 的** **`spring.application.name`**（决定「找谁」）；
> - `path` 对齐 **provider 的** **`@RequestMapping`** **前缀**（决定「打哪个门」）。
>   这两处任意一个对不上，运行时就是一个 404 或「找不到服务」，且通常**不报编译错误**，只会在调用时炸。

### 4.2 方法 / 参数注解

| 注解                             | 作用                                     | 本项目实例                                                          |
| ------------------------------ | -------------------------------------- | -------------------------------------------------------------- |
| `@GetMapping` / `@PostMapping` | 描述 HTTP 方法 + 子路径                       | `@PostMapping("/do")`                                          |
| `@RequestParam`                | 参数放进 **query string**（`?questionId=1`） | `getQuestionById(@RequestParam("questionId") long questionId)` |
| `@RequestBody`                 | 参数序列化成 **JSON 请求体**                    | `updateQuestionSubmitById(@RequestBody QuestionSubmit qs)`     |
| `@PathVariable`                | 参数填进路径占位符（`/{id}`）                     | 本项目未使用                                                         |
| `@RequestHeader`               | 参数放进请求头                                | 本项目未使用                                                         |

> 这些注解**沿用 Spring MVC 的那一套**，所以有 Web 基础的话几乎零学习成本 —— 这也是 Feign 相比 Dubbo 更「亲民」的地方。

***

## 5. 本项目 service-client 的真实契约（照代码抄的）

### 5.1 三个契约总览

| 契约接口                  | `name`                           | `path`                | 方法                                                     | HTTP                                        |
| --------------------- | -------------------------------- | --------------------- | ------------------------------------------------------ | ------------------------------------------- |
| `UserFeignClient`     | `haxoj-backend-user-service`     | `/api/user/inner`     | `getById(long)` → `User`                               | GET `/get/id`                               |
| <br />                | <br />                           | <br />                | `listByIds(Collection<Long>)` → `List<User>`           | GET `/get/ids`                              |
| `QuestionFeignClient` | `haxoj-backend-question-service` | `/api/question/inner` | `getQuestionById(long)` → `Question`                   | GET `/get/id`                               |
| <br />                | <br />                           | <br />                | `getQuestionSubmitById(long)` → `QuestionSubmit`       | GET `/question_submit/get/id`               |
| <br />                | <br />                           | <br />                | `updateQuestionSubmitById(QuestionSubmit)` → `boolean` | POST `/question_submit/update`              |
| <br />                | <br />                           | <br />                | `updateQuestionAcceptedNum(long)` → `boolean`          | POST `/question_submit/update/accepted_num` |
| `JudgeFeignClient`    | `haxoj-backend-judge-service`    | `/api/judge/inner`    | `doJudge(long)` → `QuestionSubmit`                     | POST `/do`                                  |

### 5.2 谁在用谁（消费方矩阵）

| 消费方                | 引入的契约                                | 用在哪                                                         | 是否 `@EnableFeignClients` |
| ------------------ | ------------------------------------ | ----------------------------------------------------------- | ------------------------ |
| `question-service` | `UserFeignClient`、`JudgeFeignClient` | `UserServiceImpl` 补全用户信息；`QuestionSubmitServiceImpl` 异步触发判题 | ✅ 有                      |
| `judge-service`    | `QuestionFeignClient`                | `JudgeServiceImpl` 读提交/题目、回写状态、通过数 +1                       | ✅ 有                      |
| `user-service`     | 无                                    | **纯提供方**，只被别人调，自己不调别人                                       | ❌ 无（依赖里也没有 openfeign）    |

> **值得注意**：`user-service` 完全没有引入 OpenFeign。这说明「服务间调用」是**按需引入**的能力，
> 不是「每个服务都必须装」。它是本项目里最纯粹的 provider。

***

## 6. 契约的对偶面：provider 侧的 InnerController

Feign 在消费方只是一张「说明书」，但**真正干活的代码在提供方**。本项目为此专门建了三个「内部接口」Controller：

| 契约                    | 提供方实现                     | 类级注解                                 | 完整路径（含 context-path `/api`） |
| --------------------- | ------------------------- | ------------------------------------ | --------------------------- |
| `UserFeignClient`     | `UserInnerController`     | `@RequestMapping("/user/inner")`     | `/api/user/inner`           |
| `QuestionFeignClient` | `QuestionInnerController` | `@RequestMapping("/question/inner")` | `/api/question/inner`       |
| `JudgeFeignClient`    | `JudgeInnerController`    | `@RequestMapping("/judge/inner")`    | `/api/judge/inner`          |

**最重要的一点：提供方不是** **`implements`** **这份契约接口。**

```java
// 契约（消费方视角）
@FeignClient(name = "haxoj-backend-question-service", path = "/api/question/inner")
public interface QuestionFeignClient { @GetMapping("/get/id") Question getQuestionById(...); }

// 提供方（另一套代码，另一份接口/类，只保证「路径 + 参数 + 返回类型」一致）
@RestController
@RequestMapping("/question/inner")
public class QuestionInnerController { @GetMapping("/get/id") public Question getQuestionById(...) { ... } }
```

两边靠 \*\*「路径 + 参数 + 返回类型」的约定\*\* 对齐，而不是靠继承同一个 Java 接口 —— 这是和 Dubbo 思维最大的区别。

### 6.1 为什么要单独搞「内部接口」，复用对外接口不行吗

对比同一个服务里的两套接口：

| <br /> | 对外接口（如 `UserController`） | 内部接口（`UserInnerController`）     |
| ------ | ------------------------ | ------------------------------- |
| 给谁用    | 浏览器 / 前端                 | 其它微服务                           |
| 返回结构   | 统一包装 `BaseResponse<T>`   | **直接返回实体**（`User` / `Question`） |
| 鉴权     | 要登录校验、`@AuthCheck`       | **不做登录校验**（服务间信任）               |
| 脱敏     | `UserVO` 脱敏              | **返回完整实体**                      |
| 语义     | 业务语义                     | 数据访问语义                          |

**如果复用对外接口会怎样？** 服务间调用会被迫背负「统一返回格式 + 登录态 + 脱敏」这些**只对浏览器有意义**的逻辑，调用方还得先拆 `BaseResponse` 再拿数据，极其别扭。

> 所以结论是：**对外的「业务接口」和对内的「数据接口」应该分开**，各管各的职责。

### 6.2 设计权衡：为什么不让提供方直接 `implements` 契约

初次接触时最容易冒出的疑问是：

> 「既然契约接口在 service-client 里写好了，**让提供方直接 `implements` 它**，路径和参数就由编译器保证一致了，不是更不容易出错、也更易读吗？」

**先说结论：这不是「不行」，而是「不划算」。** 技术上完全可以这么做（Spring MVC 5.1+ 允许在接口上声明 `@RequestMapping`，本项目 Boot 2.6 = Spring MVC 5.3，能跑）：

```java
// service-client：契约
@FeignClient(name = "haxoj-backend-user-service", path = "/api/user/inner")
public interface UserFeignClient {
    @GetMapping("/get/id")
    User getById(@RequestParam("userId") long userId);
}

// user-service：提供方直接实现它（这样写是能跑的）
@RestController
public class UserInnerController implements UserFeignClient {
    @Override
    public User getById(long userId) { ...真正查库... }
}
```

好处是真实的：**路径、参数、返回类型由编译器保证一致**，不会再出现「坑 2 里的 `name` 写错却在编译期无感」那类问题。

但代价同样真实，本项目选了不这么做：

**代价 1：把「客户端契约」和「服务端 API」焊死成一个东西。**
`@FeignClient` 的语义是**「这是客户端的代理声明」**。提供方一旦 `implements` 它，同一个类型就同时是「消费方怎么调我」和「我必须长成这样」。
后果是**契约无法独立演进**：提供方想给自己加个参数、换个返回结构，都必须去改共享模块里的契约，而这个改动立刻影响所有消费者。
现在这种「约定对齐」下，提供方新增 `/user/inner/get/batch` 之类接口时，**契约一个字都不用动**。

**代价 2：会引入 Bean 冲突（最实际的坑）。**
`@EnableFeignClients` 不带 `basePackages` 时，会为扫描到的**每个** `@FeignClient` 接口生成一个代理 Bean。
本项目 `question-service`、`judge-service` 都有 `@EnableFeignClients`，会为 `com.hax.haxojbackend` 下的全部三个契约生成代理。
假设 `question-service` 让 `QuestionInnerController implements QuestionFeignClient`，容器里就会**同时存在**：
- 一个 `QuestionFeignClient` 的 **Feign 代理 Bean**；
- 一个 `QuestionInnerController`（它**也是**一个 `QuestionFeignClient`）。

同类型两个 Bean → 注入歧义（按类型注入直接炸），要绕开就得额外收窄扫描范围或用 `contextId`/`primary` 治理。
**「少写重复注解」省下的力气，被这点复杂度吃回去了。**

**代价 3：HTTP 语义会泄漏到服务端。**
看契约里这个方法签名：

```java
updateQuestionSubmitById(@RequestBody QuestionSubmit questionSubmit);
```

它描述的是**「怎么通过 HTTP 调用」**（POST、参数放请求体、传稀疏实体），而不是**「这是什么业务操作」**。
让提供方 implements 它，就等于**服务端接口形态由第一个消费方决定**了；还会强制服务端接受「网络回来的部分字段实体」这种入参，并且**不能**把返回包装成统一的 `BaseResponse<T>` —— 正好和 6.1 里「对外/对内两套风格」的设计冲突。

**代价 4：方向是反的，读代码的人会困惑。**
让服务端实现一个名字里就写着 **Feign** 的接口，职责变得不清晰。现在的写法里，提供方的 `UserInnerController` 读起来就是一个**普普通通的 Controller**，一眼能看出它是服务端。

#### 根源：Dubbo 的接口「中立」，Feign 的接口「不中立」

| | Dubbo | Feign |
| --- | --- | --- |
| 接口长什么样 | `User getById(long id)` —— 纯领域操作，**不带传输层信息** | 带 `@GetMapping`/`@RequestBody`/`path` —— 是**一份 HTTP 调用说明书** |
| 提供方能否 `implements` | ✅ 标准做法（接口中立，复用无害） | ⚠️ 能，但别扭（把传输细节反向压回业务层） |

> **这就是根源**：Dubbo 的接口是「业务能力的定义」，双方共用一份很自然；
> Feign 的接口本质是「HTTP 调用说明书」，让服务端去实现一份「说明书」，自然别扭。

#### 三种做法怎么选

| 方案 | 提供方怎么写 | 编译期校验 | 耦合度 | 适合场景 |
| --- | --- | --- | --- | --- |
| **契约分离（本项目）** | 另写 Controller 满足路径约定 | ❌ 需靠测试/文档兜底 | **低**，各自独立演进 | 服务边界清晰、要解耦、消费方可能多于一个 |
| 接口共享（provider `implements`） | 直接实现契约接口 | ✅ 强 | **高**，接口改动牵动双方 | 服务少、消费方固定、极看重「写错当场报错」 |
| 代码生成（OpenAPI → client/server stub） | 从文档生成 | ✅ 强 | 中 | 想「既解耦又有编译期校验」，业界更成熟的解法 |

**结论**：本项目选分离，是为了「服务各自演进、契约只留真正跨服务的那几个方法」这个更大的目标；它换来的缺点（**写错路径编译期不报错**）靠**内部接口的集成测试**兜底。
如果你更看重编译期安全，完全可以让提供方 `implements` —— 只是要记得把 `@EnableFeignClients` 的扫描范围收窄以避免 Bean 冲突，并接受契约从此「不中立」。

***

## 7. 领域接口 vs Feign 契约（为什么不共用一份）

同一个业务会存在**两份长得像、但职责不同**的接口，初学者最容易在这里绕晕：

| <br />     | 领域接口（如 `QuestionService`）                               | Feign 契约（如 `QuestionFeignClient`） |
| ---------- | ------------------------------------------------------- | --------------------------------- |
| 放哪         | **各服务自己**的 `service/` 包                                 | **service-client 模块**             |
| 谁用         | 本服务的实现类 + 本服务 Controller                                | **别的服务**                          |
| 内容         | **完整**业务方法 + 继承 `IService` 的 `getById/updateById/save`… | **只保留别的服务真正要用的少数方法**              |
| 有 HTTP 注解吗 | 没有（本地方法调用）                                              | **必须有**（Feign 靠它拼请求）              |

记忆口诀：

> 领域接口 = 「**我能干什么**」（完整、本地用、无 HTTP 语义）
> Feign 契约 = 「**别人需要我干什么**」（精简、远程用、带 HTTP 语义）

**为什么不能只留一份完整接口、让两边共用？**（很多人的第一直觉）两个硬伤：

1. **`IService`** **的方法没有 HTTP 注解**：`getById`、`updateById` 是 MyBatis-Plus 的本地方法，Feign 拿到它们**不知道发 GET 还是 POST、路径是什么、参数放哪**，无法工作。
2. **完整接口会带进「本地才有的类型」**：签名里会出现 `QueryWrapper`、`Page`、`UpdateWrapper` 这类**数据库层对象**，它们无法（也不该）被序列化成 HTTP 报文。

***

## 8. 落地必踩的坑（本项目真实踩过）

### 坑 1：不能把本地库对象跨服务传 —— `UpdateWrapper` 事件

原单体里「判题通过 → 题目通过数 +1」是这么写的：

```java
UpdateWrapper<Question> w = new UpdateWrapper<>();
w.eq("id", questionId);
w.setSql("acceptedNum = acceptedNum + 1");
questionService.update(w);      // 原子 +1，避免并发丢更新
```

**问题**：`UpdateWrapper` 是**本地数据库对象**，塞不进 HTTP 请求体；更根本的是，`question` 表**属于 question-service**，判题服务不该直接写别人的库。

**改法**：换成**具名方法** `updateQuestionAcceptedNum(questionId)` —— 调用方只说「哪道题」，
真正的原子 SQL 由数据归属方 `question-service` 在 `QuestionInnerController` 里执行：

```java
// question-service 侧：SQL 留在数据归属方，并发安全也没丢
updateWrapper.setSql("acceptedNum = acceptedNum + 1");
```

> 通用原则：**跨服务调用要传「意图」而不是「构造器/包装器」。谁的表，谁负责写。**

### 坑 2：Feign 的 `name` 写错不报编译错

`name` 必须**逐字**等于对方的 `spring.application.name`。写错只会在运行时表现为「找不到服务」或调用失败，编译期完全无感。

### 坑 3：Spring Cloud 2021.x 起，`openfeign` 不再自带负载均衡器

只加 `spring-cloud-starter-openfeign` 启动会直接报：

```
IllegalStateException: No Feign Client for loadBalancing defined.
Did you forget to include spring-cloud-starter-loadbalancer?
```

**本项目已修复**：`question-service` 与 `judge-service` 的 `pom.xml` 里都**显式**补了 `spring-cloud-starter-loadbalancer`。

### 坑 4：长耗时调用要异步，否则拖垮用户请求

判题（跑沙箱）可能要几秒到几十秒。`question-service` 的提交接口不阻塞用户：

```java
// 提交记录落库后，异步触发判题，立刻把 questionSubmitId 返回给前端
CompletableFuture.runAsync(() -> {
    judgeFeignClient.doJudge(questionSubmitId);
});
```

> 同步调用会一直占着 Tomcat 线程直到判题结束，高并发下线程池很快耗尽。

### 坑 5：本地循环依赖「消失」了

原单体里 `question-service` 注入 `judgeService`，而 `judge-service` 又要回调题目，形成环，需要 `@Lazy` 打破。
改成远程代理后，`JudgeFeignClient` 只是一个**代理对象**，本地不存在真实的 `JudgeServiceImpl`，环自然不存在 → `@Lazy` 可以去掉。

### 坑 6：实体序列化的两个细节

- 跨服务传的是**实体**（`User` / `Question` / `QuestionSubmit`），走 JSON 编解码，**字段名必须两边一致**。
- `updateQuestionSubmitById(@RequestBody QuestionSubmit qs)` 传的是**只带需要更新字段的稀疏对象**（如只 set id + status），服务端用 `updateById` 只更新非 null 字段 —— 这是 MyBatis-Plus 的语义，靠调用方「少传」来实现「局部更新」。

***

## 9. 依赖清单：service-client 的 pom 拆开看

```xml
<artifactId>haxoj-backend-service-client</artifactId>
<description>服务调用模块：各微服务对外暴露的 Feign 客户端接口定义</description>

<dependencies>
    <!-- ① Web：契约里要用 @GetMapping/@PostMapping/@RequestParam 等 MVC 注解 -->
    <dependency>spring-boot-starter-web</dependency>

    <!-- ② OpenFeign：提供 @FeignClient 注解与动态代理能力（版本由父工程 BOM 锁定） -->
    <dependency>spring-cloud-starter-openfeign</dependency>

    <!-- ③ 复用实体与统一返回对象（方法签名里要用 User / Question / QuestionSubmit） -->
    <dependency>haxoj-backend-common</dependency>
    <dependency>haxoj-backend-model</dependency>
</dependencies>
```

必须理解的几点：

- **为什么需要** **`spring-boot-starter-web`？** 因为契约用的是 Spring MVC 的注解（`@GetMapping` 等），这些注解在 web 依赖里。它**不代表这个模块是个 Web 应用** —— service-client 只是个 jar 库，没有启动类。
- **为什么有** **`openfeign`？** `@FeignClient` 注解本身来自这个 starter。
- **为什么需要** **`common`** **/** **`model`？** 契约的方法签名引用了实体类型，编译期必须能解析到它们。
- **为什么**不再**需要** **`mybatis-plus`？** 这是重构的关键收益：因为契约里**杜绝了** **`IService`** **/** **`Page`** **/** **`QueryWrapper`**，方法签名只用实体和 JDK 集合，所以依赖可以彻底摘掉。pom 里甚至留了一行注释说明这件事。
- **消费方还要额外加什么？** `spring-cloud-starter-openfeign`（@EnableFeignClients 生效）+ `spring-cloud-starter-loadbalancer`（选址）。这两条不在 service-client 里，而在**每个消费方服务自己的 pom**、以及启动类的 `@EnableFeignClients`。

> 一句话：**service-client 只管「契约长什么样」；能不能真的发出去，取决于消费方自己有没有配齐 openfeign + loadbalancer +** **`@EnableFeignClients`** **+ Nacos。**

***

## 10. 和其它方案对比

| 方案            | 通信方式        | 调用体验        | 优点               | 缺点                     | 本项目           |
| ------------- | ----------- | ----------- | ---------------- | ---------------------- | ------------- |
| **OpenFeign** | HTTP + JSON | 声明式接口，像本地调用 | 标准 REST、注解风格、易调试 | 性能不如 RPC；需注意超时/重试      | ✅ 采用          |
| RestTemplate  | HTTP + JSON | 编程式，手动拼     | 直观、无需注解          | 代码啰嗦、无负载均衡、无契约         | 未用            |
| WebClient     | HTTP（响应式）   | 链式编程        | 非阻塞              | 学习成本高                  | 未用            |
| **Dubbo**     | 私有 RPC 协议   | 像本地调用       | 性能高、可共享完整接口      | 需额外注册/协议体系，与 HTTP 生态割裂 | 未用            |
| 消息队列（MQ）      | 异步消息        | 解耦、削峰       | 天然异步、可重试         | 不适合「要立刻拿到返回值」的场景       | 本项目**未引入 MQ** |

> 判题这种「提交后可以稍后返回结果」的场景**理论上也可以**改用 MQ 解耦，
> 但本项目走的是 **Feign +** **`CompletableFuture`** **异步**的方案；项目里目前没有任何 MQ 依赖。

***

## 11. 排错速查表

| 报错 / 现象                                                 | 根因                                                                      | 排查方向                           |
| ------------------------------------------------------- | ----------------------------------------------------------------------- | ------------------------------ |
| `NoSuchBeanDefinitionException`（注入 `XxxFeignClient` 失败） | 消费方启动类没加 `@EnableFeignClients`，或契约不在其扫描包下                               | 检查启动类注解与包路径                    |
| `No Feign Client for loadBalancing defined`             | 缺 `spring-cloud-starter-loadbalancer`                                   | 消费方 pom 补依赖                    |
| 404                                                     | `path` 与 provider 的 `@RequestMapping` 前缀不一致，或 context-path 漏算           | 核对 `/api/xxx/inner` 完整路径       |
| 「找不到服务 / 无可用实例」                                         | `name` 与 provider 的 `spring.application.name` 不一致，或 provider 未注册到 Nacos | 查 Nacos 服务列表                   |
| 调用成功但字段全 null                                           | 两边实体字段名不一致，JSON 对不上                                                     | 对比 `model` 里实体字段               |
| 连接超时 / 长时间挂起                                            | Feign 未配置明确超时；长耗时任务未异步                                                  | 加超时配置；长任务改 `CompletableFuture` |
| 启动报多个 `@SpringBootConfiguration`                        | 同一模块里有多个 `@SpringBootApplication`                                       | 只保留一个启动类                       |

***

## 12. 一句话总结

> **service-client = 微服务的「跨服务通信契约层」。**
> 它用 `@FeignClient` + HTTP 注解（声明式）把「远程调用」包装成「接口调用」；
> Feign 靠**动态代理**在启动时把接口变成 Bean，靠**注解元数据 + 编解码 + Nacos + LoadBalancer**在运行时把方法调用变成 HTTP 往返；
> 它只放**精简的、能被 HTTP 表达**的契约（所以能摘掉 MyBatis-Plus），
> 提供方用 **inner Controller** 满足契约的路径 ——
> 从而让「谁负责什么数据、怎么被调用」这件事，**在各服务之间形成一份清晰、可解耦的约定**。

## 13. 待改进（诚实记录）

- **超时未显式配置**：Feign/Ribbon 未设置明确超时，长耗时判题在异常时会长时间挂起线程。
- **异步异常被吞**：`CompletableFuture.runAsync` 的返回值未被观察，判题失败时异常难以感知（也没有自定义线程池，用的是公共池）。
- **Sentinel 未真正启用**：依赖已引入，但契约上没有 `@SentinelResource`、也没有开启 Feign 的 Sentinel 支持，**当前没有实际的熔断/降级**。
- **`getLoginUser`** **每调用一次就远程查一次用户表**，可优化为直接用 session 中的用户对象。
- **judge-service 仍保留 datasource 配置与 MyBatis-Plus 依赖**，但它已不直接读写数据库（依赖 `common` 里的 `MyBatisPlusConfig` 才保留的）。

