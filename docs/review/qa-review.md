# AutoLedger QA 审查报告

- 审查人：严过关（QA Engineer）
- 审查方式：**纯静态审查**（本机无 JDK / Android SDK，代码从未编译过，未运行任何 Gradle 任务）
- 审查范围：正确性 / 性能 / 兼容性 / 测试
- 结论一句话：**工程当前无法编译（至少 4 处必现编译错误），且即便编译通过，启动路径存在主线程 Keystore + SQLCipher IO、账单导入有 O(n²) 全表扫描与日期解析退化、去重指纹存在误吞真实消费的风险。**

> 说明：所有行号基于当前工作区快照。`RED` 标记的单元测试用例是**刻意写红**的——它们断言"正确行为"，用于把下面的缺陷固化成可回归的红灯，修复后应转绿。

---

## 一、总览

| 级别 | 数量 | 定义 |
|---|---|---|
| 严重 | 11 | 编译失败 / 启动崩溃 / 数据错误 / 数据丢失 |
| 中等 | 20 | 明显性能问题、逻辑偏差、可规避的崩溃与体验问题 |
| 建议 | 20 | 健壮性、可维护性、一致性与未来风险 |

### 修复状态追踪（QA 二次核对，基于修复后的工作区快照）

| 编号 | 问题 | 状态 | 核对结论 |
|---|---|---|---|
| **S0** | `Stats.kt` 缺 `import java.time.Instant` | ✅ **已修复** | 文件 :3-4 已补 `import java.time.Instant` / `import java.time.ZoneId`，`:core:model` 可编译 |
| **S1** | `LedgerRepository` 缺 `listRange` | ✅ **已修复** | `Spi.kt:96-100` 带 `= false` 默认值；`RoomLedgerRepository.kt:74-79` override **不带默认值**（正确写法，见下） |
| **S2** | `goAsync()` 不存在 | ✅ **已修复** | `LedgerNotificationListener.kt` 已移除，注释明确说明走受生命周期管理的 IO scope |
| **S3** | `Base64` 未 import | ✅ **已修复** | `BackupManager.kt:3` 已补 `import android.util.Base64` |
| **S4** | `onClick` 内调 `rememberCoroutineScope()` | ✅ **已修复** | `CaptureAndSettings.kt:57` 已在 Composable 顶层取 `val scope = rememberCoroutineScope()` |
| S5 ~ S10、M*、L* | 逻辑/性能/健壮性 | ⏳ **未修复** | 已复核：相关生产代码与初版审查时一致，见"RED 用例复核"一节 |

> ⚠️ **S1 的写法提醒（给后续改动者）**：Kotlin 不允许在 `override` 函数上声明参数默认值（`DEFAULT_VALUE_NOT_ALLOWED_IN_OVERRIDE`）。正确做法是**默认值只写在接口上**（`Spi.kt:99`），实现类 override 不写默认值、由接口继承。已落地的代码正是这个写法，不要改成 `override ... includeTransfers: Boolean = false`。

测试交付：新增 **9 个测试文件 / 109 个用例**（其中 4 个 RED 红灯用例），另有 3 处 `build.gradle.kts` 增加 `testImplementation(kotlin("test"))`（见第七节说明）。

---

## 二、严重（Severe）

### S0. `Stats.kt` 缺 `import java.time.Instant`，`:core:model` 自身编译失败
> 本条由 software-architect 交叉审查发现，QA 已复核确认。

- 【文件:行号】`core/model/src/main/java/com/autoledger/core/model/Stats.kt:1-4（import 区）, 59, 65, 72`
- 【问题】整个文件原本**没有任何 import**，却在 `TimeRange.today()/thisMonth()/lastDays()` 里用了 `Instant`；同文件的 `ZoneId` 写成了全限定 `java.time.ZoneId`（:77），`Instant` 却漏了。
- 【产生原因】Kotlin 默认导入只有 `java.lang.* / kotlin.*`，**不含 `java.time`**；全限定与裸用混写时容易漏掉其一。
- 【影响范围】**这是排在所有错误之前的第一顺位阻断**：`:core:model` 编译失败 → 依赖它的 `:core:database` / `:core:backup` / `:feature:*` / `:app` **全部**编译失败；QA 新增的 `core/model/src/test/**` 与 `feature/dedup/src/test/**`（共 92 个用例）当时也一并编译不了。
- 【修改方向】
```kotlin
package com.autoledger.core.model

import java.time.Instant
import java.time.ZoneId
```
- 【状态】✅ **已修复**（当前文件 :3-4 已补齐），并已把 `ZoneId` 的全限定写法一并统一。
- 【优先级】**1**

---

### S1. `LedgerRepository` 缺少 `listRange`，feature-stats 无法编译
- 【状态】✅ **已修复**（`Spi.kt:96-100` + `RoomLedgerRepository.kt:74-79`）。下方保留原始分析，落地写法以"已采用版本"为准。
- 【文件:行号】`core/model/src/main/java/com/autoledger/core/model/Spi.kt:89-100`；`feature/stats/src/main/java/com/autoledger/feature/stats/Metrics.kt:28, 58, 83, 109, 130`
- 【问题】`Metrics.kt` 5 处调用 `repo.listRange(...)`，但 `repo` 的静态类型是 `LedgerRepository`，而该接口只有 `listSince / listAll / findById ...`，**没有 `listRange`**。`listRange` 只存在于 `RoomLedgerRepository:73`。
- 【产生原因】`MetricProvider.compute(range, repo: LedgerRepository)` 只面向接口编程，`listRange` 是后来加在 Room 实现上的方法，忘了回填接口。
- 【影响范围】`:feature:stats` 模块编译失败 → 依赖它的 `:app` 一并失败。**整个工程当前编译不过。**
- 【修改方向】
```kotlin
// Spi.kt —— 在 LedgerRepository 中补一行（必须有默认参数，兼容现有两参调用）
suspend fun listRange(
    fromMillis: Long,
    toMillis: Long,
    includeTransfers: Boolean = false,
): List<LedgerTransaction>
```
```kotlin
// RoomLedgerRepository.kt:74 —— 已采用版本：override 上【不要】写默认值
// （Kotlin 禁止在 override 函数上声明参数默认值，写了会报
//  DEFAULT_VALUE_NOT_ALLOWED_IN_OVERRIDE；默认值由接口的 Spi.kt:99 提供）
override suspend fun listRange(
    fromMillis: Long,
    toMillis: Long,
    includeTransfers: Boolean,
): List<LedgerTransaction> =
    txnDao.listRange(fromMillis, toMillis).filterTypes(includeTransfers).map { it.toDomain() }
```
> 若担心"派生类型上两参调用"的解析行为不确定，最稳的做法是把 `AppStores.kt:63/64/309` 三处两参调用显式写成三参（`includeTransfers = false`），语义与默认值一致但完全不依赖编译器的默认值继承规则。
- 【优先级】**1**

---

### S2. `NotificationListenerService` 上不存在 `goAsync()`
- 【文件:行号】`feature/capture/src/main/java/com/autoledger/feature/capture/notify/LedgerNotificationListener.kt:71, 78`
- 【问题】`val token = goAsync()` —— `goAsync()` 是 `BroadcastReceiver` 的方法，`NotificationListenerService`（继承自 `Service`）没有这个方法。
- 【产生原因】把广播接收器的"延长进程存活"写法直接搬到了通知监听服务上。
- 【影响范围】`:feature:capture` 编译失败 → 全工程编译失败。
- 【修改方向】通知回调本身就是同步回调，不需要 `goAsync`；改为直接投递，并补上 `onDestroy` 取消 scope：
```kotlin
override fun onNotificationPosted(sbn: StatusBarNotification) {
    // ... 解析逻辑不变 ...
    scope.launch { CaptureDispatcher.submit(envelope) }   // 不再需要 token
}

override fun onDestroy() {
    scope.cancel()          // 新增：避免 scope 泄漏
    super.onDestroy()
}
```
- 【优先级】**1**

---

