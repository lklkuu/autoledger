# 两个遗留问题的分析与修复方案

> 分析对象：AutoLedger（打工人小账本）v1.1.7，HEAD `a112a99`
> 分析方法：静态阅读代码 + 调用链追踪。**本次未改动任何代码**，结论均由 file:line 支撑。

---

## 结论先行

| | 问题一 `direction` 未透传 | 问题二 `coroutines.core` scope |
|---|---|---|
| **真实性质** | 不是「漏写一行映射」，而是**信封里压根没有这个字段**；方向信息寄生在金额符号上 | 不是「scope 用错」，而是**这条声明完全冗余**（`core:model` 已用 `api` 导出） |
| **当前可见影响** | 低（需满足「金额缺失」才触发） | **零**（体积、行为、编译均不变） |
| **潜在影响** | **一笔收入被记成支出**，且是静默的 | 架构信号失真，误导后续依赖决策 |
| **是不是"已存在的 bug"** | 是，但触发面窄；且代码里已用「加正则」做了治标缓解 | 否，纯声明债 |
| **修复规模** | 中（3 处代码 + 1 处 AI 信号 + 测试） | 极小（**4 行** build 脚本） |
| **static_check 能否捕获** | **不能** | **不能** |

**两条独立结论**：

1. 问题一值得修，且**必须连带修 AI 的触发信号**（见 §1.5.3）——否则修复会把「已能本地判出」的记录仍然发往外部服务，构成隐私口径的回归。
2. 问题二的正确描述不是「应收窄」而是「**可以整条删掉**」；改为 `testImplementation` 是次优但更诚实的做法。零风险，随时可做。
   ⚠️ **初稿说「只有三个模块」是错的**：独立 QA 审计证伪，实际同一缺陷类共 **4 处**（`feature:stats` / `classify` / `dedup` / **`core:backup`**）。详见 §2.4 修订记录。

---

# 问题一：`NotificationParser.ParseResult.direction` 未透传

## 1.1 性质：不是漏写映射，而是「字段在边界上不存在」

最容易误判的一点：这不是 `toRawEnvelope` 漏抄了一行。`RawEnvelope`（`core/model/src/main/java/com/autoledger/core/model/Model.kt:141`）**根本没有 `direction` 字段**，它只有：

```kotlin
val amountHint: Long? = null,       // 有符号，负=流出
val explicitType: TxnType? = null,  // EXPENSE/INCOME/TRANSFER/REFUND
val packageName: String? = null,
```

而 `ParseResult.direction`（`feature/capture/.../notify/NotificationParser.kt:23`）在整个 `feature:capture` 的 main 源码里**只有一个消费者**——它自己：

```kotlin
// NotificationParser.kt:58-63
val signed = when (hit.direction) {
    Direction.OUT -> amount?.let { -kotlin.math.abs(it) }
    Direction.IN  -> amount?.let {  kotlin.math.abs(it) }
}
return ParseResult(hit.id, hit.label, signed, counterparty, hit.direction, hit.ledgerType)
```

也就是说：**`direction` 的唯一作用是把符号施加到金额上**。它本身不往下传，下游要通过「金额的符号」反推方向。

于是这个字段的行为很讽刺：

- **能算出来的时候，它是冗余的**（方向已由 `amountHint` 的符号携带）；
- **唯一不可替代的时候（金额缺失），它恰好被丢弃**（`signed` 也是 `null`）。

一句话概括：**`direction` 是一个在简单情形下 100% 冗余、在困难情形下 100% 丢失的字段。**

## 1.2 后果：收入被静默记成支出

丢失点在类型判定：

```kotlin
// IngestPipeline.kt:288
internal fun resolveInitialType(explicitType: TxnType?, amount: Long?): TxnType = when {
    explicitType != null -> explicitType
    amount == null -> TxnType.EXPENSE     // ← 方向信息在此刻已经不存在了，只能兜底成"支出"
    amount < 0 -> TxnType.EXPENSE
    else -> TxnType.INCOME
}
```

触发链（`IngestPipeline.ingest`）：

| 步骤 | 位置 | 金额缺失时的行为 |
|---|---|---|
| 1 | `IngestPipeline.kt:84` | `resolveInitialType(null, null)` ⇒ **EXPENSE** |
| 2 | `IngestPipeline.kt:105-123` | 划转检测用 `amount ?: 0L`，可能再改类型 |
| 3 | `IngestPipeline.kt:127-146` | 因为类型已是 EXPENSE ⇒ **照常跑分类器**，给它分一个支出类目 |
| 4 | `IngestPipeline.kt:150` | 金额为 null ⇒ 跳过跨渠道去重（`duplicates = emptyList()`） |
| 5 | `IngestPipeline.kt:152-170` | `unresolvedAmount = true` ⇒ `shouldConfirm = false` ⇒ `status = RAW`（进「待确认」） |
| 6 | `IngestPipeline.kt:88` | 落库时 `amountMinor = amount ?: 0L` ⇒ **记成一笔 0 元支出** |

