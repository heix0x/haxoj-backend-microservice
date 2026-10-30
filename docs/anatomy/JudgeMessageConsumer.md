# JudgeMessageConsumer：判题消息消费者

> 一句话定位：judge-service 的 MQ 边界。**它自己不判题**——它做三件事：
> ① 把一条消息变成一次判题动作；② 判完决定「这条消息算不算处理完」（ack）；
> ③ 处理不完时决定「失败该往哪去」（落定 / 死信）。
>
> 颗粒度：单类 + 上下游协作方（Producer → 队列拓扑 → Consumer → `doJudge` → `finish`）。
> 代码截至 2026-10-28。改造过程与踩坑见 [`../notes/2026-10-22_RabbitMQ异步判题.md`](../notes/2026-10-22_RabbitMQ异步判题.md)、
> [`../notes/2026-10-27_判题正确性与幂等修复.md`](../notes/2026-10-27_判题正确性与幂等修复.md)。

---

## 0. 全链路一览

```
用户提交代码
  │ POST（经网关 8001）
  ▼
QuestionSubmitServiceImpl#doQuestionSubmit            [question-service]
  │ ① 落库：status=WAITING(0)，submitNum+1
  │ ② JudgeMessageProducer#sendJudgeMessage
  │    消息体只有 { questionSubmitId, timestamp }
  ▼
haxoj.judge.exchange ──judge.submit──▶ haxoj.judge.queue          [RabbitMQ]
  │                                        │ durable，声明了 DLX
  │                                        ▼
  │                              JudgeMessageConsumer#onJudgeMessage  [judge-service]
  │                                        │ MANUAL ack，三态分流
  │                                        ▼
  │                              JudgeServiceImpl#doJudge
  │                                        │ 查库 → 幂等短路 → claim 抢占
  │                                        │ → 沙箱执行 → 策略判题
  │                                        │ → finishQuestionSubmit 落定
  ▼                                        ▼
question-service 写库（status / judgeInfo / acceptedNum）
  │
  ▼
前端查看结果
        └── 系统性失败 ──nack(requeue=false)──▶ haxoj.judge.dlq（等重放）
```

| 环节 | 所在类 | 职责 |
|---|---|---|
| 提交入口 | `QuestionSubmitServiceImpl#doQuestionSubmit` | 校验、落库 WAITING、投递消息 |
| 投递 | `JudgeMessageProducer#sendJudgeMessage` | 只管发出去，不关心谁消费 |
| 拓扑 | 两侧 `RabbitMqConfig` + `MqConstants` | 常量集中；双方幂等声明，参数必须一致 |
| **消费（本篇主角）** | `JudgeMessageConsumer#onJudgeMessage` | 调度判题 + 确认决策 + 错误路由 + 状态兜底 |
| 判题编排 | `JudgeServiceImpl#doJudge` | 幂等、抢占、沙箱、策略、落定 |
| 数据落定 | `QuestionSubmitServiceImpl#finishQuestionSubmit` | 单事务：状态 + judgeInfo + acceptedNum |

---

## 1. 代码层面：完整工作链路

### 1.1 消息从哪来（上游）

`doQuestionSubmit` 把提交落库（`status=WAITING`）后，调用
`JudgeMessageProducer#sendJudgeMessage(questionSubmitId)`，发到
`haxoj.judge.exchange`（direct，routingKey=`judge.submit`）。

消息体 `JudgeMessage` 只有两个字段：

```java
private Long questionSubmitId;  // 题目提交记录 id
private Long timestamp;         // 发送时间戳，仅用于排查延迟
```

**只带 id 不带代码/题目内容**：消费方按 id 回查数据库拿最新数据。
消息是「去做这件事」的意图，不是数据快照——快照会过期，意图不会。

队列拓扑（`MqConstants` 集中常量，question-service 与 judge-service 各声明一次）：

```
haxoj.judge.exchange (direct)
  └─ judge.submit ──▶ haxoj.judge.queue (durable)
                        │ x-dead-letter-exchange: haxoj.judge.dlx
                        │ x-dead-letter-routing-key: judge.dlq
                        ▼
                   haxoj.judge.dlx ──▶ haxoj.judge.dlq
```