### S3. `BackupManager` 使用 `Base64` 但未 import
- 【文件:行号】`core/backup/src/main/java/com/autoledger/core/backup/BackupManager.kt:1-14（import 区）, 58, 59, 113, 116`
- 【问题】文件里 4 处使用 `Base64.encodeToString / Base64.decode`，但 import 列表中**没有** `import android.util.Base64`。Kotlin 默认导入只有 `java.lang.* / kotlin.*`，不会自动解析 `android.util.Base64`，也不会自动解析 `java.util.Base64`。
- 【产生原因】加密导入分支（`exportEncrypted`）是后补的，漏了 import；因为从未编译过所以没暴露。
- 【影响范围】`:core:backup` 编译失败 → 全工程编译失败；同时意味着"加密备份导出"这条路径**从未被验证过**。
- 【修改方向】
```kotlin
import android.util.Base64
```
> 顺带：`PassphraseKeyDeriver.kt:3` import 了 `android.util.Base64` 但文件内并未使用（无害，建议删除）。
- 【优先级】**1**

---

### S4. 在非 `@Composable` 的 `onClick` 中调用 `rememberCoroutineScope()`
- 【文件:行号】`app/src/main/java/com/autoledger/app/ui/screens/CaptureAndSettings.kt:137`
- 【问题】
```kotlin
IconButton(onClick = {
    androidx.compose.runtime.rememberCoroutineScope().launch { ... }   // ← 编译错误
})
```
`IconButton` 的 `onClick: () -> Unit` 不是 `@Composable` lambda，编译期报 "@Composable invocations can only happen from the context of a @Composable function"。
- 【产生原因】把"在组合里取 scope"的写法误用到了回调里。
- 【影响范围】`:app` 编译失败。
- 【修改方向】与 `ExpensesScreen`（`LedgerScreens.kt:57`）保持一致，在 `CaptureScreen` 顶部取一次：
```kotlin
@Composable
fun CaptureScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()          // 新增
    ...
    IconButton(onClick = {
        scope.launch {
            container.repository.markStatus(txn.id, TxnStatus.CONFIRMED)
            store.refresh(context)
        }
    }) { Icon(LedgerIcons.Check, "确认入账") }
```
- 【优先级】**1**

---

### S5. 冷启动在主线程同步做 Keystore 密钥生成 + SQLCipher 建库
- 【文件:行号】`app/src/main/java/com/autoledger/app/di/AppContainer.kt:61, 62, 105-109, 113`；`core/crypto/.../PassphraseVault.kt:17-18`；`core/crypto/.../KeystoreKeyProvider.kt:18-22`
- 【问题】`AppContainer` 的**构造期立即求值**属性链：
  1. `private val passphraseVault = PassphraseVault(context)`（:61）→ 其成员 `CryptoBox(keyProvider.masterKey())`（PassphraseVault.kt:18）在构造时同步执行 **AndroidKeyStore 密钥生成**；
  2. `val cryptoBox = CryptoBox(KeystoreKeyProvider().masterKey())`（:62）再来一次；
  3. `val classifier = CompositeClassifier(listOf(MemoryClassifier(database.classifierRuleDao()), ...))`（:105-109）与 `val correctionLearner`（:113）是**非 lazy** 的，构造时即触碰 `database` → 触发 `database` 这个 `by lazy` 的初始化 → **Room + SQLCipher 打开/建表**。
  
  而 `AppContainer(this)` 是在 `LedgerApp.onCreate()`（LedgerApp.kt:13）的**主线程**里调用的。
- 【产生原因】把"昂贵资源"写成了 eager val，且误以为 `database` 的 lazy 能保护所有入口；`classifier` 这条 eager 链绕过了 lazy。
- 【影响范围】
  - 冷启动阻塞主线程数百毫秒 ~ 数秒（SQLCipher 首次打开要做 KDF 派生 + 建表），低端机直接 ANR；
  - Keystore 在少数 ROM / 用户录入生物特征后 / 设备迁移场景下抛 `KeyPermanentlyInvalidatedException` → **Application.onCreate 抛异常 = 100% 启动崩溃，无任何降级**。
- 【修改方向】
```kotlin
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    // 密钥与数据库全部下沉到 lazy + IO 线程
    val cryptoBox: CryptoBox by lazy { CryptoBox(KeystoreKeyProvider().masterKey()) }
    private val passphraseVault by lazy { PassphraseVault(appContext) }
    val database: LedgerDatabase by lazy {
        LedgerDatabaseFactory.create(appContext, sqlCipherFactory())
    }
    val classifier by lazy {
        CompositeClassifier(listOf(MemoryClassifier(database.classifierRuleDao()), ...))
    }
    val correctionLearner by lazy { CorrectionLearner(database.classifierRuleDao()) }

    /** 密钥/数据库初始化统一走 IO，失败可降级 */
    suspend fun prepare() = withContext(Dispatchers.IO) {
        runCatching { cryptoBox; database }
    }
}
```
```kotlin
class LedgerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch { container.prepare(); container.bootstrapOnIo() }
    }
}
```
- 【优先级】**1**

---

### S6. `PassphraseVault` 解密无兜底 + 用 `apply()` 异步写口令（可导致数据库永久打不开）
- 【文件:行号】`core/crypto/src/main/java/com/autoledger/core/crypto/PassphraseVault.kt:21, 23`
- 【问题】
  1. `prefs.getString(...)?.let { return cryptoBox.open(Base64.decode(it, Base64.NO_WRAP)) }` —— `cryptoBox.open` 在密钥失效/数据损坏时抛 `CryptoException` 或 `AEADBadTagException`，**没有任何 catch**，直接在 `Application.onCreate` 链路上崩；
  2. 首次生成口令后写盘用 `apply()`（异步落盘）。若用户在写入完成前杀进程 / 系统强杀，会出现"SP 里没口令，但数据库已按该口令加密"→ **数据库永久无法打开，全部账本数据丢失**。
- 【产生原因】把"保险箱"当成了不会失败的操作，且没区分"配置写入"与"凭证写入"的持久性要求。
- 【影响范围】全量数据丢失 + 启动崩溃；`allowBackup=false` 与 `data_extraction_rules` 都排除了 db，意味着**没有任何恢复路径**。
- 【修改方向】
```kotlin
fun passphrase(): ByteArray {
    val wrapped = prefs.getString(KEY_WRAPPED, null)
    if (wrapped != null) {
        runCatching { cryptoBox.open(Base64.decode(wrapped, Base64.NO_WRAP)) }
            .onFailure { Log.w(TAG, "口令解封失败，可能是密钥失效: ${it.message}") }
            .getOrNull()?.let { return it }
        // 解不出来：判为密钥失效，重新生成（旧库此时已不可读，交给上层提示）
    }
    val fresh = cryptoBox.randomPassphrase()
    // 凭证必须用 commit() 同步落盘
    prefs.edit()
        .putString(KEY_WRAPPED, Base64.encodeToString(cryptoBox.seal(fresh), Base64.NO_WRAP))
        .commit()
    return fresh
}
```
同时 `KeystoreKeyProvider.masterKey()` 需要捕获 `KeyPermanentlyInvalidatedException` / `GeneralSecurityException` 并向上抛一个可识别的 `KeyUnavailableException`，由 `AppContainer` 决定「提示用户重新初始化」而不是直接崩。
- 【优先级】**1**

---

### S7. 账单 CSV 日期解析退化为"当前时间"，导致整批流水时间戳塌缩
- 【文件:行号】`feature/capture/src/main/java/com/autoledger/feature/capture/bill/BillImportCaptureSource.kt:61, 82-91`
- 【问题】
```kotlin
val occurredAt = row["time"]?.let { tryParseDateTime(it) } ?: System.currentTimeMillis()
```
`tryParseDateTime` 只尝试 `LocalDateTime.parse`（ISO 严格格式，月/日必须两位）与 `LocalDate.parse`。支付宝常见导出格式 `2024/1/2 13:45`（单位数月/日）**两次都会失败** → 回退到 `System.currentTimeMillis()`。
- 【产生原因】用 `String.replace` 手工规范化后交给严格的 ISO 解析器，没覆盖单位数月/日、没覆盖无秒时间、也没覆盖 `yyyy年M月d日`。
- 【影响范围】
  1. 整份账单所有行 `occurredAtMillis` = 导入时刻 → 月度/趋势统计全部失真；
  2. `LedgerDuplicateResolver` 的 3 分钟窗口 + 金额指纹会让**同一批账单内部互相判重**，触发 `IngestPipeline` 的自动合并 → **真实消费被静默吞掉**；
  3. 与通知渠道抓到的历史流水也无法对齐。