具体后果：

1. **方向记反**：一笔真实收入以「0 元支出」的形态进入待确认队列。用户在待确认里补上金额后，**类型仍然是 EXPENSE**（类型不会因为补金额而重算），于是这笔收入永久留在支出侧，直接抬高当月支出、压低结余。
2. **污染统计口径**：一旦用户没注意就确认了，它会进入分类统计（已预先分了支出类目），使「消费结构」多出一笔假支出。
3. **去重静默失效**：金额缺失时跳过去重，意味着同一笔交易的银行短信 + App 动账通知**都不会被合并**（连候选都不会列出），这正是 1.1.5 花力气解决的「跨渠道重复」问题在金额缺失路径上的复发。
4. **不可察觉**：没有任何告警。用户只有在待确认队列里逐条核对时才发现——而队列里显示的是一条 0 元支出，看金额根本分辨不出它本该是收入。

## 1.3 典型成因：不是「正则写得不好」，是架构上缺一条通道

要区分「表层成因」与「根本成因」：

**表层成因**（为什么金额会缺失）：各收入规则的正则没覆盖到全部真实文案形态。例如 `sms_bank_in` 的第三条正则注释就明确写了这个危险：

```kotlin
// NotificationRule.kt:305-308
// ③ 紧跟收入关键词：中间允许夹「注记」括号或「入账 / 到账 / 发放」连接词，
//    用于覆盖「收入(整整到期)5,055」这种**没写「元」**的形态
//    （取不到金额时 IngestPipeline 会把类型兜底成 EXPENSE，等于记反方向）。
```

**这段注释是本问题最有力的证据**：代码作者已经知道「金额缺失 ⇒ 记反方向」，但选择的缓解方式是**把正则写得更宽**。这是治标——正则永远追不上银行文案的变化（大写金额「伍仟元整」、金额跨行、金额在图片里、新银行新模板）。

**根本成因**：**已经确定方向的信息，在信封边界上没有承载它的字段**。规则命中本身就意味着「我知道这笔是流入还是流出」（这是规则的一部分，不是从文本里猜的），但这个**已知事实**在 `ParseResult → RawEnvelope` 的转换中没有任何通道，只能退化到「靠金额符号反推」。一旦金额没解析出来，这个已知事实就被丢掉，下游只能猜，而猜的默认值是 EXPENSE。

**为什么默认值是 EXPENSE 而不是「不确定」**：`resolveInitialType` 的注释解释了显式类型优先的理由（退款金额是正数），但没有解释 `amount == null → EXPENSE` 的取向。这是**一个有意偏向的兜底**——对支出流水（占绝大多数）来说这是对的；对收入来说它就是错的。也就是说，兜底方向对「数量占多数的支出」优化，代价是「少数收入」被记反。

## 1.4 受影响范围

**受影响的规则：2 条。**

| 规则 ID | `direction` | `ledgerType`（explicitType） | 是否受影响 |
|---|---|---|---|
| `refund_wechat`（:137） | IN | **`TxnType.REFUND`**（:147） | ✅ 安全 —— 显式类型优先级最高，绕过了金额判定 |
| `refund_alipay`（:150） | IN | **`TxnType.REFUND`**（:158） | ✅ 安全 |
| `refund_generic`（:161） | IN | **`TxnType.REFUND`**（:169） | ✅ 安全 |
| **`wechat_receive`（:191）** | IN | **未设置（null）** | ⚠️ **受影响** |
| **`sms_bank_in`（:282）** | IN | **未设置（null）** | ⚠️ **受影响** |
| `bank_generic_out`（:221） | OUT | null | 不受影响（兜底 EXPENSE 恰好正确） |
| `wechat_pay` / `alipay_pay` | OUT | null | 不受影响 |

**规律**：所有 `direction = IN` 且 `ledgerType = null` 的规则都受影响。当前是 `wechat_receive` 与 `sms_bank_in` 两条。

**这个分布的成因值得注意**：退款规则之所以安全，是因为它们被**另一个机制**（显式类型）保护了——不是因为方向本身被正确传递。换句话说，当前代码里没有一条收入规则是靠 `direction` 保证方向的；它们全靠「金额符号」或「explicitType」二者之一。**任何新增的收入规则，只要忘了设 `ledgerType`，就会掉进这个坑。**

**为什么不建议通过「给收入规则也设 `ledgerType = TxnType.INCOME`」来绕过**：`NotificationRule.kt:38-40` 的注释明确禁止这么做：

```kotlin
// 缺省 null = 交由流水线按金额正负推断。**不要用 [direction] 推导**：
// `wechat_receive` / `sms_bank_in` 同样是 IN，但它们应是 INCOME，只有退款才是 REFUND。
```

即：`direction`（IN/OUT，2 值）与 `TxnType`（4 值）**不是同一个抽象**，不能用 `direction.toTxnType()` 直接映射。这个约束决定了修法的形状（见下）。