消费端 `application.yml` 的关键配置（与代码行为是一体的，读代码必须连着读）：

```yaml
listener.simple:
  acknowledge-mode: manual        # 手动 ack，确认权在代码手里
  prefetch: 1                     # 一次只取一条：判题是重任务，先保证公平与背压
  concurrency: 1
  max-concurrency: 2
  default-requeue-rejected: false # 连 ack 都抛异常时也不 requeue，走死信，不制造循环
```

### 1.2 消费入口签名

```java
@RabbitListener(queues = MqConstants.JUDGE_QUEUE, ackMode = "MANUAL")
public void onJudgeMessage(JudgeMessage message,
                           Channel channel,
                           @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag)
```

三个参数各司其职：`message` 是反序列化后的任务；`channel` + `deliveryTag` 是 RabbitMQ 的
「回执凭证」——**不调 ack，broker 就认为这条消息还在处理中**。确认权从框架手里收回到代码手里，
是后面所有错误路由的前提。

### 1.3 四条路径（核心决策表）

| # | 触发条件 | 提交状态副作用 | 消息动作 | 消息去向 | 日志级别 |
|---|---|---|---|---|---|
| 1 | `message == null` 或 id 为空 | 无 | `basicAck` | 删除 | WARN |
| 2 | `doJudge` 正常返回（含幂等短路、抢占失败） | 见 §1.5 | `basicAck` | 删除 | INFO |
| 3 | `doJudge` 抛 `BusinessException` | 尽力落定为 `FAILED + System Error` | `basicAck` | 删除 | WARN |
| 4 | `doJudge` 抛其他任何异常（含 `SystemException`） | 重置回 `WAITING` | `basicNack(requeue=false)` | **死信队列** | ERROR |

逐条看它为什么这么做：

**路径 1：空消息守卫。** 消息没有业务对象可指向，重试一万次还是空——直接 ack 删掉。
如果 nack 回队列，就是一条永远消费不掉的毒消息。

**路径 2：正常路径。** `judgeService.doJudge(questionSubmitId)` 是整条业务链（§1.4），
正常返回即 ack。**ack 的语义是「这件事我处理完了」，不是「这件事成功了」**——
判题失败（答案错误）同样是"处理完了"，只是结论是失败。

**路径 3：业务异常 → `markFailed` → ack。** 业务异常（提交不存在、题目不存在、状态不允许）
重试一万次结果一样，所以 ack 丢弃，防止毒消息卡队列。
但**不能只是 ack**：异常可能发生在抢占成功之后（状态已是 RUNNING），直接 ack 掉消息就再也没人管这条提交，前端永远转圈。
所以先尽力落定再 ack：

```java
private void markFailed(Long questionSubmitId) {
    JudgeInfo judgeInfo = new JudgeInfo();
    judgeInfo.setMessage(JudgeInfoMessageEnum.SYSTEM_ERROR.getValue()); // "System Error"
    judgeInfo.setMemory(0L);  judgeInfo.setTime(0L);
    QuestionSubmitFinishRequest request = new QuestionSubmitFinishRequest();
    request.setId(questionSubmitId);
    request.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());      // 3
    request.setJudgeInfo(JSONUtil.toJsonStr(judgeInfo));
    boolean marked = questionFeignClient.finishQuestionSubmit(request);
    // marked=false 或抛异常 → 只记日志，不再抛出（消息马上要 ack，抛了只会让消费者重试注定失败的消息）
}
```

两个细节：
- 用 `finish` 而不是 `updateById`：finish 带 `WHERE status=RUNNING` 守卫，
  **不会把一条已经成功的提交改坏**；重复调用也不会重复累加通过数。
- 兜底动作自身的异常被吞掉只记日志：此时最重要的是把消息 ack 掉，落定尽力而为。