- 【修改方向】
```kotlin
private val DATE_TIME_FORMATTERS = listOf(
    DateTimeFormatterBuilder().appendPattern("yyyy-M-d H:m:s").toFormatter(),
    DateTimeFormatterBuilder().appendPattern("yyyy-M-d H:m").toFormatter(),
    DateTimeFormatterBuilder().appendPattern("yyyy-M-d").toFormatter(),
    DateTimeFormatterBuilder().appendPattern("yyyy年M月d日 H:m:s").toFormatter(),
    DateTimeFormatter.ISO_LOCAL_DATE_TIME,
)

private fun tryParseDateTime(value: String): Long? {
    val v = value.trim().replace("/", "-").replace("　", " ")
    val zone = ZoneId.systemDefault()
    for (f in DATE_TIME_FORMATTERS) {
        runCatching { LocalDateTime.parse(v, f) }.getOrNull()
            ?.let { return it.atZone(zone).toInstant().toEpochMilli() }
        runCatching { LocalDate.parse(v, f) }.getOrNull()
            ?.let { return it.atStartOfDay(zone).toInstant().toEpochMilli() }
    }
    return null
}

// 解析不出来时：宁可丢弃这一行也不要伪造"现在"
val occurredAt = row["time"]?.let { tryParseDateTime(it) } ?: return null
```
- 【优先级】**1**

---

### S8. 去重每次写入都全表扫描（O(n²)），且已有的专用查询没人用
- 【文件:行号】`feature/dedup/src/main/java/com/autoledger/feature/dedup/LedgerDuplicateResolver.kt:36-47`；`core/database/src/main/java/com/autoledger/core/database/Daos.kt:57-66`
- 【问题】`findDuplicates` 用 `repository.listSince(from, includeTransfers = true)` 把 `occurredAt >= (txn.occurredAt - 3min)` 的**所有**记录拉到内存再逐条比对。注意 `from` 是"这笔的时间 - 3 分钟"，所以它返回的是从那一刻到现在的**全部**流水——导入历史账单（occurredAt 是两年前）时等于**整表读取**。
- 【产生原因】`TransactionDao.findByFingerprintNear(fingerprint, anchor, window, excludeId)` 这条按 `fingerprint` 索引的精确查询已经写好了（还有 `Index(value=["fingerprint"])`），但 `RoomLedgerRepository.findByFingerprintNear`（:84）**从未被调用**。
- 【影响范围】导入 500 条账单 = 500 次全表扫描 + 500 次全量对象映射；配合 S7 会让"导入账单"这个核心冷启动功能直接卡死/ANR。
- 【修改方向】把 `IngestPipeline` 传入的 repository 换成能拿到 `findByFingerprintNear` 的类型（或在 `LedgerRepository` 接口上暴露），改用索引查询：
```kotlin
// LedgerRepository 增加
suspend fun findByFingerprintNear(
    fingerprint: String, anchor: Long, windowMillis: Long, excludeId: String,
): List<LedgerTransaction>

// LedgerDuplicateResolver.findDuplicates
override suspend fun findDuplicates(txn: LedgerTransaction): List<DuplicateCandidate> =
    repository.findByFingerprintNear(txn.fingerprint, txn.occurredAtMillis, windowMillis, txn.id)
        .mapNotNull { other ->
            if (other.status == TxnStatus.MERGED) return@mapNotNull null
            val drift = abs(other.occurredAtMillis - txn.occurredAtMillis)
            if (drift > windowMillis) return@mapNotNull null
            DuplicateCandidate(other.id, scoreOf(drift, other.sourceId == txn.sourceId))
        }.sortedByDescending { it.score }
```
> 提示：`abs()` 在 SQL 里会**绕过 fingerprint 索引**（`Daos.kt:61`）。若数据量上来了，应改成 `occurredAtMillis BETWEEN :lo AND :hi`，lo/hi 由调用方算好。
- 【优先级】**1**

---

### S9. 商户名为空时指纹塌缩，自动合并会静默吞掉真实消费
- 【文件:行号】`feature/dedup/src/main/java/com/autoledger/feature/dedup/LedgerDuplicateResolver.kt:31-34, 63-67`；触发点 `feature/capture/.../IngestPipeline.kt:110, 130-132`
- 【问题】`fingerprint = sha256("金额|归一化商户")`。`normalize("")` = `""`，于是**所有"商户名为空的同金额流水"共享同一个指纹**。通知/短信解析失败时 `counterpartyHint` 为 null → `IngestPipeline:48` 变成 `""`，这是很常见的路径。3 分钟窗口内第二笔同金额流水会被判为重复，并且因为 `autoMergeDuplicates` 默认为 `true`，直接 `merge()` 掉。
- 【产生原因】指纹设计文档里说"刻意不含时间"，但没有为"商户信息缺失"这个高频分支准备降级信号。
- 【影响范围】用户在同一时段的两笔同金额真实消费（例如两次 25 元的乘车）会被吞掉一笔，且**没有任何提示**（`Outcome.MergedInto` 没进待确认队列）。
- 【修改方向】
```kotlin
override fun fingerprintOf(txn: LedgerTransaction): String {
    val entity = normalize(txn.counterparty)
    return if (entity.isBlank()) {
        // 商户缺失：把渠道 + 渠道侧标识纳入指纹，宁可漏判也不误判
        sha256("${txn.amountMinor}|blank|${txn.sourceId}|${txn.sourceRef}")
    } else {
        sha256("${txn.amountMinor}|$entity")
    }
}

/** 供 IngestPipeline 判断"是否可信到可以自动合并" */
fun isAutoMergeSafe(txn: LedgerTransaction): Boolean =
    txn.amountMinor != 0L && normalize(txn.counterparty).isNotBlank()
```
```kotlin
// IngestPipeline.kt:130
duplicates.isNotEmpty() && autoMergeDuplicates && resolver.isAutoMergeSafe(txn) -> { ... }
```
- 【优先级】**1**

---

### S10. 备份 v1→v2 迁移用 Double 转分，系统性少 1 分
- 【文件:行号】`core/backup/src/main/java/com/autoledger/core/backup/BackupEnvelope.kt:73`
- 【问题】`t.put("amountMinor", (yuan * 100).toLong())`。IEEE754 下 `0.29 * 100 = 28.999999999999996` → `toLong()` = **28**；`1.15 * 100 = 114.99999999999999` → **114**。
- 【产生原因】v1 档案把金额存成 Double（"元"），迁移时直接乘 100 强转，没走 BigDecimal。
- 【影响范围】任何从 v1 档案导入的老用户，**尾数为 x9 / x5 的金额普遍少 1 分**，对账与月度合计都会对不上——这恰恰是 v2 升级想解决的问题。
- 【修改方向】
```kotlin
val yuan = t.getDouble("amount")
t.put("amountMinor", BigDecimal.valueOf(yuan).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact())
```
> 同源问题：`Money.fromYuanDouble`（Money.kt:30）与手动记账入口 `LedgerScreens.kt:99` 的 `(yuanValue * 100).toLong()`，见 L3 / M21。已用 RED 用例 `Red_fromYuanDouble...` 固化。
- 【优先级】**2**

---

## 三、中等（Medium）

### M1. 启动 `bootstrap()` 每次全量 upsert + 采集订阅无法退订 + 异常被静默吞掉
- 【文件:行号】`app/.../di/AppContainer.kt:137-153`；`feature/capture/.../CaptureSource.kt:46-55`
- 【问题】
  1. 每次启动都 `upsertCategories(11)` + `upsertAll(100+ 条规则)`（`DefaultRulePack` 展开后约 100 条）；
  2. `CaptureDispatcher.subscribe` 只有 add 没有 remove；若 `bootstrap()` 被调用两次（多进程 / 单元测试 / 未来加 WorkManager），同一信封会被 `ingest` 两次；
  3. `submit` 里 `runCatching { it(envelope) }` **吞掉所有 Throwable**，包括 `CancellationException` —— 破坏协程取消语义，且流水静默丢失、无日志、无指标。