## 1.5 定位与修复

### 1.5.1 定位手法

1. **找「已确定但未承载」的字段**：在解析层 (feature:capture) 里搜 `ParseResult` 的所有字段，逐个问「谁读了它」。`direction` 的答案是「只有解析器自己」——这类**只写不读**的字段就是丢失信号的停尸房。

   ```bash
   grep -rn "\.direction" --include="*.kt" feature/capture/src/main   # → 仅 NotificationParser 自身
   ```

2. **沿判定链反查兜底值**：从 `resolveInitialType` 出发问「每个分支的默认值对哪一侧有利」。

   ```bash
   grep -rn "resolveInitialType" --include="*.kt" .   # → IngestPipeline:84 / TypeRefiner:51 / TypeRefiner:45
   ```

3. **找受影响的规则**：交叉 `direction == IN` 与 `ledgerType == null` 两条声明。

4. **构造可复现样本**（最小复现）：一条命中 `sms_bank_in` 但金额正则取不到的收入短信，例如把现有测试样本
   `"您尾号1234账户收入人民币1,234.50元"` 改成不含「元」也不含币符、且金额形态在正则之外的变体。

### 1.5.2 修复方案（推荐 A）

**A. 给信封加方向提示，并把它接进判定链**

1. `core/model/.../Model.kt` — `RawEnvelope` 新增字段：
   ```kotlin
   /** 采集端由规则已知的收支方向。仅在 amountHint 缺失时用于判定；非空时优先于金额符号之外的一切兜底。 */
   val directionHint: Direction? = null,
   ```
2. `feature/capture/.../notify/CaptureEnvelopeFactory.kt:19` — 补上 `directionHint = parsed.direction`。
3. `feature/capture/.../IngestPipeline.kt:288` — 扩展判定链（保持 `explicitType` 最高优先，**不得**用 direction 推导 `explicitType`）：
   ```kotlin
   internal fun resolveInitialType(
       explicitType: TxnType?, amount: Long?, direction: Direction? = null,
   ): TxnType = when {
       explicitType != null -> explicitType
       amount != null      -> if (amount < 0) TxnType.EXPENSE else TxnType.INCOME
       direction != null   -> if (direction == Direction.OUT) TxnType.EXPENSE else TxnType.INCOME
       else                -> TxnType.EXPENSE
   }
   ```
4. `IngestPipeline.kt:84` 的调用点传入 `envelope.directionHint`。

**保留 `Direction` 与 `TxnType` 两个抽象不合并**：`directionHint` 只回答「流入还是流出」，`explicitType` 仍由规则显式声明（退款/划转）。这正好符合 `NotificationRule.kt:38-40` 的既有约束。

**B. 不推荐的替代方案及原因**

| 方案 | 不推荐的理由 |
|---|---|
| 给收入规则补 `ledgerType = INCOME` | 违反 `NotificationRule.kt:38` 的显式约定；把「方向」和「账本类型」两个抽象混成一谈，退款/划转会开始出错 |
| 继续加宽金额正则 | 就是当前的治标做法，已被证明追不上文案变化；且**无法覆盖「金额确实不存在」的场景** |
| 默认值从 EXPENSE 改成「不确定」 | 会把海量正常的有金额支出也变模糊，改动面远大于收益 |

### 1.5.3 ⚠️ 必须连带修改：AI 的「本地判不出」信号

**这是最容易被漏掉的一环。** AI 二次判定的触发条件是：

```kotlin
// feature/ai/.../AiConfig.kt:41-44
internal fun shouldAskAi(mode: AiMode, amountMinor: Long?): Boolean = when (mode) {
    AiMode.FALLBACK -> amountMinor == null      // ← 「本地判不出」的判据
    AiMode.ALWAYS -> true
}
```

而 `TypeRefineRequest`（`feature/capture/.../TypeRefiner.kt:27`）只携带 `text / amountMinor / localGuess`，**没有方向**。

那个注释写得很明确：「本地"判不出"的可观测信号**就是金额缺失**（`resolveInitialType` 在有金额时永远能按正负给出 EXPENSE/INCOME）」。

**修完 §1.5.2 之后，这个前提就不成立了**：金额缺失但方向已知的记录，本地已经能判出结论，而 `shouldAskAi` 仍会认为「本地判不出」⇒ **把整条通知原文发往外部服务**——为一件本地已解决的事出网，与本项目反复强调的隐私口径（「默认关闭 = 全本地处理，通知原文不出设备」）相冲突。

因此同一批改动里必须：

1. `TypeRefineRequest` 增加 `directionHint: Direction?`（或一个 `localWasAmbiguous: Boolean`，推荐前者，语义更直接）；
2. `shouldAskAi(mode, amountMinor, directionHint)` 的 FALLBACK 分支改为 `amountMinor == null && directionHint == null`；
3. 更新 `AiConfig.kt:26-33` 那段 KDoc——它现在的措辞在修改后就是错的。