**路径 4：系统异常 → `resetToWaiting` → `nack(requeue=false)` → 死信。**
系统异常（沙箱挂了、网络抖动、落定失败）换个时间重试可能成功，值得重试；
但**不能 `requeue=true`**——同一条消息会立刻回到队头被再次消费、再次失败，
形成死循环把消费者 CPU 打满。所以：

```java
resetToWaiting(questionSubmitId);                  // 先把状态从 RUNNING 拨回 WAITING
channel.basicNack(deliveryTag, false, false);       // 再放行到死信队列，等人工/定时重放
```

先重置后 nack 的顺序是有讲究的：状态拨回 `WAITING`，这条提交才**还能被重新抢占**（claim 的条件是 `WAITING`）。
否则死信重放时它卡在 `RUNNING`，claim 永远失败，等于判了死刑。
重置本身失败也只记日志：消息已经注定去死信，人工可以处理，再抛异常只会污染确认流程。

### 1.4 下游 `doJudge` 在干什么（衔接点）

消费者只调 `JudgeServiceImpl#doJudge(questionSubmitId)`，其内部五步：

1. **查**：Feign 回查提交与题目（不存在 → `BusinessException`，走路径 3）；
2. **幂等短路**：`status != WAITING` 直接返回，重复消息变成 no-op；
3. **原子抢占**：`claimQuestionSubmit` 一条 `UPDATE ... WHERE status=WAITING SET status=RUNNING`，
   抢不到就返回，保证同一提交不被判两次；
4. **执行 + 判题**：工厂创建沙箱（代理记日志）→ 沙箱跑代码 → 策略模式比对输出/资源
   （Java 策略有 1000ms 时间宽限）；沙箱 status≠1 时直接定性失败，不进策略；
5. **落定**：`finishQuestionSubmit`（单事务）写状态 + judgeInfo +（通过时）`acceptedNum+1`；
   返回 false → 抛 `SystemException`，走路径 4。

### 1.5 状态与消息的联合走向

提交状态机：`WAITING(0) → RUNNING(1) → SUCCEED(2) / FAILED(3)`。

| 场景 | 状态轨迹 | 消息轨迹 |
|---|---|---|
| 正常判完（AC） | WAITING → RUNNING → SUCCEED | ack，删除 |
| 正常判完（WA/TLE/MLE…） | WAITING → RUNNING → FAILED | ack，删除 |
| 重复消息 / 抢占失败 | 不变 | ack，删除 |
| 提交/题目不存在（抢占前抛） | 不变 | ack，删除 |
| 业务异常发生在抢占后 | RUNNING → FAILED（markFailed 兜底） | ack，删除 |
| 沙箱/网络故障 | RUNNING → WAITING（重置） | nack → DLQ |
| 落定失败（finish 返回 false） | 状态可疑 → WAITING（重置） | nack → DLQ |

**设计不变量：任何一条消息消费结束后，提交要么是终态，要么回到 WAITING，不存在「永远 RUNNING」。**

---

## 2. 抽象思想层面：设计理念与架构思路

### 2.1 总纲：把不确定的失败引导到确定的出口

MQ 消费的本质困难是 **at-least-once**：网络会断、进程会挂、消息会重。
这个类的全部设计可以归结为一句话——
**不追求「绝不失败」，只保证「无论怎么失败，每条消息和每个状态都有确定去处」**：
消息要么被 ack 删除，要么进死信等重放；状态要么是终态，要么回到 WAITING 可重试。
没有第五种悬置状态。

### 2.2 为什么异步化：时间解耦 + 削峰 + 故障隔离

改造前提交接口线程池直调判题 Feign；改造后投递消息即可返回。三层收益：

- **时间解耦**：提交方不需要知道判题何时发生、由谁发生，只依赖 exchange/routingKey；
- **削峰**：提交高峰进队列排队，消费者按能力（`prefetch=1`）匀速消费；
- **故障隔离**：judge-service 重启或变慢，提交接口毫秒级返回，任务在队列里不丢不堵。

代价是引入了分布式系统的全部经典问题（重复、丢消息、顺序），所以才有 §2.3–2.8 的配套设计。

### 2.3 消息只带 id：消息是意图，不是数据