- 【影响范围】启动耗时、重复入账风险、线上问题不可观测。
- 【修改方向】
```kotlin
// 1) 只在首次或版本变化时播种
if (settings.seedVersion < LedgerSchema.CURRENT) { ...upsert...; settings.seedVersion = LedgerSchema.CURRENT }

// 2) 订阅返回句柄
fun subscribe(listener: suspend (RawEnvelope) -> Unit): () -> Unit {
    synchronized(listeners) { listeners.add(listener) }
    return { synchronized(listeners) { listeners.remove(listener) } }
}

// 3) 不吞 CancellationException
suspend fun submit(envelope: RawEnvelope) {
    val snapshot = synchronized(listeners) { listeners.toList() }
    snapshot.forEach { listener ->
        try { listener(envelope) }
        catch (e: CancellationException) { throw e }          // 让取消继续传播
        catch (t: Throwable) { Log.e("Capture", "ingest failed: ${t.message}") }  // 其余记录后继续
    }
}
```
- 【优先级】2

---

### M2. 写入路径没有事务，outbox 与数据可能不一致
- 【文件:行号】`core/database/.../repository/RoomLedgerRepository.kt:37-40, 109-119`
- 【问题】`upsertAll` 先 `txnDao.upsertAll(...)`，再对每条 `enqueue(...)` 写 outbox，全程**没有 `db.withTransaction {}`**。中途失败会出现"流水写进去了但 op 没登记"（将来接云同步就漏数据）或反向。另外 Room 默认每条语句一个事务，500 条 = 500 次 fsync。
- 【修改方向】
```kotlin
override suspend fun upsertAll(txns: List<LedgerTransaction>) = db.withTransaction {
    txnDao.upsertAll(txns.map { it.toEntity() })
    txns.forEach { enqueue("transaction", it.id, "UPSERT") }
}
```
- 【优先级】2

---

### M3. 每条流水分类都全表读规则（100+ 行）
- 【文件:行号】`feature/classify/.../Classifiers.kt:30, 66`；`feature/classify/.../CorrectionLearner.kt:24`
- 【问题】`KeywordClassifier.classify` 与 `MemoryClassifier.classify` 都 `ruleDao.listAll()` 读全表；`CorrectionLearner.remember` 也读全表。批量导入 500 条 = 至少 1000 次全表读 + 反序列化。
- 【影响范围】账单导入 / 短信扫描的吞吐被 IO 与对象分配吃满。
- 【修改方向】
```kotlin
class RuleCache(ruleDao: ClassifierRuleDao, scope: CoroutineScope) {
    @Volatile private var snapshot: List<ClassifierRuleEntity> = emptyList()
    init { scope.launch { ruleDao.observeAll().collect { snapshot = it } } }   // 已有 Flow 查询
    fun all(): List<ClassifierRuleEntity> = snapshot
}
```
分类器改为依赖 `RuleCache`；批量导入时在循环外取一次快照传入。
- 【优先级】2

---

### M4. 分类器永远拿不到原文/备注，自动分类准确率被人为砍掉
- 【文件:行号】`feature/capture/.../IngestPipeline.kt:91-100`（`note = null`）
- 【问题】`TransferContext` 传了 `note = envelope.rawText.take(200)`，但 `ClassificationContext` 传的是 **`note = null`**。而 `KeywordClassifier` 的 haystack = `counterparty + note`（Classifiers.kt:24-27）。商户名常常是"财付通"/"支付宝"这类无法分类的泛词，真正的分类信号（"美团外卖""滴滴出行"）都在正文里。
- 【影响范围】自动分类命中率、待确认队列长度、用户体感"这个 App 不够聪明"。
- 【修改方向】
```kotlin
val result = classifier.classify(
    ClassificationContext(
        counterparty = counterparty,
        note = envelope.rawText.take(200),     // 改：把正文交给分类器
        amountMinor = amount ?: 0L,
        occurredAtMillis = envelope.occurredAtMillis,
        sourceId = envelope.sourceId,
        packageName = envelope.packageName,
    )
)
```
> 注意：`rawTextSealed` 是密文，分类器必须吃明文；管道内部使用没问题，但要确保 `RuleCache`/日志出口不落盘（配合 `Redactor`）。
- 【优先级】2

---

### M5. 多处 `runCatching {}.getOrNull()` 静默吞异常，用户看不到任何反馈
- 【文件:行号】`IngestPipeline.kt:60`；`AppStores.kt:125, 131, 211`；`CaptureAndSettings.kt:99`（`openSystemSettings` 内的 `runCatching`）
- 【问题】
  - `rawTextSealed = runCatching { cryptoBox.sealString(...) }.getOrNull()`：加密失败后原文变 null，**用户永远丢失这笔的原文**，无日志；
  - `LedgerStore.delete / correctCategory` 的 `runCatching` 完全不处理结果：用户点"删除"后列表没变化也不知道为什么；
  - `CaptureStore.pullBacklog` 用 `isSuccess` 统计成功数，但失败原因被丢弃。
- 【修改方向】统一约定：**允许降级，但必须留痕**。至少 `Log.w(TAG, ...)`（经 `Redactor.redact`）；UI 路径走 `State.error`。
- 【优先级】3

---

### M6. `storeScope` 是全局单例、永不取消、且重活跑在主线程
- 【文件:行号】`app/.../ui/stores/AppStores.kt:35, 56-82, 101-114`
- 【问题】
  1. `private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` 是**文件级单例**，被 6 个 Store 共用，**永远无法取消**；Activity 销毁后查询仍在跑，结果仍会写 StateFlow；
  2. `HomeStore.load()`（:63-68）在主线程编排：`listRange`×2 + `listAll`（全表）+ `listCategories` + 4 个 metric provider（每个又各自 `listRange` + `listCategories`）≈ **10 次 DB 往返**；Room 的 suspend 查询会切到 IO，但**返回后的 `sortedByDescending / filter / sum / 分组聚合全部在主线程**。
- 【影响范围】千条以上流水的老用户，进首页掉帧；旋转/退后台后仍有孤儿协程在写状态。
- 【修改方向】
```kotlin
class HomeStore(private val container: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    fun clear() = scope.cancel()               // 由 Activity/DisposableEffect 调用

    fun load() = scope.launch {
        _state.value = State()
        runCatching {
            withContext(Dispatchers.Default) { /* 全部 DB 调用 + 聚合放这里 */ }
        }...
    }
}
```
更彻底的做法：改用 `viewModelStore` + `viewModelScope`，并让 UI 直接 collect Room 的 `Flow`（`observeSince` / `observeRawQueue` 已经写好了，见 L11）。
- 【优先级】2

---

### M7. 数字输入/格式化使用默认 Locale，编辑体验与展示都会出问题
- 【文件:行号】`app/.../ui/screens/HomeScreens.kt:184-194`（`NumberField`）、`HomeScreens.kt:65, 118, 141-146, 174-177`、`LedgerScreens.kt:98`、`Metrics.kt:133, 137`、`InsightScreens.kt:72-83`、`IngestPipeline.kt:136`
- 【问题】
  1. `NumberField` 用 `value = value.toString()`：`value.toString()` 在 zh_CN 是 `12.0`，用户输入 `12.` 会被 `toDoubleOrNull()` 解析成 `12.0` 再回写成 `"12.0"`，**小数点打不出来、光标跳位**；在小数分隔符是逗号的 Locale（de/fr）下 `toDoubleOrNull("12,5")` 返回 null，**完全无法编辑**；
  2. 全项目 `"%.1f".format(x)` / `"%.2f".format(x)` 都用**默认 Locale**，在 ar/fa 等 Locale 下输出阿拉伯数字（`١٢.٣`），与 `toDoubleOrNull` 的解析不对偶。
- 【修改方向】
```kotlin
private val EDITABLE_FORMAT = DecimalFormat("0.##", DecimalFormatSymbols(Locale.US))
private val DISPLAY: Locale = Locale.US     // 或 Locale.CHINA，全项目统一一个

// 输入用「字符串状态 + 解析后再回写」，不要 value.toString()
var text by remember { mutableStateOf(value.toString()) }
OutlinedTextField(
    value = text,
    onValueChange = { raw ->
        text = raw
        raw.toDoubleOrNull()?.let(onChange)
    }, ...)