> 当前 AI 入口是关闭的（`AiFeatureGate.ENTRY_VISIBLE = false`），所以这是**潜伏问题**：不修也不会立刻出网，但一旦恢复上线，契约会静默失效。**修问题一的时候一起修掉，代价最小。**

### 1.5.4 另一个需要同步的地方：app 模块的「镜像」函数

`app/src/test/java/com/autoledger/app/ingest/DigitalRmbNotificationReproTest.kt:103` 因为够不到 `internal` 可见性，**手写了一份 `toRawEnvelope` 的复制品**：

```kotlin
/** 与 CaptureEnvelopeFactory.toRawEnvelope 字段一一对应（app 模块够不到它的 internal 可见性）。 */
private fun toEnvelope(n: Notif): RawEnvelope { ... }
```

它靠**注释**与真身保持同步，编译器不检查。给 `RawEnvelope` 加字段后：

- 若不同步，该测试仍然编译通过（新字段有默认值 `null`），但**测到的不再是生产行为**——这正是「测试绿但没测到东西」的典型形态；
- 建议：同步补上 `directionHint = parsed?.direction`，并把「字段一一对应」从注释升级为一个**反射断言**（比较 `RawEnvelope` 构造参数个数/名称与镜像函数所传字段），让它无法静默漂移。

## 1.6 修复后需要覆盖的验证点

**A. 判定链本体（纯函数，JVM 单测即可钉死）**

| # | 验证点 | 期望 |
|---|---|---|
| A1 | `resolveInitialType(null, null, Direction.IN)` | `INCOME` ← **本次修复的核心断言** |
| A2 | `resolveInitialType(null, null, Direction.OUT)` | `EXPENSE` |
| A3 | `resolveInitialType(null, null, null)` | `EXPENSE`（兜底行为不得变） |
| A4 | `resolveInitialType(null, -500L, Direction.IN)` | `EXPENSE` —— **金额存在时金额优先**，方向不得推翻金额 |
| A5 | `resolveInitialType(TxnType.REFUND, null, Direction.IN)` | `REFUND` —— explicitType 仍最高优先 |
| A6 | 变异测试：删掉 `direction != null` 那一分支 | A1 必须变红（证明断言有鉴别力，不是恒真） |

**B. 信封映射（`feature:capture` 单测）**

| # | 验证点 | 期望 |
|---|---|---|
| B1 | `toRawEnvelope(..., parsed = ParseResult(direction = IN, amountMinor = null, ...))` | `env.directionHint == IN` **且** `env.amountHint == null` |
| B2 | 同上但 `amountMinor = 1230L` | `directionHint == IN`、`amountHint == 1230L` 并存，互不覆盖 |
| B3 | 既有 4 条 `CaptureEnvelopeFactoryTest` 用例 | 全部保持绿（尤其 `explicitType` 三条不得回归） |

**C. 端到端（`IngestPipeline` 层，需覆盖「金额缺失」这条此前无人走的路径）**

| # | 验证点 | 期望 |
|---|---|---|
| C1 | 一条命中 `sms_bank_in`、金额正则取不到的短信走完 `ingest` | `type == INCOME`（修复前是 EXPENSE）、`amountMinor == 0`、`status == RAW` |
| C2 | 同上，断言**没有**被分到支出类目（`categoryId == null`），因为分类只对 EXPENSE 跑 | 修复后类型是 INCOME ⇒ 分类器不应被调用 |
| C3 | 一条命中 `wechat_receive`、金额取不到的通知 | 同上为 INCOME |
| C4 | 三条退款规则在金额取不到时 | 仍为 `REFUND`（确认 explicitType 通道未被破坏） |
| C5 | 真实样本回归：`「工商银行收入(整整到期)5,055元」` | 仍为 INCOME 且金额正确（这条是 1.1.5 修过的样本，必须不退化） |

**D. AI 信号（`feature:ai` 纯函数单测）**

| # | 验证点 | 期望 |
|---|---|---|
| D1 | `shouldAskAi(FALLBACK, amountMinor = null, directionHint = IN)` | **`false`** ← 修复前是 `true`，即「已判出却仍出网」 |
| D2 | `shouldAskAi(FALLBACK, null, null)` | `true`（真·判不出，仍要问） |
| D3 | `shouldAskAi(ALWAYS, null, IN)` | `true`（全覆盖模式下不受影响） |
| D4 | `TypeRefineRequest` 携带的 `directionHint` 与 `localGuess` 一致 | 断路器：若两者矛盾说明判定链接线错了 |

**E. 真机 / 集成（JVM 测试覆盖不到的部分）**

| # | 验证点 | 说明 |
|---|---|---|
| E1 | 用真实银行收入短信（金额规范不匹配的形态）走采集 | 待确认队列里应显示为**收入**，而非 0 元支出 |
| E2 | 该笔记录在补上金额并确认后，仍留在收入侧 | 验证「类型不因补金额而漂移」 |
| E3 | 若 AI 恢复上线：确认该场景**不产生网络请求** | 属于隐私口径，必须真机抓包或看日志验证，JVM 测试不能作为证据 |