代码、题目、用例都不进消息体。消费时按 id 回查，拿的是**当前真实状态**。
附带收益：消息小（网络/存储友好）、不过期（代码改了不需要"撤回"消息）、天然延迟容忍（堆积几天照样能消费）。
`timestamp` 不参与逻辑，只是排查"这条消息排了多久"时的线索。

### 2.4 幂等做在数据上，不做在通道上

at-least-once 下重复消费无法避免，与其追求 exactly-once（通道层几乎做不到），不如让重复消费**无害**。
本链路有三道闸，全部写在数据库上：

| 闸门 | 落点 | 挡住什么 |
|---|---|---|
| 幂等短路 | `doJudge` 开头：`status != WAITING` 跳过 | 已判完/判题中的重复消息 |
| 原子抢占 | `claim`：`UPDATE ... WHERE status=WAITING` | 并发双消费者同时判同一提交 |
| 落定守卫 | `finish`：`WHERE status=RUNNING` + 已是终态直接返回 true | 重复落定、状态回拨、通过数重复累加 |

**并发正确性写进 SQL 的 WHERE 条件，不写进 Java 的 if**——Java 的检查与更新之间永远有窗口。
MySQL 返回「匹配行数」天然就是 CAS 的结果值。

### 2.5 异常分类的本质：该不该重试，而不是谁抛的

`BusinessException` 与 `SystemException` 的区别不在调用层次，而在**重试价值**：

- 业务异常 = 换个时间再试结果一样（数据不存在、参数不合法）→ **不该重试** → ack 丢弃；
- 系统异常 = 换个时间再试可能成（网络抖动、下游忙）→ **值得重试** → 进死信等重放。

异常类型在这里不是"错误报告"，而是**路由协议**——它直接决定消息去向。
判别口诀（见 `SystemException` 类注释）：「换个时间再试一次，有可能成功吗？」
消费者只是这个协议的执行器：`catch` 两个异常类型分别走向两条确定出口。

### 2.6 确认策略即错误路由表

传统理解 ack/nack 是"成功/失败"的回执；这里把它升级为**三路错误路由器**：

```
            ┌─ 成功 ──────────── ack（删除）
消息 ──────┼─ 业务失败 ── 尽力落定 ── ack（删除，不算毒消息）
            └─ 系统失败 ── 重置 WAITING ── nack(requeue=false) ── DLQ（延迟重试）
```

关键取舍是 **`requeue=false` 而非 `requeue=true`**：
requeue=true 是"立刻重试"，失败原因是系统性的就会立刻再失败，循环打满 CPU——
**自动重试没有退避机制时，等于拒绝服务**。DLQ 的本质是把「何时重试」的决策权
从 broker 手里收回到人/定时任务手里：换时间、换修好的依赖，再重放。

### 2.7 毒消息防护：先分类，再处置

"毒消息"= 无论消费多少次都失败的消息。若一视同仁地重试，一条坏消息能堵死整个队列。
对策是**先判断失败会不会自愈，再决定处置**：
- 不会自愈（业务异常）→ 立刻 ack 出队 + 落定失败态，队列继续流动；
- 可能自愈（系统异常）→ 移出主队列（DLQ），不占消费者，也不丢；
- 完全无意义（空消息）→ 直接 ack 销毁。

队列的**吞吐可用性**优先于单条消息的重试次数。

### 2.8 状态机完整性：不许中间态泄漏

异步系统最隐蔽的事故不是崩溃，而是**没人认领的中间态**：
消息 ack 了、状态停在 RUNNING、前端转圈到天亮。
本类的两条兜底路径（`markFailed` / `resetToWaiting`）就是为堵这个洞存在的：

- 业务异常不能"只 ack"——异常可能发生在抢占后，此时必须先把提交落到终态；
- 系统异常不能"只 nack"——消息去死信了，状态得先拨回 WAITING，重放时还能被 claim。

更进一步，兜底用的 `markFailed` 走 `finish` 而不是裸更新，因为兜底动作自身也可能重复执行，
必须同样幂等。**兜底路径和正常路径共享同一套幂等约束，没有第二套逻辑。**