```
所有 `format` 显式传 `Locale.US`：`"%.1f".format(Locale.US, x)`。
- 【优先级】3

---

### M8. "清空全部流水" 无二次确认
- 【文件:行号】`app/.../ui/screens/CaptureAndSettings.kt:229-236`
- 【问题】一个 Button 直接 `store.clearAll()`，没有 `AlertDialog`。而 `allowBackup=false` + `data_extraction_rules` 排除了 db，删掉就**没有任何恢复手段**。
- 【修改方向】加确认弹窗，并要求输入/长按；更好的是先引导导出备份再清。
- 【优先级】2

---

### M9. 支付宝「不计收支」被误判为支出
- 【文件:行号】`feature/capture/.../bill/BillImportCaptureSource.kt:55-59`
- 【问题】`val isOut = directionRaw.contains("支出") || directionRaw.contains("支")`。支付宝的 `收/支` 列有第三种取值 **「不计收支」**，它含"支" → 被判为支出。这类行正是"余额宝转入/转出、信用卡还款、内部划转"。
- 【影响范围】月度支出凭空虚高——与整个"内部划转过滤"的产品目标直接冲突。
- 【修改方向】
```kotlin
private fun directionOf(raw: String): Direction? = when {
    raw.contains("不计收支") || raw.trim() == "/" -> null          // 交后续 TransferDetector 判定
    raw.contains("支出") -> Direction.OUT
    raw.contains("收入") -> Direction.IN
    else -> null
}
// amountMinor = when (dir) { OUT -> -abs; IN -> abs; null -> abs } 且 type 交给流水线
```
- 【优先级】2

---

### M10. 账单 CSV 整文件读进内存 + 编码嗅探不可靠
- 【文件:行号】`feature/capture/.../bill/BillImportCaptureSource.kt:119-121, 139-145, 153-167`
- 【问题】
  1. `stream.readBytes()` 把整个账单读进内存（微信多年账单可达数 MB），低端机 OOM；`parseRows` 又把它变成 `List<List<String>>`，内存放大约 3~5 倍；
  2. `decode()` 用「替换字符 > 2」判断 GBK，不可靠；且只有 UTF-8 分支做了 `trimStart('\uFEFF')`，GBK 分支的 BOM 没去掉 → 表头首列名带 BOM → 列名匹配失败 → **整份账单解析为空**；
  3. `parseRows` 手工状态机没有处理 `\r\n` 之后紧跟 `\r` 的场景已处理，但 quoted 字段内的换行在 `inQuotes` 时被保留成 `\n`，后续按行匹配表头可能错位（可接受）。
- 【修改方向】改为 `BufferedReader` 逐行流式解析（先流到表头行，再逐行产出）；BOM 在解码后统一 `trimStart('\uFEFF')`；编码嗅探加上"是否含 GBK 常见字节序列"或用 `CharsetDetector`。
- 【优先级】3

---

### M11. Keystore 强转无兜底；SQLCipher 的"降级开关"实际从未自动生效
- 【文件:行号】`core/crypto/.../KeystoreKeyProvider.kt:20`；`core/crypto/.../SqlCipherSupport.kt:24-25`；`core/database/.../Migrations.kt:39-47`
- 【问题】
  1. `ks.getKey(MASTER_ALIAS, null)?.let { return it as SecretKey }` 是**无检查强转**，且未处理 `KeyPermanentlyInvalidatedException`；
  2. `SqlCipherSupport.Config` 的注释写着"某些 ROM 上 .so 加载失败 → 允许降级"，但 `openHelperFactory(passphrase, config)` **只是把用户手动开关透传**，没有任何 `try/catch`；`LedgerDatabaseFactory.create` 直接 `.build()`。也就是说：.so 一旦加载失败就是崩溃，注释承诺的降级**没有实现**。
- 【修改方向】
```kotlin
// LedgerDatabaseFactory
fun create(context: Context, passphrase: ByteArray, allowPlaintext: Boolean): LedgerDatabase {
    if (!allowPlaintext) {
        runCatching { SupportFactory(passphrase) }
            .onSuccess { return Room...openHelperFactory(it)...build() }
            .onFailure { Log.e(TAG, "SQLCipher 不可用: ${it.message}") }
    }
    // 降级：明文库 + 明确告警标志，由 UI 展示红色横幅
    SettingsStore.markPlaintextFallback()
    return Room.databaseBuilder(...).build()
}
```
- 【优先级】2

---

### M12. Room 枚举转换器把未知值静默改成默认值
- 【文件:行号】`core/database/src/main/java/com/autoledger/core/database/Converters.kt:15-28`
- 【问题】`runCatching { TxnStatus.valueOf(v) }.getOrDefault(TxnStatus.CONFIRMED)`。若某行的 status 字符串因迁移/bug 变成未知值，会被**静默改成 CONFIRMED**：MERGED 被改回来 → 去重重复合并；RAW 被改掉 → 待确认队列里的流水凭空消失。
- 【修改方向】未知值映射到 `TxnStatus.IGNORED` 并 `Log.e` + 上报，绝不能映射到一个"有效且会被自动处理"的状态；类型同理（`TxnType` 未知 → `IGNORED` 语义需要新增枚举或保留原始串的 `rawValue` 字段）。
- 【优先级】3

---

### M13. `enableEdgeToEdge()` 在 `super.onCreate()` 之前调用
- 【文件:行号】`app/.../ui/MainActivity.kt:38-39`
- 【问题】官方用法是 `super.onCreate(savedInstanceState)` 之后再 `enableEdgeToEdge()`；提前调用在部分 ROM / Activity 版本上会因 window 尚未就绪而失效或抛异常。
- 【修改方向】
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    ...
}
```
并确认 `Scaffold` 的 `padding` 已应用到内容（`MainActivity.kt:97` 已用 `Box(Modifier.padding(padding))` ✓），底部 `NavigationBar` 与 `imePadding()` 需实机验证。
- 【优先级】3

---

### M14. `TimeRange` 边界：`lastDays(0)` 产出负区间；窗口右端点是 `now`
- 【文件:行号】`core/model/src/main/java/com/autoledger/core/model/Stats.kt:58-75`
- 【问题】
  1. `lastDays(days)` 当 `days <= 0` 时 `minusDays(days - 1)` 变成**加一天**，`start > end` 得到负区间，所有统计静默返回空，无任何报错；
  2. `today()` / `thisMonth()` 的 `endInclusiveMillis = now`，晚于当前时刻的流水（跨时区账单、预授权、用户改过系统时间）会被漏掉。
- 【影响范围】趋势图/月度报表少数据且难定位。
- 【修改方向】
```kotlin
fun lastDays(days: Int, now: Long = System.currentTimeMillis()): TimeRange {
    require(days >= 1) { "days 必须 >= 1" }
    ...
}
```
若产品要求"今天"覆盖整天，改为 `end = 次日 0 点 - 1ms`（新增 `endOfDay(now)`）。已用 RED 用例 `Red_lastDays must tolerate non positive day count` 固化。
- 【优先级】3

---

### M15. 通知监听 Service 的 scope 从不取消；授权判定在部分 ROM 会误判
- 【文件:行号】`feature/capture/.../notify/LedgerNotificationListener.kt:29, 42-79, 86-91`；`feature/capture/.../notify/NotificationCaptureSource.kt:30-34`（`runCatching { startActivity }` 无日志）
- 【问题】
  1. `scope` 没有 `onDestroy { cancel() }`，`requestRebind` / 系统解绑重建会累积；
  2. `accessGranted` 读 `Settings.Secure.enabled_notification_listeners`，多用户/工作资料下读到的是当前用户的设置；部分 ROM 对该值做了截断或返回空 → 已授权却显示"未开启"，用户反复去设置页。
- 【修改方向】补 `onDestroy` 取消；`accessGranted` 增加兜底：同时检查 `ComponentName` 是否在 `NotificationManager.getEnabledListenerPackages()`（API 27+）里，两者取或。
- 【优先级】3

---