**F. 回归面**

- 全量单测 `./gradlew test --rerun-tasks` 必须 0 失败（当前基线 **1189** 条）；
- `python tools/static_check.py` 通过；
- 特别关注 `IngestPipelineTypeTest`、`BankIncomeCrossChannelIngestTest`、`DigitalRmbNotificationReproTest`、`AiTypeRefinerTest` 四个用例集——它们直接坐在这次改动的路径上。

---

# 问题二：三个模块的 `coroutines.core` 应收窄为测试期依赖

## 2.1 性质：不是 scope 用错，而是声明完全冗余

`feature:stats`(:17) / `feature:classify`(:23) / `feature:dedup`(:17) 三个模块各有一行：

```kotlin
implementation(libs.kotlinx.coroutines.core)
```

**核查结果：三个模块的 main 源码对 coroutines 的引用数为 0。**

```bash
grep -rn "^import kotlinx" feature/{stats,classify,dedup}/src/main   # → 全部为空
grep -rn "kotlin\.coroutines" feature/{stats,classify,dedup}/src/main # → 全部为空
```

那 main 里出现的 `suspend` 是什么？**它是 Kotlin 语言关键字，编译产物是 stdlib 的 `kotlin.coroutines.Continuation`，与 `kotlinx-coroutines-core` 无关。** 三个模块 main 里所有命中都是 `override suspend fun`（`Metrics.kt` / `Classifiers.kt` / `LedgerDuplicateResolver.kt`）——它们是**抽象的 suspend 契约实现方**，谁会调、在哪个调度器上跑，由 app 层决定。

**更强的证据**：`core:model/build.gradle.kts:20` 用的是 **`api`**：

```kotlin
// api：LedgerRepository 的公开契约 observeSince/observeRawCount 直接返回 Flow，
// 使用方（core:database / feature:* / app）在编译期必须能解析 kotlinx-coroutines 类型，
// 继续用 implementation 会造成 API 泄漏（编译期提示 cannot access class ...Flow）。
api(libs.kotlinx.coroutines.core)
```

三个模块都 `implementation(project(":core:model"))`，因此 **coroutines-core 已经通过 `api` 传递到它们的编译类路径上**。结论：

> 这三个模块里的 `implementation(libs.kotlinx.coroutines.core)` **即使整条删掉也不会编译失败**——它们并不需要这条声明。

所以准确的说法是「**冗余声明**」，而不是通常意义上的「依赖 scope 写错」。scope 确实也不对（main 不用，test 才用），但**根因是冗余**。

顺带核对：既然它们 main 里连 `Flow` 都不引用（`stats` 里唯一的 `Channel` 命中是一条讲 `ChannelShareMetric` 的历史注释），那 `core:model` 的 `api` 导出是否也被过度使用了？——**不是**，`LedgerRepository` 的公开契约确实返回 `Flow`，只是这三个模块的实现体只调用了 `suspend` 方法、没有在签名里暴露 `Flow`。`api` 的用法是对的。

## 2.2 后果

**当前后果：零。** 三个维度都无变化，这点必须说清楚，避免被当成性能问题：

| 维度 | 影响 | 原因 |
|---|---|---|
| APK 体积 | **0 字节** | `app` 已声明 `implementation(libs.kotlinx.coroutines.android)`（`app/build.gradle.kts:205`），coroutines 无论如何都在包里；R8 也会裁掉未用代码 |
| 运行时行为 | 无 | 只是类路径上少了一个重复来源 |
| 编译期 | 无 | 类型仍由 `core:model` 的 `api` 提供 |

> 注：`feature:stats` 是纯 JVM 模块（`kotlin.jvm` 插件），删掉 main 侧声明会把它从 stats 的 main runtime classpath 上摘掉。但 stats 最终被 `app` 消费，而 app 自带 coroutines —— 所以对最终产物仍无影响。

**真正的后果在别处，是「架构信号失真」**：

1. **让纯逻辑模块看起来依赖异步运行时**。`stats` / `dedup` 是标着「纯 JVM、可独立单测、可独立演进」的模块（`classify` 的 build 脚本注释也这么写）。一行 `implementation(coroutines)` 会让任何做架构审查的人（或 AI 代理）得出「这三个模块需要协程」的错误结论，进而影响「能不能把这几个模块抽成独立库/挪到别处」的判断。
2. **掩盖了真实的依赖来源**。当前 coroutines 进入这三个模块的**唯一**真实路径是 `core:model` 的 `api`。多出的这行让这条路径不可见——将来若有人想收紧 `core:model` 的 `api`（改成 `implementation`），会看到三处 `implementation` 声明而误以为「没关系，它们自己声明了」，结果一改就编译失败，排查成本远高于现在删掉这三行。
3. **与「声明债清理」的既有工作不一致**。1.1.6 已经清理过 7 条零引用依赖声明（见 CHANGELOG 1.1.6「裁剪 7 条源码零引用的依赖声明」），这三条属于**同一类、当时被漏掉**的。