### 2.9 谁的数据谁写：边界放在契约里

judge-service 想改提交状态、想给通过数 +1，但**不自己写库**，
而是调 `QuestionFeignClient` 的具名方法（`claim` / `finish` / `updateQuestionSubmitById`），
由 question-service 执行。三点收益：

- `UpdateWrapper` 这类本地库对象不出现在 HTTP 边界上；
- 「状态 + 通过数」能放进同一个事务（一次 finish = 一次事务）；
- 契约语义精确到动作：`finish` 的返回值只回答一件事——**要不要重试**（true=已是终态，false=请重试），
  调用方不需要理解对方的状态机细节。

### 2.10 薄消费者：Consumer 只做调度与路由

回看整个类：没有判题逻辑、没有 SQL、没有状态机判断。
它只回答三个问题——**派谁干活（`doJudge`）、干完认不认（ack）、干不成往哪放（落定/DLQ）**。
业务正确性全部下沉到 `JudgeServiceImpl` + `finish` 的事务里。
这样切的好处：确认策略的变化（比如将来加"延迟重试"）只动这一个类，
判题逻辑的变化（比如加语言）完全不碰它——两种变化被隔离在两边。

### 设计原则速查表

| 问题 | 对策 | 代码落点 |
|---|---|---|
| 同步调用拖垮提交接口 | 异步 + 队列削峰 | `JudgeMessageProducer`、`prefetch=1` |
| 消息里的数据过期 | 只带 id，消费时回查 | `JudgeMessage` |
| 重复消费判两次 | 三道数据层幂等闸 | 幂等短路 / `claim` / `finish` |
| 毒消息卡队列 | 按"重试价值"分类处置 | `BusinessException` → ack |
| 重试死循环打满 CPU | 禁 requeue=true，改走 DLQ | `basicNack(..., false)` |
| 停留在「判题中」没人管 | 双兜底落定/重置 | `markFailed` / `resetToWaiting` |
| 状态与计数不一致 | 单事务落定 | `finishQuestionSubmit` |
| 跨服务乱写数据 | 谁的数据谁写 | `QuestionFeignClient` 具名契约 |

---

## 3. 边界与已知取舍

| 边界 | 现状 | 代价 |
|---|---|---|
| DLQ 无自动重放 | 人工/后续做定时重放工具 | 系统异常的消息堆积在 DLQ 需人工关照 |
| `markFailed` 结论粗 | 统一落 `System Error` | 用户看不到"提交不存在/题目不存在"的真实原因 |
| `resetToWaiting` 失败只记日志 | 依赖人工从 DLQ 补 | 极端情况下提交仍停在 RUNNING |
| `timestamp` 只用于排查 | 不参与超时判断 | 消息堆积延迟无自动告警 |
| 空消息直接销毁 | 不留档 | 排查来源靠 broker 侧日志 |

遗留清单（DLQ 重放工具、traceId 透传等）见 [`../known-issues.md`](../known-issues.md) 与
[`../notes/2026-10-27_判题正确性与幂等修复.md`](../notes/2026-10-27_判题正确性与幂等修复.md) §8。

---

## 4. 相关文档

| 文档 | 关系 |
|---|---|
| [`../notes/2026-10-22_RabbitMQ异步判题.md`](../notes/2026-10-22_RabbitMQ异步判题.md) | 历史：本类怎么从"线程池直调"演变成今天这样，拓扑实测与踩坑 |
| [`../notes/2026-10-27_判题正确性与幂等修复.md`](../notes/2026-10-27_判题正确性与幂等修复.md) | 历史：`markFailed` 兜底、单事务 finish 的修复过程 |
| [`../notes/2026-09-17_判题功能全景解析.md`](../notes/2026-09-17_判题功能全景解析.md) | 判题链路全景（含判题策略，本篇 §1.4 第 4 步的展开） |
| [`../architecture.md`](../architecture.md) | 系统全景：§3.3 判题链路、§4.6 幂等 |
| [`../conventions.md`](../conventions.md) | 异常分类、日志级别的全局约定 |