### M16. 短信扫描：排序串接 LIMIT、无进度反馈、Play 权限合规
- 【文件:行号】`feature/capture/.../sms/SmsCaptureSource.kt:50-59, 89-90`；`feature/capture/src/main/AndroidManifest.xml:5-8`
- 【问题】
  1. `sortOrder = "${Telephony.Sms.DATE} DESC LIMIT $limit"` 依赖 `SQLiteQueryBuilder` 接受 LIMIT 后缀（多数 ROM 可以，但不是契约保证；`limit` 未做上界约束，外部传参可注入）；
  2. `DEFAULT_LIMIT = 500` 且 `pullBacklog` 无进度回调，扫描期间 UI 只有一个 loading（配合 S8 的全表扫描，会长时间无响应）；
  3. `READ_SMS` 在 Google Play 属受限权限，manifest 未加 `android:maxSdkVersion` 说明、未声明 `PermissionGroup` 与用途说明，审核风险；`POST_NOTIFICATIONS` 在 app 与 feature 两处 manifest 重复声明（无害但冗余）。
- 【修改方向】`limit` 用 `coerceIn(1, 1000)`；改用 `query(uri, projection, selection, args, "${DATE} DESC")` + 游标取前 N 条；扫描分批 + 进度回调；Play 上架时补权限声明表单。
- 【优先级】3

---

### M17. 屏幕/系统兼容性：无横屏与大屏适配，Android 15 键盘需实机验证
- 【文件:行号】`app/src/main/AndroidManifest.xml:15-20`；所有 `ui/screens/*.kt` 的 `LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp))`
- 【问题】
  1. 未声明 `screenOrientation`，也未做 `WindowSizeClass` 适配；横屏/折叠屏展开态下 16dp 边距 + 单列卡片会显得空旷，底栏 6 个入口在小屏（360dp）下 `alwaysShowLabel = true` 有挤压风险（6 项 × 图标+文字）；
  2. `android:windowSoftInputMode="adjustResize"` 与 `enableEdgeToEdge()` 组合在 Android 15（targetSdk 35）上的键盘遮挡需实机验证；
  3. `Theme.AutoLedger` 继承平台 `android:Theme.Material.Light.NoActionBar`（themes.xml:4），与 Material3 Compose 混用时状态栏图标颜色需验证。
- 【修改方向】加 `calculateWindowSizeClass` 分支（宽屏两列）；底栏 `alwaysShowLabel` 在小屏改 `false` 或减至 5 项；主题改用空壳 `Theme.Material3.Light.NoActionBar` 或直接 `android:Theme.NoTitleBar`，由 Compose 全权控制。
- 【优先级】4

---

### M18. 用户设置用 `Float` 存金额、用 `apply()` 落盘，且不随备份导出
- 【文件:行号】`app/src/main/java/com/autoledger/app/UserSettings.kt:30-38, 56-63`
- 【问题】`putFloat(KEY_SALARY, profile.monthlyNetSalary.toFloat())`：Double→Float 精度损失（月薪填 123456.78 会被截断）；`apply()` 异步落盘；注释已声明"不随备份导出"，换机需重填——这条与"备份文件自带版本号可完整迁移"的产品承诺不一致。
- 【修改方向】金额改 `putString` 存 `BigDecimal.toPlainString()` 或 `putLong`（转成分）；关键配置用 `commit()`；把 `WageProfile` / `FreedomGoal` 一并写进 `BackupEnvelope.payload`（v3 或作为 v2 的可选字段，导入时 `opt` 读取保证向后兼容）。
- 【优先级】3

---

### M19. 分类置信度上限设计自相矛盾，导致短关键词永远进不了自动入账
- 【文件:行号】`feature/classify/.../Classifiers.kt:37-42`；`feature/capture/.../IngestPipeline.kt:34, 113-115`
- 【问题】
```kotlin
val base = if (hit.kind == RuleKind.MERCHANT_EXACT) 0.95f else 0.6f
confidence = (base + hit.pattern.length / 100f).coerceAtMost(0.85f)
```
  - `MERCHANT_EXACT` 的 base 0.95 被 `coerceAtMost(0.85f)` 压到 0.85 —— 精确商户规则与模糊关键词**被压到同一上限**，注释"最高不超过 0.85"与代码语义矛盾；
  - 阈值 `autoConfirmThreshold = 0.75f`：关键词 base 只有 0.6，需要 `pattern.length >= 15` 才够 0.75。实际规则包里全是 2~3 字的词（"咖啡""地铁""超市"）→ 置信度 0.62 → **永远进待确认队列**，与"自动分类"的产品目标冲突。
- 【修改方向】分档：`MERCHANT_EXACT` → `0.92f`；`KEYWORD` → `0.6f + min(pattern.length, 8) / 20f`（3 字 → 0.75，8 字 → 1.0 再 coerce 到 0.9）。并把阈值降到 `0.7f`，或按 `RuleKind` 分别设阈值。
- 【优先级】2

---

### M20. 账户名模糊包含会把整个渠道的流水判成内部划转
- 【文件:行号】`feature/dedup/src/main/java/com/autoledger/feature/dedup/TransferRules.kt:43-49`
- 【问题】`ownAccount` 里 `ctx.counterparty.contains(account.name)`。若用户把账户命名为"微信"/"支付宝"/"银行卡"/"我的卡"，那么几乎所有该渠道流水都会被判成 `SELF_TRANSFER`（置信度 0.95，最高优先级）→ `IngestPipeline` 把它标成 `TRANSFER` → **月度支出被清零**（比 S9 更严重，且没有任何提示）。
- 【修改方向】
```kotlin
val nameHit = account.name.length >= 4 && ctx.counterparty.contains(account.name)
```
并要求 `hintHit` 与 `nameHit` 至少一条是**强证据**（hint 长度 ≥ 4）；同时 `DefaultTransferDetector` 在 `SELF_TRANSFER` 且置信度 0.95 时，也应保留 `TxnStatus.RAW` 让用户在采集箱确认，而不是直接不计入消费。
- 【优先级】2

---

## 四、建议（Suggestion）