## 2.3 典型成因

这类「冗余/超范围声明」通常来自四个来源，本项目命中的是第 1、4 条：

1. **复制粘贴模块骨架**：新建 `feature:*` 模块时从既有模块抄 `dependencies {}` 块。三个模块的声明行位置（stats:17 / classify:23 / dedup:17）与写法完全一致，符合复制痕迹。
2. **从可编译状态倒推**（本项目未命中）：先有 `import kotlinx.coroutines`，编译报错后补声明。
3. **实现中撤掉了协程使用，声明没跟着撤**（本项目未命中）：这三个模块的实现一直是纯 `suspend`，没有过 `launch` / `Flow` / `withContext`。
4. **误把 `suspend` 当成 `kotlinx.coroutines` 的 API**（很可能是本项目的直接原因）：`suspend` 语法上像库特性，实际上属于 stdlib。写模块时见到「我的类实现了 suspend 函数」，就顺手补了一条协程依赖。**这是 Kotlin 里最常见的依赖误判之一。**

**工具侧为什么会遗漏**：`tools/static_check.py` 的检查项 5（跨模块 import 必须有对应 `project()` 依赖）与检查项 8（API 泄漏）都只解析 **`project(":x")` 形式**的依赖：

```python
# static_check.py:48-51
DEP_KIND_RE = (
    r'(api|implementation|compileOnly|runtimeOnly|testImplementation|'
    r'androidTestImplementation|debugImplementation|releaseImplementation)'
    r'\s*\(\s*project\(\s*"([^"]+)"\s*\)'      # ← 只匹配 project(...)
)
```

**版本目录形式的依赖（`libs.kotlinx.coroutines.core`）根本不进入这两个检查的视野**，而「已声明但零引用」这种问题**目前没有任何检查项**。所以这几行能长期存活，不是谁疏忽，而是**没有护栏**。

## 2.4 受影响范围

**初稿写的「恰好这三个模块，无更多」是错的**，已由独立 QA 审计证伪并修订（见下方「修订记录」）。全仓 coroutines 声明盘点（**13 个模块**，`settings.gradle.kts` 为准）：

| 模块 | 声明 | 判断 |
|---|---|---|
| `core:model` | `api(kotlinx.coroutines.core)` | ✅ 正确 —— `LedgerRepository` 公开返回 `Flow`，必须 `api` |
| `feature:stats` | `implementation(kotlinx.coroutines.core)` | ⚠️ **冗余**（main 零引用） |
| `feature:classify` | `implementation(kotlinx.coroutines.core)` | ⚠️ **冗余**（main 零引用） |
| `feature:dedup` | `implementation(kotlinx.coroutines.core)` | ⚠️ **冗余**（main 零引用） |
| **`core:backup`** | `implementation(kotlinx.coroutines.android)` | ⚠️ **同一缺陷类的第 4 处**（main 零引用，仅测试用 `runBlocking`）—— **初稿把它误判为「合理」，已收窄** |
| `app` | `implementation(kotlinx.coroutines.android)` | ✅ 正确 —— 实测 `Dispatchers.Main` **11 处**，确实需要 `-android` |
| `core:database` / `feature:capture` / `feature:transfer` / `feature:ai` | `implementation(kotlinx.coroutines.android)` | ⚠️ **变体过宽**（信号精度问题，非正确性）—— 实测 `Dispatchers.Main` **均为 0**；只用 `Dispatchers.IO`（capture/transfer 各 3、ai 1）或 `Flow`/`withTransaction`（database 7 处 import）⇒ 只需 `-core`。因 `-android` 是超集且 `app` 已保留它，**APK 不受影响** |
| `feature:ai` | `testImplementation(kotlinx.coroutines.android)` | ✅ 已经是「main + test 分别声明」的正确形态（改法的参照样板） |

**`-android` 必要性矩阵**（实测 `grep -rnE "Dispatchers\.Main" <mod>/src/main | wc -l`）：

```
app              Main=11  IO/Default=17   ← 唯一真正需要 -android
core:backup      Main=0   IO/Default=0    ← 完全不需要协程（main 侧）
core:database    Main=0   IO/Default=0    ← 只需 -core（用 Flow / withTransaction）
feature:capture  Main=0   IO/Default=3    ← 只需 -core
feature:transfer Main=0   IO/Default=3    ← 只需 -core
feature:ai       Main=0   IO/Default=1    ← 只需 -core
```

> **修订记录（v2，QA 审计后）**：本文档初稿在上一版表格里把 `core:backup` / `core:database` / `feature:ai` / `feature:capture` / `feature:transfer` 一并标为「✅ 合理 —— 这些模块确实要调度器 / `Dispatchers.Main`」。**该结论只对 `app` 成立**，对其余 5 个与事实不符，已按实测数据重写。同时初稿漏掉了 `core:backup` 这个与三模块**性质完全相同**的第 4 处，已补做收窄。

值得注意：**`feature:ai` 已经是「main + test 分别声明」的正确样板**——说明项目里已存在更好的做法，只是这几个老模块没跟上。这也支持「不是认知问题，是历史遗留」的判断。

**测试侧确实需要它**（所以推荐 `testImplementation` 而非简单删除）：

```bash
grep -rn "kotlinx.coroutines" feature/stats/src/test     # → 42 处（Flow / MutableStateFlow / map / runBlocking）
grep -rn "kotlinx.coroutines" feature/classify/src/test  # → 15 处（runBlocking）
grep -rn "kotlinx.coroutines" feature/dedup/src/test     # → 98 处（runBlocking 为主）
```

## 2.5 定位与修复

### 2.5.1 定位手法

1. **按符号而非按 import 搜**：`suspend` 不算协程依赖，必须搜**真正来自库的符号**：
   ```bash
   for m in stats classify dedup; do
     echo "== $m main =="
     grep -rn "^import kotlinx\|kotlinx\.coroutines\|Flow<\|CoroutineScope\|Dispatchers\.\|launch\|withContext" \
       feature/$m/src/main
   done
   ```
   命中的只有 `suspend` 关键字时，就可以判定该模块不需要 coroutines。

2. **确认传递来源**：查被依赖模块用的是 `api` 还是 `implementation`：
   ```bash
   grep -rn "coroutines" --include="build.gradle.kts" .
   ```
   若上游是 `api`（本项目就是），下游的 `implementation` 必然冗余。

3. **交叉验证（最硬的证据）**：临时删掉该行跑 `./gradlew :feature:stats:compileKotlin`，编译通过即证明冗余。这比任何静态推断都有力，且可复算。

4. **顺带做一次全仓声明盘点**：把「声明了但源码零引用」的依赖列出来——这是可批量发现同类问题的通用手法（1.1.6 就是这么清出 7 条的）。

### 2.5.2 修复方案

**推荐做法：改为 `testImplementation`，而不是删除。**

```kotlin
// feature/stats/build.gradle.kts  （classify / dedup 同）
dependencies {
    implementation(project(":core:model"))
    // 本模块 main 源码不用协程（`suspend` 属 Kotlin stdlib）；仅测试用 runBlocking / Flow 造夹具。
    // 编译期类型仍由 core:model 的 api 传递提供，此处是显式声明"测试期直接使用"。
    testImplementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
}
```

理由：

- **比删除更诚实**：测试里确实**直接**使用了 `kotlinx.coroutines.runBlocking` / `flow.*`。Gradle 的官方口径是「你直接用的类型，你就应该声明」；依赖传递可用不等于应该依赖传递（上游一旦收紧 `api`，测试会以难懂的方式失败）。
- **比保留更准确**：把使用范围如实表达为「仅测试」，消除对架构审查的误导。
- 顺带把理由写进注释，避免下一次被当成「漏了 main 依赖」而补回去。

**次优做法**：直接删除（已验证不会编译失败）。适用于「连测试也不想显式声明、完全接受传递依赖」的团队。**不推荐**，因为它把耦合藏得更深。

**通用改进（建议顺手做）**：给 `tools/static_check.py` 加一条检查项——

> **检查项 9（新增）**：解析**版本目录形式**的依赖声明，对每个 `implementation(libs.x)` 检查该模块 `src/main` 是否有引用 `libs.x` 的符号；若无，报 warning 并要求改为 `testImplementation` 或删除。

这需要维护「别名 → 包名」的映射表（`libs.kotlinx.coroutines.core` → `kotlinx.coroutines`），但只覆盖仓库里实际用到的少数几个别名即可，成本很低。**这是本问题唯一能防止复发的手段**——因为这类问题不会被编译器、也不会被现有 8 项检查发现。

### 2.6 修复后需要覆盖的验证点

| # | 验证点 | 命令 / 期望 |
|---|---|---|
| V1 | 三个模块 main 编译通过 | `./gradlew :feature:stats:compileKotlin :feature:classify:compileDebugKotlin :feature:dedup:compileKotlin` 全绿 ← **证明冗余判断成立** |
| V2 | 三个模块测试编译并全绿 | `./gradlew :feature:stats:test :feature:classify:testDebugUnitTest :feature:dedup:test`，失败 0 |
| V3 | 全量回归 | `./gradlew test --rerun-tasks`，仍是 **1189** 条、0 失败（数量不得变化） |
| V4 | 静态检查 | `python tools/static_check.py` 通过（改后不应触发任何既有规则） |
| V5 | Release 产物无变化 | `./gradlew :app:assembleRelease --rerun-tasks` 后比对 `classes.dex` / `resources.arsc` 的 sha256 与本次发布基线（`1265fde6…` / `ea16e1e3…`）**逐字节相同** ← 这是「零影响」论断的硬证据 |
| V6 | 依赖树确认来源 | `./gradlew :feature:stats:dependencies --configuration compileClasspath`，确认 coroutines 仍出现（来自 `core:model` 的 `api`），而不是消失 |
| V7 | 无损性：测试类路径 | `./gradlew :feature:stats:dependencies --configuration testCompileClasspath`，确认 coroutines 存在（来自 testImplementation 或 core:model） |