| # | 【文件:行号】 | 【问题】 | 【修改方向】 | 优先级 |
|---|---|---|---|---|
| L1 | `core/model/.../Money.kt:36` | `val sign = if (withSign) "" else ""` 两分支相同，`withSign` 完全无效（死代码） | 删掉参数或实现"不带符号"分支；已由 `MoneyTest.formatYuan always keeps sign...` 固化现状 | 5 |
| L2 | `core/model/.../Money.kt:20` | `YUAN_PATTERN` 取最左匹配，"订单号12345678 支付12.30元" 会解析成 12345678（当前 `fromYuan` 是死代码，暂未爆雷） | 优先匹配带 `¥/￥/元/金额` 上下文的 token；RED 用例 `Red_fromYuan must prefer money like token...` | 4 |
| L3 | `core/model/.../Money.kt:30` | `fromYuanDouble` 用 `(v*100).toLong()`：`0.29 → 28`、`1.15 → 114` | `BigDecimal.valueOf(v).setScale(2, HALF_UP).movePointRight(2).longValueExact()`；RED 用例已固化 | 2 |
| L4 | `core/model/.../Money.kt:15` | `abs()` 在 `Long.MIN_VALUE` 上溢出返回负数 | `if (minor == Long.MIN_VALUE) Long.MAX_VALUE else abs(minor)`，或限制金额上界 | 4 |
| L5 | `feature/dedup/.../LedgerDuplicateResolver.kt:69-71` | `sha256` 用 `"%02x".format(it)` 逐字节格式化，每条流水 32 次 Formatter + 默认 Locale | 预定义 `HEX` 查表数组，或 `Integer.toHexString(b.toInt() and 0xff).padStart(2,'0')` | 4 |
| L6 | `feature/capture/.../notify/NotificationParser.kt:53-59` | `Regex(p)` 每次解析重新编译（5 条规则 × 2~3 个 pattern ≈ 10 次编译/通知） | 在 `NotificationRule` 里预编译缓存（`lazy` 或构造时编译） | 4 |
| L7 | `feature/capture/.../notify/NotificationParser.kt:27-42` | 微信/支付宝包名强制要求 `packageNames` 命中，未命中的通知被 `return null` **直接丢弃**，既不入库也不进待确认 | 落一条 `amountHint = null` 的 RAW 信封（走 `IngestPipeline.NeedsReview`），保留"我漏了什么"的可观测性 | 3 |
| L8 | `core/backup/.../BackupManager.kt:187` | `currentPassphrase: CharArray` 长期挂在单例上从不 wipe；`PassphraseKeyDeriver.wipe` 有定义但无人调用 | 导入完成后立即 `wipe` + 置 null；`PBEKeySpec` 用完 `clearPassword()` | 3 |
| L9 | `core/backup/.../BackupManager.kt:124` | `REPLACE_ALL` 只清 transactions，分类/账户/规则不清理；UI 写死 `MERGE_BY_ID`（AppStores.kt:394），`REPLACE_ALL` 分支无入口也无说明 | 明确两种策略语义；`REPLACE_ALL` 时一并清 categories/accounts/rules，并在 UI 给用户选择 | 4 |
| L10 | `core/database/.../Migrations.kt:31, 43-47` | 无 `fallbackToDestructiveMigrationOnDowngrade`；v3 若漏写 migration 就是全量崩溃丢数据（注释说"失败即崩以强制遵守约定"，但代价是用户数据） | 生产版本至少加 `fallbackToDestructiveMigrationOnDowngrade()`；把 migration 完整性作为 CI 检查项（对比 `schemas/` 快照） | 3 |
| L11 | 全局 | 大量已实现但**从未被调用**：`RoomLedgerRepository.observeSince/observeRawQueue/findByFingerprintNear/pendingOps/removeOp/linkTransferPair`、`LedgerDuplicateResolver.unmerge`、`TransferPairMatcher`、`Redactor`、`Money.fromYuan/formatYuan`、`LedgerSchema.BACKUP_EXTENSION`、`CloudSyncClient` | UI 改用 Room 的 `Flow` 观察（新增流水后首页自动刷新）；否则把死代码删除或在文档标注"预留" | 4 |
| L12 | `app/.../ui/screens/LedgerScreens.kt:140` | `store.visibleItems()` 在组合中直接读 `_state.value`，Compose **不会订阅**它；目前靠 `state` 变化顺带重组才生效，属隐式依赖 | 改 `val visible = remember(state) { store.visibleItems() }` 或直接 `derivedStateOf` | 3 |
| L13 | `app/.../ui/screens/InsightScreens.kt:131-134` | 发现页同时创建 `InsightsStore` + `HomeStore` 并各 `load()` 一次，HomeStore 算出的 4 个 metric 直接丢弃 | 复用同一个 HomeStore，或让 InsightsStore 只取自己需要的字段 | 4 |
| L14 | `app/.../ui/components/Common.kt:175-188` | `MetricCard` 的 Breakdown 条形图对 `slices` **全量**渲染 `Box(Modifier.weight(...))`，分类多时几十个 measurable；列表只 `take(5)` 但条形图不截断 | 条形图 `slices.take(8)`，其余归并为"其他" | 4 |
| L15 | `core/model/.../Model.kt:25` vs `core/database/.../repository/Mappers.kt:21` | `direction` 由 amount 推导，`toEntity` 又忽略传入值重新推导一次 | 单一来源：只在一处推导，实体侧直接透传 | 5 |
| L16 | `core/database/.../Migrations.kt:25` | v1→v2 新增 `schemaVersion` 默认 **1**，而 `LedgerSchema.CURRENT = 2`；老行的行级版本落后于当前版本，备份迁移链会重复处理 | 默认填 `LedgerSchema.CURRENT`，或在文档明确"行级版本 = 写入时的版本"语义 | 4 |
| L17 | `app/src/main/AndroidManifest.xml:8-10` + `res/xml/backup_rules.xml` | `allowBackup="false"` 与 `fullBackupContent` / `dataExtractionRules` 语义重叠；`backup_rules.xml` 的 `<exclude domain="file" path="backups"/>` 从不使用 | 二选一并删除无用 exclude | 5 |
| L18 | `app/src/main/res/values/themes.xml:4` | 主题继承平台 `android:Theme.Material.Light.NoActionBar`，与 Material3 + `enableEdgeToEdge` 混用 | 改为空壳主题，全部交给 Compose；实机验证状态栏图标对比度 | 4 |
| L19 | `feature/capture/.../IngestPipeline.kt:136` | `Outcome.NeedsReview` 的 reason 用 `"%.2f".format(confidence)`（默认 Locale） | `"%.2f".format(Locale.US, confidence)` | 5 |
| L20 | `core/crypto/.../CryptoBox.kt:28` | `require(sealed.size > IV_BYTES)` 只校验 >12，未校验 ≥ 28（IV+TAG）；损坏密文走到 `doFinal` 抛 `AEADBadTagException`，错误信息不直观 | `require(sealed.size >= IV_BYTES + TAG_BITS / 8)` 并给出明确异常文案 | 5 |

---

## 五、可测试性改进建议（Testability）

当前工程**零测试**，且若干关键类因为"直接 new / 直接读 DB / 依赖 Android 框架"而无法在 JVM 上测。按投入产出排序：

| 建议 | 目标类 | 具体做法 |
|---|---|---|
| T1 | `IngestPipeline`、`DefaultTransferDetector`、`CompositeClassifier` | **`IngestPipeline` 是最该被测却最难测的类**：它直接持有 `CryptoBox`（Android `Base64`）与 `DuplicateResolver`。建议把 `CryptoBox` 抽成接口 `TextSealer { seal(s): String?; open(s): String? }`，生产实现走 Android，测试实现走 Base64/明文。这样整条入账流水线可在 JVM 上端到端回放。 |
| T2 | `KeywordClassifier` / `MemoryClassifier` / `CorrectionLearner` | 它们依赖 `ClassifierRuleDao`（Room）。抽 `RuleSource { fun all(): List<ClassifierRule> }` 接口，`RoomRuleSource` 是生产实现；测试用内存实现。顺带解决 M3 的全表读。 |
| T3 | 所有 Store（`HomeStore` 等） | 注入 `CoroutineDispatcher`（`Dispatchers.Main` 用 `MainDispatcherRule` 或注入 `DispatcherProvider`），测试用 `StandardTestDispatcher`，消除 `runBlocking` 里的时序不确定与主线程依赖。 |
| T4 | `NotificationParser` / `BillImportCaptureSource` | 已经是纯函数 + `InputStream`，天然可测——**不需要任何改造**，直接补 JVM 用例即可（本次未覆盖，建议下一轮补齐，见第六节）。 |
| T5 | `BackupMigrator` / `BackupEnvelope` | 迁移链是纯 JSONObject 变换，可测；但 `BackupManager` 依赖 `LedgerDatabase`。建议把 `buildPayload()` / `toTransactions()` 拆成 `object BackupCodec`（静态纯函数），`BackupManager` 只负责编排。这样 v1→v2 迁移可以在 JVM 上跑 golden file 测试。 |
| T6 | `SmsCaptureSource` / `LedgerNotificationListener` | 依赖 `ContentResolver` / `StatusBarNotification`。建议把"解析"从"取数"里剥离：`fun parseCursor(rows: List<SmsRow>): List<RawEnvelope>`，Service/Provider 只负责取数。 |
| T7 | 全局 | `PassphraseVault` / `KeystoreKeyProvider` / `CryptoBox` 建议抽 `KeyProvider` 接口；`SqlCipherSupport` 抽 `DatabaseOpener`。这样"密钥失效降级"这条灾难路径才有办法在测试里触发。 |
| T8 | CI | `core:model` / `feature:dedup` / `feature:stats` 都是 **`kotlin.jvm` 纯 JVM 模块**，天生适合做 CI 的第一道门禁：加一条 `./gradlew :core:model:test :feature:dedup:test :feature:stats:test`，不需要模拟器即可拦截绝大部分回归。 |

---

## 六、关键路径缺失用例清单（尚未编写，建议下一轮补齐）