> **V5 是关键**：本问题对外宣称「零影响」，那就必须能被证伪。`--rerun-tasks` 全量重建后比对 dex/arsc 哈希，是本项目已验证可行的手法（v1.1.7 发布前用它证明了注释改动惰性）。

---

# 附：两个问题都不会被现有护栏捕获

| 护栏 | 问题一方向丢失 | 问题二冗余声明 |
|---|---|---|
| Kotlin 编译器 | ❌ 字段有默认值 `null`，不报错 | ❌ 未使用的依赖不报错 |
| `static_check.py` 检查 5（跨模块 import） | ❌ 不涉及 | ❌ 只解析 `project(":x")`，版本目录形式不在视野内 |
| `static_check.py` 检查 8（API 泄漏） | ❌ 不涉及 | ❌ 只解析 `project(":x")`；且只查 core 模块的**公开签名**，不查「声明了但没用」 |
| 全量单测 | ❌ **当前无任何用例覆盖「命中规则但金额缺失」的收入路径** | ❌ 编译通过即绿，行为不变 |
| 真机 | ⚠️ 能发现（记反方向），但需用户主动核对 | ❌ 无任何可观测差异 |

**这解释了为什么两个问题都能长期存活**：它们都处在「所有自动护栏的缝隙」里——问题一缺用例，问题二缺检查项。

**顺带一个值得记入的通用教训**：`ParseResult.direction` 这种「简单情形冗余、困难情形丢失」的字段，是设计上的强警告信号。识别手法很便宜：**对每个数据类字段问「谁读了它」；只写不读的字段，往往意味着信号在某个边界上断了。**

---

## 独立审计补充（QA，只读）

审计结论：**三处改动本身 100% 正确**（无夹带、`--rerun-tasks` 实测编译通过、测试侧确需 `testImplementation`、来源确为 `core:model` 的 `api`），**但「恰好三个模块」被证伪**，且本文档初稿有一段事实错误（见 §2.4 修订记录）。

审计另外发现两件事：

1. **`static_check.py` 检查项 8（API 泄漏）的覆盖也偏窄**：它的 `DEP_KIND_RE` 强制要求 `project(...)` 形式，因此**只检查模块间的 API 泄漏，外部库完全不在视野内**。也就是说：若 `core:model` 把 coroutines 误写成 `implementation`（而非 `api`），第 8 项**也抓不到** —— 这是一个比「检查项 9」更值得补的缺口。
2. **检查项 9 的可行性与误报源**已评估：可低成本实现（解析 `api/implementation(libs.<alias>)`，用「别名→包名前缀」表映射后 grep `src/main` 的 import），但必须配 **显式例外白名单**并在 `strip_comments` 后比对。误报源有三类：运行时/服务提供者型依赖（`kotlinx-coroutines-android` 靠 `META-INF/services` 注册 Main 调度器，天然零 import）、注解处理器/编译器插件/BOM、`-ktx` 库（用量落在 base 包）。**「`-android` 变体过宽」不建议自动化**（包名相同，需「是否用到 `Dispatchers.Main`」的语义启发，误报高）。

## 建议的推进顺序

1. **先做问题二**（零风险、可被 V5 完整证伪）—— 已完成，含审计补出的第 4 处 `core:backup`。
2. **再做问题一**，且**必须把 §1.5.3 的 AI 信号一起改**，作为同一个提交，否则会把隐私口径改坏。
3. 顺手把 §2.5.2 的 `static_check` 检查项 9 加上，并**同时把检查项 8 扩展到外部库**——这是这些问题里唯一能防复发的措施。

## 待决策项（性质不同，未自行扩大范围）

以下两类**不属于问题二的性质**（问题二是「main 零引用」，这些是「声明范围/变体过宽，但确实在用」），已按纪律留待确认，未擅自改动：

| 类别 | 模块 | 现状 | 收窄后 | 风险 |
|---|---|---|---|---|
| **变体过宽** | `core:database` / `feature:capture` / `feature:transfer` / `feature:ai` | `implementation(kotlinx.coroutines.android)` | `-core`（实测 `Dispatchers.Main` 均为 0） | 低（`-android` 是超集、`app` 仍保留它，APK 不受影响）。注意 `feature:ai` 是「main+test 分开声明」的结构样板，只应动 artifact、不动结构 |
| **边界** | `app` / `feature:capture` | `implementation(androidx.core.ktx)` | — | 低优先（仅用到 base `core` 的面，ktx 扩展面未用；1.1.6 已清过 4 处更明显的） |

两项都完成后，v1.1.8 可作为一次「无用户可见行为的内部修正」版本发布。