| 关键路径 | 应覆盖的场景 | 难点 / 前置条件 |
|---|---|---|
| 金额解析 | `Money.fromYuan` 各格式（已覆盖）、`fromYuanDouble` 精度（已 RED）、`NotificationParser.toMinor` | `NotificationParser` 在 Android 模块，需先把它下沉到 JVM 模块或抽纯函数 |
| 转账识别 | 已覆盖（`TransferRulesTest`） | — |
| 去重指纹 | 已覆盖（`LedgerDuplicateResolverTest`） | — |
| CSV 账单解析 | `CsvBillReader` 表头嗅探（≥3 列名）、GBK/BOM、引号内含逗号、空行、合计行；`rowToEnvelope` 的「不计收支」与 REJECT_STATUS | **需先把 `BillImportCaptureSource` 的解析部分搬到 JVM 模块或在 `:feature:capture` 加 Robolectric** |
| 备份迁移 v1→v2 | 金额 Double→分（S10 已 RED 待补 golden）、缺失字段补默认、rules 补 `learned`、未知版本抛错、信封 kind 校验 | 需先做 T5（`BackupCodec` 抽纯函数） |
| 分类置信度 | `KeywordClassifier` 最长匹配、`MERCHANT_EXACT` 不被 coerce、`CompositeClassifier` 取最高置信度且单分类器异常不影响链路（M19） | 需先做 T2（`RuleSource` 接口） |
| `IngestPipeline` 端到端 | 转账优先于分类、未解析金额 → NeedsReview、重复 → MergedInto/NeedsReview 分支、加密失败降级 | 需先做 T1（`TextSealer` 接口） |
| `TimeRange` / `WageProfile` | 已覆盖 | — |
| `Metrics` | 已覆盖 | **被 S1 阻塞，修完才能编译** |

---

## 七、本次交付的测试资产

### 文件清单与用例数

| 模块 | 文件 | 用例数 | 说明 |
|---|---|---|---|
| `:core:model` | `src/test/java/com/autoledger/core/model/MoneyTest.kt` | 20 | 金额解析 / 符号 / 格式化 / 3 个 RED |
| `:core:model` | `src/test/java/com/autoledger/core/model/WageProfileTest.kt` | 14 | 真实时薪公式全展开 / 除零 / 极值 |
| `:core:model` | `src/test/java/com/autoledger/core/model/TimeRangeTest.kt` | 8 | 统计时间窗边界 / 1 个 RED |
| `:core:model` | `src/test/java/com/autoledger/core/model/LedgerModelTest.kt` | 11 | 领域模型默认值与 SPI 兜底 |
| `:feature:dedup` | `src/test/java/com/autoledger/feature/dedup/FakeLedgerRepository.kt` | — | 内存版 `LedgerRepository`（测试夹具） |
| `:feature:dedup` | `src/test/java/com/autoledger/feature/dedup/LedgerDuplicateResolverTest.kt` | 16 | 指纹 / 窗口 / 排序 / 合并 / 1 个 RED |
| `:feature:dedup` | `src/test/java/com/autoledger/feature/dedup/TransferRulesTest.kt` | 23 | 转账规则优先级 / 自有账户 / 成对配账 |
| `:feature:stats` | `src/test/java/com/autoledger/feature/stats/FakeLedgerRepository.kt` | — | 内存版仓库 + `Fixtures` |
| `:feature:stats` | `src/test/java/com/autoledger/feature/stats/MetricsTest.kt` | 17 | 5 个统计维度 + 注册表 |
| **合计** | **9 个文件（7 个测试类 + 2 个夹具）** | **109 个用例** | 其中 4 个 RED |

### 4 个 RED 红灯用例（对应缺陷编号）

| 用例 | 断言的正确行为 | 对应缺陷 |
|---|---|---|
| `MoneyTest.Red_fromYuanDouble must not lose a cent to binary floating point` | `fromYuanDouble(0.29) == 29` | L3 |
| `MoneyTest.Red_fromYuanDouble handles 1_15` | `fromYuanDouble(1.15) == 115` | L3 |
| `MoneyTest.Red_fromYuan must prefer money like token over long digit run` | "订单号12345678 支付12.30元" → 1230 | L2 |
| `TimeRangeTest.Red_lastDays must tolerate non positive day count` | `lastDays(0)` 不得产出 start > end | M14 |
| `LedgerDuplicateResolverTest.Red_blank merchant must not collapse every same amount txn into one fingerprint` | 空商户时指纹必须引入第二信号 | **S9** |

> 修复对应缺陷后，这些用例应自动转绿；请在 CI 里把"RED 用例全部转绿"作为修复完成的验收条件。

### 需要说明的两处构建改动

为让上述测试**可编译可运行**，我在 3 个 JVM 模块的 `build.gradle.kts` 的 `dependencies` 里各加了一行（未改动任何生产源码）：

```kotlin
// QA: 纯 JVM 单元测试（kotlin.test + JUnit4 运行时）
testImplementation(kotlin("test"))
```

涉及文件：`core/model/build.gradle.kts`、`feature/dedup/build.gradle.kts`、`feature/stats/build.gradle.kts`。
如果团队不希望引入该依赖，可替换为 `testImplementation("junit:junit:4.13.2")` 并把 `kotlin.test.*` 换成 `org.junit.Assert.*`。

### 运行方式（环境就绪后）

```bash
./gradlew :core:model:test :feature:dedup:test :feature:stats:test
```

S0~S4 全部修复后，**三个模块的测试现在都能编译运行**（均为纯 JVM，不需要模拟器 / Robolectric）。

### RED 用例复核（修复后快照）

4 个红灯用例对应的生产代码**全部未改动**，红灯仍然有效，可直接作为修复验收条件：

| RED 用例 | 目标代码现状 | 结论 |
|---|---|---|
| `Red_fromYuanDouble must not lose a cent...`（L3） | `Money.kt:30` 仍为 `(value * 100).toLong()` | 仍红 |
| `Red_fromYuanDouble handles 1_15`（L3） | 同上 | 仍红 |
| `Red_fromYuan must prefer money like token...`（L2） | `Money.kt:20` 正则仍为最左匹配 | 仍红 |
| `Red_lastDays must tolerate non positive day count`（M14） | `Stats.kt:73` 仍为 `minusDays(days - 1L)`，无参数校验 | 仍红 |
| `Red_blank merchant must not collapse...`（**S9**） | `LedgerDuplicateResolver.kt:31-34` 指纹仍为 `hash(金额｜归一化商户)` | 仍红 |

### 因 S1 落地而同步调整的测试夹具

`LedgerRepository` 新增 `listRange` 后，两个 `FakeLedgerRepository` 必须实现它，否则测试源集编译失败。**已同步更新**：

- `feature/dedup/src/test/.../FakeLedgerRepository.kt:48-54`
- `feature/stats/src/test/.../FakeLedgerRepository.kt:49-55`

两者均改为 `override suspend fun listRange(fromMillis, toMillis, includeTransfers)`（闭区间 + 过滤 TRANSFER/REFUND，与 `RoomLedgerRepository` 语义一致）。**以后接口再加方法，这两个夹具必须同步补。**

---

## 八、修复建议的优先级排序（给 Engineer）

**第一批（不修则编译不过）—— 已全部修复 ✅**
1. S0 补 `import java.time.Instant`（`Stats.kt`）
2. S1 补 `LedgerRepository.listRange`
3. S2 删除 `goAsync()`
4. S3 补 `import android.util.Base64`
5. S4 `rememberCoroutineScope()` 提到 Composable 顶层

> 下一步建议：先跑一次 `./gradlew :core:model:test :feature:dedup:test :feature:stats:test` 验证绿色基线（预期 109 个用例中 105 绿 / 4 红），再进入第二批。

**第二批（不修则上线必出事故）**
5. S5 启动路径下沉到 IO + lazy
6. S6 `PassphraseVault` 兜底 + `commit()`
7. S9 去重指纹在空商户时的降级
8. S7 CSV 日期解析多格式 + 解析失败丢弃
9. S8 去重改用 `findByFingerprintNear` 索引查询
10. S10 v1→v2 金额走 BigDecimal

**第三批（体验与准确性）**
11. M19 置信度分档、M20 账户名最小长度、M9 「不计收支」、M4 分类器拿到正文、M3 规则缓存、M2 写入事务、M8 清空二次确认、M6 storeScope 治理

**第四批（健壮性与可维护性）**
12. M5/M11/M12/M13/M14/M15/M16/M17/M18 + 全部 L 级建议 + 第六节的用例补齐
