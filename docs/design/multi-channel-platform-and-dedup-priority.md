# 新增渠道 + 自定义平台 + 识别归并 + 去重优先级 —— 增量设计与任务分解

> 设计人：架构师 高见远 ｜ 日期：2026-09-27 ｜ 只读设计产物，未改动任何源码
> 行号基于当前工作区快照。

---

## 0. 先讲清楚：哪些已实现、哪些是本次增量

复核代码后可以确认：**「消费平台」体系的主体在上一轮改造中已经落地**。本次不是从零搭，而是**补 4 个缺口**。先划清边界，避免重复造轮子。

### 0.1 已经实现（本次**不要**重做）

| 能力 | 位置 | 说明 |
|---|---|---|
| 平台领域类型 + 角色区分 | `core/model/.../platform/Platform.kt:39-48` | `PlatformKind = ORDER \| PAYMENT \| OTHER`，注释已写明「淘宝下单 + 支付宝付款」为何要分角色 |
| 用户权威标记 | `Platform.kt:23-29` | `PlatformSource = AUTO \| USER`，「用户改过即不可被自动覆盖」 |
| 目录 + 运行时注册 | `Platform.kt:78-197` | `PlatformCatalog`、`UNKNOWN_ID`、`register()`（注释：预留给用户自定义，**本期不开放 UI**） |
| 识别引擎（多级打分） | `feature/platform/.../KeywordPlatformResolver.kt` | 包名 0.95 > 强词 0.90 > 中词 0.60 > 弱词 0.35 |
| **ORDER 优先于 PAYMENT** | `KeywordPlatformResolver.kt:94-136` | 规则 1（包名是支付通道、正文有下单平台 ⇒ 下单平台胜出）+ 规则 2（无包名时 ORDER 优先，护栏 ≥0.50）**均已实现** |
| 识别接入流水线 | `feature/capture/.../IngestPipeline.kt:58-81` | 在 draft 构造前调 `platformResolver.resolve(...)`，写 `platformId/confidence/source=AUTO` |
| DB 三列 | `Entities.kt`（transactions） | `platform_id` / `platform_confidence` / `platform_source`（v5 迁移已加） |
| 显式迁移起步 | `Migrations.kt:32-50` | `MIGRATION_4_5`；**v5 起已终止 destructive 策略**，改结构必须补显式 Migration |
| UI：平台名展示 + 待确认角标 | `app/.../ui/components/Common.kt:302-306` | 低于 `CONFIRM_THRESHOLD` 打角标 |
| UI：平台选择器 | `app/.../ui/components/TxnEditing.kt:284`、`LedgerScreens.kt:182` | 遍历 `PlatformCatalog.all()` |
| UI：按平台筛选 / 搜索匹配平台名 | `AppStores.kt:210/423`、`LedgerQuery.kt:29-30` | 含「只看未识别」 |
| 按平台统计 | `feature/stats/.../Metrics.kt` `PlatformShareMetric` | 已注册（`AppContainer.kt:282`） |

### 0.2 本次要补的 4 个缺口

| # | 缺口 | 现状 | 本次要做 |
|---|---|---|---|
| **G1** | 三类新渠道（银行 / 数字人民币 / 云闪付） | 目录里没有 | 加 3 条 `PlatformEntry` + 一个 `bank` 兜底规则 |
| **G2** | 用户自定义平台 | `register()` 有实现但**无 UI、无持久化** | 新增 `user_platforms` 表 + 管理 UI + 启动注入目录 |
| **G3** | 平台识别（按通知来源） | 识别本身已实现 | **只需补新平台的关键词/包名数据 + bank 兜底**；不要重写引擎 |
| **G4** | **去重优先级** | `LedgerDuplicateResolver` **完全没有平台概念**；`IngestPipeline:152` 直接让"已存在的那条"当主记录 | **本次核心**：`PlatformPriority` + 两级匹配 + 主记录裁决 + 合并信息保留 |

> **直答 team-lead 的问题 B3**：「ORDER 优先于 PAYMENT」在**识别层**已实现且足以支撑「美团 > 微信/支付宝」；**但在去重/合并层完全没实现** —— 现在谁当主记录纯看谁先入库。这正是 G4。

---

## 1. 方案概述

```
通知/短信进来
   ↓ NotificationParser          （不动）
   ↓ KeywordPlatformResolver      ← G1 加数据、G3 加 bank 兜底
   ↓ IngestPipeline
        ├─ 平台识别结果写入流水
        └─ 去重：Tier-1 指纹精确（已有）→ 未命中则 Tier-2 层级互补（新增）
                 ↓ 命中后按 PlatformPriority 裁决谁当主记录（新增）
                 ↓ 合并：写入 merged_into_id + 从 merged 补 primary 空白（新增）
   ↓ Room（v6：+ user_platforms 表、+ transactions.merged_into_id）
```

三条设计主线：
1. **数据驱动，不加分支**：新渠道 = 往目录加数据；用户自定义 = 往 DB 存数据再注入目录。引擎逻辑几乎不动。
2. **去重分两级**：指纹精确（商户一致）走老路；层级互补（商户不同但描述同一笔）走新路。**新增的路必须严守护栏**，否则会吞掉真实消费。
3. **合并必须可追溯**：`merged_into_id` + 字段"只补空白不覆盖"。

---

## 2. 数据模型

### 2.1 `PlatformEntry` / `PlatformKind` 扩展（core:model）

**新增枚举值 `PlatformKind.BANK`**：

```kotlin
enum class PlatformKind {
    ORDER,    // 下单平台：消费发生的场所
    PAYMENT,  // 支付通道：钱从哪条通道出去
    BANK,     // 【新增】银行：钱从哪张卡出去（结算侧）
    OTHER,    // 既非下单也非通道
}
```

**为什么"银行"要独立成一个 kind，而不是塞进 PAYMENT 或 OTHER**：
- 塞进 `PAYMENT` ⇒ 它会与微信/支付宝同级，而需求要求 **银行卡优先级最低**（美团 > 微信/支付宝 > 银行卡），同级就没法排序；
- 塞进 `OTHER` ⇒ `toPriority()` 会得到 `NONE`，就没法参与层级比较，Tier-2 匹配失效。

**安全性**：全仓 `PlatformKind` 的使用点只有 `KeywordPlatformResolver` 的 `== ORDER` / `== PAYMENT` 判断（`grep` 已确认无穷尽 `when`），新增枚举值是**源码兼容**的。

**新增优先级（去重语义）**：

```kotlin
// core:model .../platform/Platform.kt
/**
 * 去重层级 —— 「谁留下」的排序，**与 PlatformKind（角色）刻意分开**：
 * kind 描述业务角色，priority 描述合并语义；将来二者可能不同（例如把 OTHER 也纳入层级）。
 */
enum class PlatformPriority(val rank: Int) {
    NONE(0),     // unknown / OTHER —— 不参与层级比较
    BANK(1),     // 银行卡：最低
    PAYMENT(2),  // 微信 / 支付宝 / 云闪付 / 数字人民币
    ORDER(3),    // 美团 / 淘宝 / 拼多多 / 抖音：最高
}

fun PlatformKind.toPriority(): PlatformPriority = when (this) {
    PlatformKind.ORDER   -> PlatformPriority.ORDER
    PlatformKind.PAYMENT -> PlatformPriority.PAYMENT
    PlatformKind.BANK    -> PlatformPriority.BANK
    PlatformKind.OTHER   -> PlatformPriority.NONE
}

/** ID → 层级。未收录 ID 一律 NONE（绝不抛异常）。 */
fun priorityOf(platformId: String): PlatformPriority =
    PlatformCatalog.find(platformId)?.kind?.toPriority() ?: PlatformPriority.NONE
```

> 用**纯函数**派生而非在 `PlatformEntry` 上加一个 `priority` 字段：少一个字段就少一处"自定义平台忘了填 priority"的出错可能，且天然与 kind 一致。

### 2.2 三类新渠道的条目设计

```kotlin
PlatformEntry(
    id = "bank",
    displayName = "银行卡",
    kind = PlatformKind.BANK,
    // 刻意**只给 weak**：「银行」是极高频词（几乎每条银行短信都有），
    // 放 strong(0.90) 会把大量本该 unknown 的记录吸成 bank。
    weakKeywords = listOf("储蓄卡", "信用卡", "借记卡", "尾号", "银行"),
    // 不按包名匹配：各银行 App 包名零散且多为「待核实」，且银行短信没有包名
    packageNames = emptySet(),
    sortOrder = 70,
),
PlatformEntry(
    id = "digital_rmb",
    displayName = "数字人民币",
    kind = PlatformKind.PAYMENT,
    strongKeywords = listOf("数字人民币", "数币支付"),
    mediumKeywords = listOf("数字人民币钱包", "e-CNY"),
    weakKeywords = listOf("试点版"),
    packageNames = emptySet(),   // 待核实
    sortOrder = 80,
),
PlatformEntry(
    id = "unionpay",
    displayName = "云闪付",
    kind = PlatformKind.PAYMENT,
    strongKeywords = listOf("云闪付"),
    mediumKeywords = listOf("银联", "UnionPay"),
    weakKeywords = listOf("银联商务", "云闪付支付"),
    packageNames = emptySet(),   // 待核实
    sortOrder = 90,
),
```

**「银行卡」的语义边界（直答需求 A3）**

| 问题 | 结论 |
|---|---|
| 「银行卡」是 platform 还是 sourceId？ | **是 platform（`platform_id`）**。理由：`sourceId` 是"**怎么抓到的**"（sms/notify/bill_import/manual），是技术追溯字段；而用户要的是"我这个月银行卡花了多少"这种**业务维度**，只有进 `platform_id` 才能进「按消费平台统计」 |
| 一笔来自银行短信、又没识别出商家平台，落成什么？ | 落 **`platformId = "bank"`**，而不是 unknown。因为**我们确实知道这笔钱是从银行卡出去的**。confidence 0.35 ⇒ 低于 `CONFIRM_THRESHOLD(0.75)` ⇒ UI 自动打「待确认」角标，用户可一键改 |
| 与 `unknown` 的区别 | **`bank` = 知道钱从哪出去（银行卡），但不知道花在哪；`unknown` = 连支付通道都不知道**（如账单导入的模糊行）。二者不可合并 |

> ⚠️ 注意这里**刻意没有用 `sourceId == "sms"` 来判银行**：`PlatformContext` 的文档已明确「sourceId 不参与平台判定」（`Platform.kt:206`）。银行判定**只依据文本关键词**，以保住这条架构不变量。

### 2.3 用户自定义平台：存储方案（决策）

**结论：新增 Room 表 `user_platforms`**，不用 DataStore / JSON 文件。

| 方案 | 结论 |
|---|---|
| DataStore / 本地 JSON | ❌ **不进备份管线** ⇒ 换机/导入备份后自定义平台丢失，而引用它的流水还在（会显示「未知平台」）。且与 `BackupManager` 现有的 `categories/accounts/rules` 数组模式不一致 |
| ✅ **Room 新表** | ① 天然可进备份（照抄 categories 的导出/导入模式）；② 支持**软删除**（归档后历史流水仍能正确显示名称，而不是变成「未知平台」）；③ 复用已有 `database` 单例与迁移体系，无新依赖 |

**表结构 `user_platforms`（`UserPlatformEntity`）**

| 列名 | 类型 | 说明 |
|---|---|---|
| `id` | TEXT PK | **生成规则：`user:<UUID>`** —— 前缀让来源一眼可辨，且与内置 ID（wechat/alipay/…）**不可能冲突** |
| `displayName` | TEXT | 用户输入，去首尾空白；**非空校验放 UI 层**（重名允许，仅提示，避免用户被"名称重复"卡住） |
| `kind` | TEXT | 存 `PlatformKind.name`（ORDER / PAYMENT / BANK / OTHER）。**默认 `ORDER`**（用户自定义多半是"某个商家/平台"） |
| `strongKeywords` | TEXT | **换行 `\n` 分隔**（沿用 `AccountEntity.identifierHints` 的既有权宜做法：实体用 String、领域用 List） |
| `mediumKeywords` | TEXT | 同上 |
| `weakKeywords` | TEXT | 同上 |
| `packageNames` | TEXT | 同上 |
| `sortOrder` | INTEGER | 用户自定义排序；**默认接在内置之后**（建议 `1000 + 序号`），保证内置平台永远排在前面 |
| `archived` | BOOLEAN | **软删除**。归档后：不进识别候选、但仍能被 `displayNameOf` 找到 ⇒ 历史流水显示不塌成「未知平台」 |
| `createdAtMillis` | INTEGER | 创建时间 |
| `schemaVersion` | INTEGER | 行级结构版本（与 `LedgerTransaction` 同约定） |

索引：`Index(value = ["archived"])` ⇒ Room 生成名 **`index_user_platforms_archived`**。

**与 `PlatformCatalog` 的关系与注册时机**

```
Room (真源)  ──启动读取──▶  PlatformCatalog.register(...)  (进程内缓存)
     ▲                                   │
     └────写成功后再 register/unregister────┘
```

| 时机 | 动作 |
|---|---|
| 启动 | `AppContainer.bootstrap()` 中，**在 `startCaptureLoop()` 之前**（紧跟现有 seeds 之后）读 `listUserPlatforms()` 并逐个 `register()`。**必须早于采集循环**，否则冷启动窗口内到达的通知会用不含自定义平台的目录识别 |
| 新增/编辑 | `upsertUserPlatform(...)` 成功 → `PlatformCatalog.register(entry)`（同 ID 覆盖，已有能力） |
| 停用/删除 | `archiveUserPlatform(id)` 成功 → **`PlatformCatalog.unregister(id)`**（**需新增该方法**，现有只有 register） |
| 备份导入 | 导入后**重新注入整个目录**（`resetExtras()` + 全量 register），避免残留旧条目 |

**`PlatformCatalog` 需要补的两处**

```kotlin
/** 移除运行时条目（停用/删除自定义平台）。内置条目不可移除。 */
fun unregister(id: String) { extra.removeAll { it.id == id } }

// ⚡ 性能修正：all() 现在每次都 (BUILT_IN + extra).sortedBy{}，
// 而 find() → all()、displayNameOf() → find() → all()，
// 意味着**列表里每一行渲染都会重排一次目录**。加入用户自定义后规模还会增长。
// 改为缓存 + 失效：
@Volatile private var cached: List<PlatformEntry>? = null
fun all(): List<PlatformEntry> =
    cached ?: (BUILT_IN + extra).sortedBy { it.sortOrder }.also { cached = it }
// register() / unregister() / resetExtras() 里置 cached = null
```

> ⚠️ `PlatformCatalog` 是**进程级可变单例**：测试里注册过的条目会污染其它测试 ⇒ **每个用到自定义平台的测试必须 `resetExtras()`**（已有 `internal` 方法），且 `resetExtras()` 也要清缓存。

### 2.4 迁移方案 v5 → v6（SQL 与索引名）

**决策：`DATABASE_VERSION` 5 → 6、`BACKUP_VERSION` 5 → 6。**

> ⚠️ v5 起 `fallbackToDestructiveMigration()` 只是兜底，**正常路径必须走显式 Migration**。若版本号从 5 跳到 6 而漏写 `MIGRATION_5_6`，Room 找不到路径 ⇒ 触发破坏性回退 ⇒ **全部流水与账目被清空**（`Schema.kt` 的注释与 `Migrations.kt` 的文件头都写明了这一点）。

```kotlin
// core/database/.../Migrations.kt 追加
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ① 自定义消费平台表
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS user_platforms (
                id TEXT NOT NULL PRIMARY KEY,
                displayName TEXT NOT NULL,
                kind TEXT NOT NULL,
                strongKeywords TEXT NOT NULL,
                mediumKeywords TEXT NOT NULL,
                weakKeywords TEXT NOT NULL,
                packageNames TEXT NOT NULL,
                sortOrder INTEGER NOT NULL,
                archived INTEGER NOT NULL,
                createdAtMillis INTEGER NOT NULL,
                schemaVersion INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        // 索引名必须与 Room 对 Index(value = ["archived"]) 的生成规则一致，
        // 否则 exportSchema 校验报 "migration didn't properly handle" ⇒ 退回 destructive ⇒ 清库
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_user_platforms_archived ON user_platforms (archived)",
        )

        // ② 合并溯源列（可空，无需 DEFAULT）
        db.execSQL("ALTER TABLE transactions ADD COLUMN merged_into_id TEXT")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_transactions_merged_into_id ON transactions (merged_into_id)",
        )
    }
}

// LedgerDatabaseFactory.create(...) 里：
.addMigrations(MIGRATION_4_5, MIGRATION_5_6)
```

**Room 硬性对齐清单（漏一条就退回清库）**：

| 必须一致 | 值 |
|---|---|
| `LedgerDatabase.entities` | 追加 `UserPlatformEntity::class` |
| `LedgerDatabase` 抽象方法 | 追加 `abstract fun userPlatformDao(): UserPlatformDao` |
| `TransactionEntity.indices` | 追加 `Index(value = ["merged_into_id"])` ⇒ 与 SQL 的 `index_transactions_merged_into_id` 同名 |
| `UserPlatformEntity.indices` | `Index(value = ["archived"])` ⇒ `index_user_platforms_archived` |
| `core/database/schemas/.../6.json` | 由 KSP `exportSchema=true` 自动生成，**必须提交入库**（现有 2/3/4/5.json 在库） |

### 2.5 备份兼容（`core:backup`）

| 改动 | 位置 | 做法 |
|---|---|---|
| 导出平台目录 | `BackupManager.buildPayload` | 追加 `"platforms"` 数组（照抄 `categories` 的写法：id/displayName/kind/三组关键词/packageNames/sortOrder/archived/createdAtMillis） |
| 导出合并溯源 | `BackupManager` 的 `LedgerTransaction.toJson()` | 追加 `put("mergedIntoId", mergedIntoId)` |
| 导入平台目录 | `import()` | 读 `platforms` 数组 → `listUserPlatforms()` 合并写入 → **重新注入目录** |
| 导入合并溯源 | `fromJson()` | `optString("mergedIntoId").takeIf{...}` |

**兼容策略**：
- **新 App 读 v5 旧备份**（无 `platforms` / 无 `mergedIntoId`）⇒ `optJSONArray("platforms") == null` 跳过、`optString` 兜底 `null` ⇒ **导入不报错**，此时引用自定义平台的流水会显示「未知平台」（可接受，用户在旧设备上本来也没有这些平台）。
- `BACKUP_VERSION` 5→6；`ImportOutcome.fileVersion/migratedToVersion` 随之体现。

---

## 3. 识别判定表（`KeywordPlatformResolver`）

### 3.1 判定链（按序短路）

| 序 | 条件 | 结果 | 置信度 | 现状 |
|---|---|---|---|---|
| ① | 包名命中 `packageNames` **且该平台 kind = ORDER** | 该平台 | 0.95 | ✅ 已实现（`:94-111`） |
| ② | 包名命中且 kind = PAYMENT/BANK，**且正文有 ORDER 平台命中 ≥0.50** | 正文的 ORDER 平台 | 0.85 `SCORE_ORDER_OVERRIDE` | ✅ 已实现（`:101-110`） |
| ③ | 包名命中且为 PAYMENT/BANK，正文无 ORDER | 包名平台 | 0.95 | ✅ 已实现（`:111`） |
| ④ | 无包名：token 打分 ORDER 命中 ≥0.50 | ORDER 平台 | 该 token 分 | ✅ 已实现（`:114-136`） |
| ⑤ | 无包名：否则最高分 | 最高分平台 | 该 token 分 | ✅ 已实现 |
| ⑥ | **以上全部未命中，且文本命中银行线索** | **`bank`** | **0.35（weak）** | 🆕 **本次新增（约 8 行）** |
| ⑦ | 仍无 | `unknown` | 0.0 | ✅ 已实现 |

**⑥ 的实现要点**（唯一需要动引擎的地方）：

```kotlin
// KeywordPlatformResolver.resolve() 里，在 best.isEmpty() 分支之前插入：
if (best.isEmpty()) {
    // bank 兜底：没有任何其它平台线索时，才允许「银行」'以弱分胜出。
    // 0.35 == UNKNOWN_THRESHOLD ⇒ 不会落 unknown（< 才 unknown），但 < CONFIRM_THRESHOLD
    // ⇒ UI 会打「待确认」角标，用户可一键改成真实平台。语义：知道钱从银行卡走，不知花在哪。
    val bank = PlatformCatalog.find(BANK_ID)
    val hit = bank?.weakKeywords?.firstOrNull { kw -> texts.any { (_, t) -> t.contains(kw, ignoreCase = true) } }
    if (bank != null && hit != null) {
        return PlatformResolution(
            platformId = bank.id,
            confidence = SCORE_WEAK,          // 复用 0.35
            candidates = listOf(PlatformMatch(bank.id, SCORE_WEAK, "命中银行线索「$hit」")),
            ambiguous = false,
        )
    }
    return PlatformResolution(PlatformCatalog.UNKNOWN_ID, 0f, emptyList(), false)
}
```

> **为什么门槛是"完全没有其它线索"**：银行线索（"尾号/信用卡"）几乎出现在**每一条**银行短信里。若不设这个前提，一条"尾号1234 消费 88 元（支付宝）"会同时命中 bank(0.35) 与 alipay(0.90) —— 好在 0.90 > 0.35，`bank` 本来也不会赢。但"完全没有其它命中"这个前提让意图更明确、更可测，也不依赖分数比大小的隐式行为。

### 3.2 需要新增的通知包名清单（可核验性优先）

| 平台 | 包名 | 状态 | 依据 |
|---|---|---|---|
| 微信 | `com.tencent.mm` | ✅ 已在用 | `NotificationRule.kt:47`（`PKG_WECHAT`） |
| 支付宝 | `com.eg.android.AlipayGphone` | ✅ 已在用 | `NotificationRule.kt:48`（`PKG_ALIPAY`） |
| 美团 | `com.sankuai.meituan` | ✅ 已在用 | `Platform.kt:131` |
| 拼多多 | `com.xunmeng.pinduoduo` | ✅ 已在用 | `Platform.kt:141` |
| 抖音 | `com.ss.android.ugc.aweme` | ✅ 已在用 | `Platform.kt:151` |
| 淘宝 | `com.taobao.taobao` | ✅ 已在用 | `Platform.kt:161` |
| 美团外卖 | `com.sankuai.meituan.takeoutnew` | ⚠️ **待核实** | 凭印象，未验证 |
| 大众点评 | `com.dianping.v1` | ⚠️ **待核实** | 凭印象，未验证 |
| 云闪付 | `com.unionpay` | ⚠️ **待核实** | 凭印象，未验证 |
| 数字人民币 | —— | ⚠️ **待核实** | 不确定，**不填** |
| 各银行 App | —— | ⚠️ **待核实** | 包名零散，**本期不逐个收录** |

**核实方法（给工程师照做，别凭记忆写死）**：

```bash
# ① 列出设备上相关 App 的真实包名
adb shell pm list packages | grep -i -E "union|dcep|pbcec|icbc|cmb|dianping|takeout"
# ② 或从一条真实通知反查来源包名
adb shell dumpsys notification --noredact | grep -E "pkg=|packageName"
```

> **硬要求**：标 `待核实` 的包名**必须先留空**（`packageNames = emptySet()`），靠关键词兜底；核实后再补。
> 理由：**写错包名 = 整类通知被恒定错判平台**，比"暂时不识别"危害大得多。这与项目既有原则一致——`Platform.kt:26-27` 明确「宁可 unknown，不可瞎猜」。

### 3.3 关于「ORDER 优先于 PAYMENT」是否足够

**识别层：足够了。** 规则 1/2 已完整覆盖「淘宝下单 + 支付宝付款」「美团下单 + 微信支付」。判定逻辑见 `KeywordPlatformResolver.kt:94-144`，注释里也写清了这个语义。

**但只到识别层是不够的** —— 需求 3 要的是"**重复出现时合并为单条**"，而合并层现在**完全不看平台**（`IngestPipeline.kt:152`）。所以真正的增量在 §4。

---

## 4. 去重优先级（本次核心）

### 4.1 「同一时间、同一金额」的判定（决策表）

| 维度 | 取值 | 理由 |
|---|---|---|
| 时间容差 | **复用 `DEFAULT_WINDOW = 3 分钟`**（`LedgerDuplicateResolver.kt:93`） | 已有实现与单测；真实通知/短信的时差就是几十秒级。**放宽会显著提高误合并** |
| 是否要求「同一天」 | **不要求** | 反例：23:59:30 的微信通知 + 00:00:10 的银行短信是**同一笔**，跨了午夜。要求同天会**漏合并** |
| 金额比较 | **带符号**（`amountMinor` 原值，负=支出 / 正=收入与退款） | 现状即如此（`fingerprintOf` 用 `txn.amountMinor`）。**这天然挡住了「退款与原单」误合并**（+88 vs −88 指纹不同），是必须保留的正确设计 |
| 商户 | Tier-1 必须相同（归一化后）；Tier-2 见 §4.2 | 保护"同金额不同商户" |

### 4.2 ⚠️ 关键发现：只加优先级**根本不会生效**（必须补一条匹配通道）

现有指纹 = `sha256(金额 | 归一化商户)`（`LedgerDuplicateResolver.kt:33-42`）。
而需求场景里两条记录的**商户名通常不同**：

| 来源 | 原始商户 | 归一化后 |
|---|---|---|
| 美团 App 通知 | `美团外卖` | `美团外卖` |
| 银行扣款短信 | `财付通` | `财付通` |
| 银行扣款短信（另一种） | `美团` | `美团` |

⇒ 指纹不同 ⇒ **`findByFingerprintNear` 根本查不到对方** ⇒ 平台优先级**永远没机会执行**。

**因此设计两级匹配**：

| 级别 | 匹配键 | 适用场景 | 是否新增 |
|---|---|---|---|
| **Tier-1 指纹精确** | `fingerprint` + 3 分钟窗口 | 商户名一致（同一平台被重复抓取、或银行短信与通知恰好同名） | 已有 |
| **Tier-2 层级互补** | `amountMinor` 相等 + 3 分钟窗口 + **平台层级互补** | 商户名不同但描述同一笔（美团通知 ↔ 银行短信） | 🆕 **新增**（**实现期修订：原含「不同 `sourceId`」，见 §10-⑨**） |

**Tier-2 的护栏（这是防误合并的关键，必须逐条实现）**

记 `tier(p) = priorityOf(p).rank ∈ {ORDER=4, PAYMENT=3, E_WALLET=2, BANK=1, NONE=0}`。

| 组合 | 自动合并？ | 理由 |
|---|---|---|
| 恰好一侧 `ORDER`（`ORDER↔PAYMENT` / `ORDER↔E_WALLET` / `ORDER↔BANK` / `ORDER↔NONE`） | ✅ | 消费场所 + 资金通道，一笔消费的上下游 —— **正是需求要的场景** |
| `ORDER ↔ NONE`（unknown） | ✅ | 美团通知 + 银行短信未识别出平台 |
| `PAYMENT ↔ E_WALLET` | ⚖️ **降级为待确认** | 一笔消费只走一个支付通道（两者互斥），但保守起见浮出给用户（**实现期修订：见 §10-⑩**） |
| `PAYMENT ↔ BANK` | ⚖️ **默认不自动合并，降级为待确认** | 「微信支付 88」+「银行卡扣 88」**可能是同一笔**（微信绑的这张卡），**也可能是两笔**（先充值、再消费）。证据不足 |
| `E_WALLET ↔ BANK` | ⚖️ **降级为待确认** | **用户拍板**：数币 / 云闪付与银行卡边界模糊，宁可保守，也不静默吞掉真实消费（**实现期修订：见 §10-⑩**） |
| 同层级 + **同 id**（`wechat↔wechat` / `digital_rmb↔digital_rmb` / `bank↔bank`） | ⚖️ **降级为待确认** | 同一类通道被重复抓取 ⇒ 疑似同一笔，但银行短信常无商户名、无法排除"同金额两笔真实扣款" ⇒ 交用户（`bank↔bank` 见 **§10-⑤**） |
| 同层级 + **不同 id**（微信↔支付宝 / 数币↔云闪付 / 美团↔淘宝） | ❌ | 一次消费只有一个通道 / 两个消费场所 = 两笔消费（**连候选都不是**） |
| `NONE ↔ NONE` / 任一资金通道 `↔ NONE` | ❌ | 无层级信息 / 无互补证据，不合并 |

> `PAYMENT ↔ BANK` / `PAYMENT ↔ E_WALLET` / `E_WALLET ↔ BANK` 不自动合并是**有意的保守**：三者都是"钱从不同通道出去"，真实歧义。降级为"待你确认"比误合并安全，与项目既有取舍一致（`LedgerDuplicateResolver.kt:21-24`：「宁可多一步确认，也不静默吞掉真实消费」）。

> ⚠️ **实现期修订**：本护栏表在实现期加入 `E_WALLET` 中间层并据用户拍板重排（见 §10-⑩）；此外 Tier-1 另补了「层级 / 门店」两道闸、Tier-2 另补了「权威来源」闸。完整偏离清单与理由见 **§10 实现期修订记录**（请连同该节一起阅读，勿只按本表实现）。

**Tier-2 查询路径与性能**

```kotlin
// TransactionDao 新增
@Query("""
    SELECT * FROM transactions
    WHERE amountMinor = :amountMinor
      AND occurredAtMillis BETWEEN :fromMillis AND :toMillis
      AND status <> 'MERGED' AND status <> 'IGNORED'
      AND id <> :excludeId
    ORDER BY occurredAtMillis DESC
""")
suspend fun findByAmountWithin(
    amountMinor: Long, fromMillis: Long, toMillis: Long, excludeId: String,
): List<TransactionEntity>
```

| 关注点 | 结论 |
|---|---|
| 索引 | **不需要新索引**。3 分钟窗口下，查询先用 `occurredAtMillis` 索引（`TransactionEntity.indices` 已有）把范围缩到个位数量级，再过滤金额 |
| O(n²) 风险 | **无**。仅在 **Tier-1 未命中** 时才执行 Tier-2 ⇒ 不增加既有热路径成本 |
| 未来若放宽到"同一天" | 那时再补复合索引 `Index(value = ["amountMinor", "occurredAtMillis"])` ⇒ 名 `index_transactions_amountMinor_occurredAtMillis`（**索引名必须与 Room 生成规则一致**） |

### 4.3 优先级如何落到代码

**契约扩展**（`core:model/.../Spi.kt`，全部带默认值 ⇒ 不破坏既有构造点与测试）：

```kotlin
enum class MatchTier { FINGERPRINT, COMPLEMENTARY }

data class DuplicateCandidate(
    val txnId: String,
    val score: Int,
    val crossSource: Boolean = true,
    // ↓↓↓ 新增
    val platformId: String = PlatformCatalog.UNKNOWN_ID,
    val priorityRank: Int = 0,
    val platformSource: PlatformSource = PlatformSource.AUTO,
    val tier: MatchTier = MatchTier.FINGERPRINT,
)
```

**主记录裁决 —— 纯函数放 `core:model`（可 JVM 单测）**：

```kotlin
// core:model .../dedup/DedupPriority.kt
object DedupPriority {
    data class Choice(val primaryId: String, val mergedId: String, val reason: String)

    /**
     * 决定合并后**谁留下**。规则按序短路：
     *  R0 用户权威：任一方 platformSource == USER ⇒ 该方为 primary；双方都 USER ⇒ 保留 existing
     *  R1 层级高者胜：priorityRank 大者为 primary（美团 3 > 微信/支付宝 2 > 银行 1）
     *  R2 同层级：保留 existing（不改写历史，避免主记录反复易主导致 UI 抖动）
     *  R3 兜底：保留 existing
     */
    fun choosePrimary(
        incomingId: String, incomingRank: Int, incomingIsUser: Boolean,
        existingId: String, existingRank: Int, existingIsUser: Boolean,
    ): Choice = when {
        existingIsUser && !incomingIsUser ->
            Choice(existingId, incomingId, "已存在记录由用户指定，保留用户值")
        incomingIsUser && !existingIsUser ->
            Choice(incomingId, existingId, "新记录由用户指定，保留用户值")
        else -> if (incomingRank > existingRank) {
            Choice(incomingId, existingId, "平台层级更高（$incomingRank > $existingRank）")
        } else {
            Choice(existingId, incomingId, "层级不高于已存在记录，保留历史（$existingRank ≥ $incomingRank）")
        }
    }
}
```

**`IngestPipeline` 的改动**（`IngestPipeline.kt:148-154`）：

```kotlin
// 改前：无条件让"已存在的那条"当主记录
// duplicateResolver.merge(duplicates.first().txnId, listOf(final.id))

// 改后：
val dup = duplicates.first()
val choice = DedupPriority.choosePrimary(
    incomingId = final.id,
    incomingRank = priorityOf(final.platformId).rank,
    incomingIsUser = final.platformSource == PlatformSource.USER,   // ingest 时恒为 AUTO
    existingId = dup.txnId,
    existingRank = dup.priorityRank,
    existingIsUser = dup.platformSource == PlatformSource.USER,
)
duplicateResolver.merge(choice.primaryId, listOf(choice.mergedId))
Outcome.MergedInto(final.id, choice.primaryId)
```

**`LedgerDuplicateResolver.findDuplicates` 的改动**：
1. 先跑 Tier-1（现状不动），把 `tier = FINGERPRINT`、`platformId/priorityRank/platformSource` 填进 `DuplicateCandidate`；
2. **Tier-1 为空时**再跑 Tier-2：`repository.findByAmountWithin(...)` → 逐条按 §4.2 护栏表判定 → 通过的标 `tier = COMPLEMENTARY`；
3. Tier-2 的 `score` 建议 = `50`（低于 Tier-1 的 0~100 时间分档之上？**取 50，语义：可信但低于精确指纹**）—— 具体值单测钉死即可。

### 4.4 冲突处理规则（完整表）

| 冲突情形 | 规则 | 依据 |
|---|---|---|
| 层级不同（ORDER vs PAYMENT/BANK） | 层级高者当 primary | 需求 3 明确要求 |
| 层级相同 | 保留 **existing** | 不改写历史；避免主记录易主导致 UI 抖动 |
| 任一方 `platformSource == USER` | 该方当 primary，**豁免被覆盖与被合并掉** | `PlatformSource` 的既定语义（`Platform.kt:23-29`「用户改过即权威」） |
| 双方都 USER | 保留 existing | R2 |
| 同金额、不同商户、**层级不互补** | **不合并** | 保护"同金额不同商户"的真实消费 |
| `PAYMENT ↔ BANK` | **不自动合并**，转「待你确认」 | 唯一真实歧义组合 |
| 退款 vs 原单 | **不合并** | 指纹含符号 |
| `amountMinor == 0` | **不合并** | `isAutoMergeSafe` 既有规则（`:45`） |
| 同 `sourceId` 的重复 | 沿用既有：不自动合并，降级待确认 | `IngestPipeline.kt:150-159` |

### 4.5 合并后信息保留（需求 3.3）

**现状缺口**：`merge()` 只 `markStatus(MERGED)`（`LedgerDuplicateResolver.kt:67-70`），**没有 primary↔merged 的链接** ⇒ 合并后查不出"谁吸收了我"，`unmerge` 也只能盲翻状态。

**设计三件事**：

**① 新增列 `merged_into_id`**（已在 §2.4 迁移里加），合并时写入：

```kotlin
// LedgerRepository 新增；Dao 新增
@Query("UPDATE transactions SET status = :status, mergedIntoId = :primaryId WHERE id = :id")
suspend fun updateMergeState(id: String, status: String, primaryId: String?)
```
合并后即可查「合并组」：`SELECT * FROM transactions WHERE mergedIntoId = :primaryId` ⇒ UI 可展示「已合并 2 条：微信、银行卡」。

**② 字段继承 —— 只补 primary 的空白，绝不覆盖非空**：

| 字段 | 规则 | 说明 |
|---|---|---|
| `counterparty` | primary 为空 → 继承 merged 的非空值 | **Tier-2 的主要价值**：银行短信常无商户，美团通知有「美团外卖」 ⇒ 主记录补上真实商户 |
| `note` | 同上 | |
| `platformId` | primary 为 `unknown`，或 `priorityRank(primary) < priorityRank(merged)` 且 primary 的 `source == AUTO` → 继承 merged 的平台 | 与 §4.3 裁决一致；**`platformSource` 保持 `AUTO`**（这不是用户选的） |
| `categoryId` | primary 为 `null` → 继承 | |
| `rawTextSealed` / `sourceRef` / `platformConfidence` | **不动** | 保留 primary 的原始证据；merged 行仍在库，证据不丢 |

**③ merged 行原样保留**（不删、不改字段），只置 `status = MERGED` + `mergedIntoId`。
⇒ 「这笔记了两次，分别来自微信和银行卡」是**数据天然可查**（`mergedIntoId` 反查 + 各行自己的 `platformId` 仍在），**不需要把"微信/银行卡"字符串拼进主记录**，避免污染主记录口径。

### 4.6 与 `MERGED` / `unmerge` 的配合

| 操作 | 行为 |
|---|---|
| `findDuplicates` | 排除 `status == MERGED`（已有 `:56`）**以及** `mergedIntoId != null` 的记录，双重保证被吸收的记录不再当候选 |
| `unmerge(mergedId)` | 置回 `RAW` + 清空 `mergedIntoId`；**不自动回滚**继承到 primary 的字段（用户可能在合并后又编辑过 primary），但返回提示「已恢复该条；主记录的商户/平台可能仍含继承值，请核对」 |
| `mergeGroupOf(primaryId)` | 新增查询，供 UI 展示合并组与逐条撤销 |

---

## 5. 文件清单（相对路径 + 要做什么）

### core:model

| 路径 | 动作 |
|---|---|
| `core/model/src/main/java/com/autoledger/core/model/platform/Platform.kt` | ① `PlatformKind` +`BANK`；② 新增 `PlatformPriority` + `PlatformKind.toPriority()` + `priorityOf()`；③ `BUILT_IN` +`bank`/`digital_rmb`/`unionpay`；④ 新增 `unregister()`；⑤ `all()` 加缓存 + 失效；⑥ 常量 `BANK_ID = "bank"` |
| `core/model/src/main/java/com/autoledger/core/model/dedup/DedupPriority.kt` | **新增**：`DedupPriority.choosePrimary(...)` + `Choice` |
| `core/model/src/main/java/com/autoledger/core/model/Spi.kt` | `DuplicateCandidate` +4 字段（带默认值）；新增 `enum MatchTier`；`LedgerRepository` 新增 `listUserPlatforms()` / `upsertUserPlatform()` / `archiveUserPlatform()` / `findByAmountWithin()` / `setMergeState()` / `mergeGroupOf()` |
| `core/model/src/main/java/com/autoledger/core/model/Schema.kt` | `DATABASE_VERSION` 5→6、`BACKUP_VERSION` 5→6 |
| `core/model/src/main/java/com/autoledger/core/model/UserPlatform.kt` | **新增**：`UserPlatform(id, displayName, kind, strong/medium/weakKeywords, packageNames, sortOrder, archived, createdAtMillis, schemaVersion)` + `fun UserPlatform.toPlatformEntry(): PlatformEntry` + `fun newUserPlatformId(): String = "user:" + UUID.randomUUID()` |

### core:database

| 路径 | 动作 |
|---|---|
| `core/database/.../Entities.kt` | 新增 `UserPlatformEntity`（+`Index("archived")`）；`TransactionEntity` +`mergedIntoId: String?` + `Index("merged_into_id")` |
| `core/database/.../Daos.kt` | 新增 `UserPlatformDao`（upsert / listActive / listAll / archive）；`TransactionDao` +`findByAmountWithin` / `updateMergeState` / `mergeGroupOf` |
| `core/database/.../LedgerDatabase.kt` | `entities` +`UserPlatformEntity::class`；+`abstract fun userPlatformDao()` |
| `core/database/.../Migrations.kt` | 新增 `MIGRATION_5_6`（SQL 见 §2.4）；`.addMigrations(MIGRATION_4_5, MIGRATION_5_6)` |
| `core/database/.../repository/Mappers.kt` | `UserPlatformEntity ↔ UserPlatform` 双向映射（关键词 List↔`\n` 拼接）；`TransactionEntity.mergedIntoId` 两端映射 |
| `core/database/.../repository/RoomLedgerRepository.kt` | 实现 §5 Spi 里新增的 6 个方法 |
| `core/database/schemas/.../6.json` | KSP 自动生成后**提交入库** |

### core:backup

| 路径 | 动作 |
|---|---|
| `core/backup/.../BackupManager.kt` | `buildPayload` +`platforms` 数组；`toJson`/`fromJson` +`mergedIntoId`；`import` 后**重新注入目录**（`resetExtras()` + 全量 register） |

### feature:platform / feature:dedup / feature:capture

| 路径 | 动作 |
|---|---|
| `feature/platform/.../KeywordPlatformResolver.kt` | 新增规则 ⑥ `bank` 兜底（§3.1，约 8 行）；`BANK_ID` 常量引用 |
| `feature/dedup/.../LedgerDuplicateResolver.kt` | `findDuplicates`：Tier-1 填 4 个新字段；新增 Tier-2 分支（`findByAmountWithin` + §4.2 护栏表）；`merge()` 写入 `mergedIntoId` |
| `feature/capture/.../IngestPipeline.kt` | 用 `DedupPriority.choosePrimary` 替换 `:152`；合并后执行 §4.5 的字段继承 |
| `feature/capture/.../notify/NotificationRule.kt` | （可选）若核实出美团外卖/云闪付包名，加入规则包以提升金额解析覆盖率 |

### app

| 路径 | 动作 |
|---|---|
| `app/.../di/AppContainer.kt` | `bootstrap()` 里 **`startCaptureLoop()` 之前**读 `listUserPlatforms()` → 逐个 `PlatformCatalog.register(toPlatformEntry())` |
| `app/.../ui/stores/AppStores.kt` | 新增 `UserPlatformStore`（list / add / edit / archive；每次写成功后 `register/unregister`）；`PlatformShareMetric` 无需改 |
| `app/.../ui/screens/PlatformManageScreen.kt` | **新增**：自定义平台管理页（列表 + 新增/编辑表单：名称、角色 kind、三档关键词、包名、停用） |
| `app/.../ui/screens/CaptureAndSettings.kt` | 设置页加「消费平台管理」入口（照抄现有「订单与退款」入口写法 `:221-229`） |
| `app/.../ui/components/TxnEditing.kt` | 平台选择器里把自定义平台一并列出（`PlatformCatalog.all()` 已自动包含）；合并组展示与「撤销合并」入口 |

---

## 6. 任务列表（有序，5 个）

### T1 — 领域契约与目录扩展（core:model）

**依赖**：无 ｜ **P0** ｜ 文件：`Platform.kt`、`DedupPriority.kt`(新)、`Spi.kt`、`Schema.kt`、`UserPlatform.kt`(新)、`PlatformCatalogTest.kt`、`DedupPriorityTest.kt`(新)

① `PlatformKind` +BANK、`PlatformPriority` + 派生函数 → ② 目录 +3 条（bank/digital_rmb/unionpay）+ `unregister()` + `all()` 缓存 → ③ `DedupPriority.choosePrimary` → ④ `Spi.kt` 契约扩展 → ⑤ `Schema.kt` 版本 6 → ⑥ `UserPlatform` 领域类型 + `toPlatformEntry()`
**验收**：`:core:model` 编译过；`choosePrimary` 与优先级映射单测全绿；`priorityOf("不存在") == NONE` 不抛异常。

### T2 — 持久化与备份（core:database, core:backup）

**依赖**：T1 ｜ **P0** ｜ 文件：`Entities.kt`、`Daos.kt`、`LedgerDatabase.kt`、`Migrations.kt`、`Mappers.kt`、`RoomLedgerRepository.kt`、`BackupManager.kt`、`schemas/6.json`

① 实体/DAO/数据库注册 → ② `MIGRATION_5_6`（**索引名逐字符对齐 §2.4 清单**）→ ③ 映射 → ④ Repository 实现 → ⑤ 备份导出/导入 + 导入后重新注入目录
**验收**：真机从 v5 升 v6 **数据不丢**（造几笔流水，升级后仍在）；`user_platforms` 表与 `index_transactions_merged_into_id` 存在；v5 旧备份可导入。

### T3 — 识别与去重引擎（feature:platform, feature:dedup, feature:capture）

**依赖**：T2 ｜ **P0** ｜ 文件：`KeywordPlatformResolver.kt`、`LedgerDuplicateResolver.kt`、`IngestPipeline.kt` + 3 个测试文件

① resolver 加 bank 兜底 → ② `findDuplicates` Tier-1 补 4 字段 → ③ 新增 Tier-2（含 §4.2 护栏表逐条） → ④ `merge()` 写 `mergedIntoId` → ⑤ `IngestPipeline` 用 `choosePrimary` + 字段继承
**验收**：§7 测试清单中 Tier-2 与优先级相关用例全绿；**反例全绿**（不该合并的一条都不能合）。

### T4 — App 装配与自定义平台 UI（app）

**依赖**：T3 ｜ **P1** ｜ 文件：`AppContainer.kt`、`AppStores.kt`、`PlatformManageScreen.kt`(新)、`CaptureAndSettings.kt`、`TxnEditing.kt`

① 启动注入目录（**必须在 `startCaptureLoop()` 之前**）→ ② `UserPlatformStore` → ③ 管理页 + 设置入口 → ④ 编辑对话框列出自定义平台与合并组
**验收**：新增一个自定义平台「京东」→ 立刻能在流水的平台选择器里看到 → 重启 App 仍在 → 停用后不再出现在候选、但历史流水仍显示「京东」。

### T5 — 验证、回归与文档

**依赖**：T4 ｜ **P1** ｜ 文件：测试文件若干 + `CHANGELOG.md`

① 补齐 §7 全部用例 → ② 手动冒烟（§7.3）→ ③ 备份往返测试 → ④ CHANGELOG
**验收**：全量测试 0 失败；手动冒烟 8 条通过。

---

## 7. 测试用例清单（可执行）

### 7.1 识别（`KeywordPlatformResolverTest`）

| # | 输入 | 期望 |
|---|---|---|
| R1 | 美团包名 + 正文「已通过支付宝支付 88 元」 | `meituan`（ORDER 覆盖 PAYMENT 型包名），confidence 0.85，`ambiguous=false` |
| R2 | 包名微信 + 正文「向美团外卖付款 25 元」 | `meituan`（规则 ①：正文有 ORDER ≥0.50） |
| R3 | 无包名，正文「支付宝付款 25.80 元」 | `alipay` |
| R4 | 无包名，正文「云闪付支付 30 元」 | `unionpay` |
| R5 | 无包名，正文「数字人民币支付 12 元」 | `digital_rmb` |
| R6 | 无包名，正文「尾号1234 消费 88.00 元」 | `bank`，confidence 0.35，**< `CONFIRM_THRESHOLD`（UI 会打角标）** |
| R7 | 正文「尾号1234 消费 88 元」+「支付宝」 | `alipay`（bank 兜底**不得**生效，因已有其它命中） |
| R8 | 正文「今天天气不错」 | `unknown`，confidence 0，候选空 |
| R9 | 自定义平台「京东」注册后，正文「京东支付 50 元」 | `user:<uuid>`（验证目录注入生效）；测后 `resetExtras()` |

### 7.2 去重与优先级（`LedgerDuplicateResolverTest` / `DedupPriorityTest` / 集成测试）

| # | 场景 | 期望 |
|---|---|---|
| D1 | 美团通知(ORDER) + 银行卡短信(unknown) 同金额 3 分钟内 | **合并**，primary = 美团那条 |
| D2 | 美团通知(ORDER) + 微信通知(PAYMENT) 同金额同时间 | **合并**，primary = 美团 |
| D3 | 微信支付(PAYMENT) + 银行卡(BANK) 同金额 | **不自动合并**，进「待确认」 |
| D4 | 微信(PAYMENT) + 支付宝(PAYMENT) 同金额 | **不合并**（层级相同且都是 PAYMENT） |
| D5 | 美团(ORDER,商户「美团外卖」) + 淘宝(ORDER,商户「淘宝」) 同金额同时间 | **不合并**（两个 ORDER = 两笔消费） |
| D6 | 同商户同金额同时间（指纹一致） | 走 Tier-1，合并（现状行为不变，防回归） |
| D7 | **同一家店 3 分钟内两笔真实消费**（同商户同金额同 source） | **不自动合并**（既有关键反例，必须继续绿） |
| D8 | 同金额**不同商户**且层级不互补 | **不合并** |
| D9 | 支出 −88 元 + 退款 +88 元 | **不合并**（指纹含符号） |
| D10 | `amountMinor == 0` | **不合并** |
| D11 | existing 的 `platformSource == USER`，incoming 层级更高 | **existing 仍为 primary**（用户权威豁免） |
| D12 | 层级相同（都非 USER） | **保留 existing** |
| D13 | 合并后 `mergedIntoId` = primary id，可反查合并组 | ✅ |
| D14 | 合并继承：primary(银行短信, 商户空) + merged(美团, 商户「美团外卖」) | primary 的 `counterparty` 被补为「美团外卖」，`platformId` 被补为 `meituan`，`platformSource` 仍是 `AUTO`，`rawTextSealed`/`sourceRef` **未变** |
| D15 | `unmerge` | merged 行回到 `RAW`、`mergedIntoId` 清空 |
| D16 | 时间差 3 分 01 秒 | **不合并**（超出窗口的边界） |
| D17 | 23:59:30 与 00:00:10（跨午夜，2 秒差） | **合并**（证明不要求同一天） |
| D18 | 已被 MERGED 的记录不再作为候选 | ✅ |

### 7.3 手动冒烟步骤

1. 从 v5 库升级 → 打开 App，历史流水仍在、平台列显示「未知」（不打角标也正常）。
2. 造一条美团通知 + 一条银行短信（同金额、间隔 <1 分钟）→ 采集箱/账单里应只剩 **1 条**，平台显示「美团」。
3. 打开该条 → 平台选择器里能看到「银行卡」「数字人民币」「云闪付」「未知」+ 自定义平台。
4. 设置 → 消费平台管理 → 新增「京东」（角色=下单平台）→ 返回流水编辑，确认「京东」可选；**杀进程重开**，仍在。
5. 停用「京东」→ 编辑对话框不再出现；但之前记过的「京东」流水**仍显示「京东」**（不是「未知平台」）。
6. 一条「尾号1234 消费 88 元」的银行短信 → 平台显示「银行卡」+ 待确认角标。
7. 导出备份 → 清空全部 → 导入 → 自定义平台与合并关系都回来。
8. 在「发现」页按平台筛选，确认「银行卡 / 云闪付 / 数字人民币」可选、统计数字正确。

---

## 8. 风险点

| # | 风险 | 后果 | 规避 |
|---|---|---|---|
| **R1** | **漏写 `MIGRATION_5_6` 或索引名不一致** | Room 找不到迁移路径 ⇒ 触发 `fallbackToDestructiveMigration` ⇒ **全库流水被清空** | 严格按 §2.4 的"硬性对齐清单"逐条核对；`schemas/6.json` 必须提交；升级前造数据做验证 |
| **R2** | **Tier-2 护栏写松**（例如只判"同金额+同时间"） | **吞掉真实消费**：同一家店 3 分钟内两笔、或两个不同商户同金额被合并成一条 | 必须实现 §4.2 整张护栏表；**D5/D7/D8 三个反例测试是硬门槛**；`PAYMENT↔BANK` 坚持不自动合并 |
| **R3** | 以为"加了优先级就能合并" | 需求场景（美团 vs 银行短信商户名不同）**指纹根本不同**，优先级永不执行 ⇒ 功能"看起来做了但没效果" | 必须实现 **Tier-2**（`findByAmountWithin`）；用 D1/D2 用例证明端到端生效 |
| **R4** | `platformSource == USER` 的记录被合并掉或平台被覆盖 | **用户的手动修改被自动流程悄悄改掉** —— 这正是 `PlatformSource` 要防的事 | `DedupPriority` R0 先行；D11 用例钉死 |
| **R5** | `PlatformCatalog` 是进程级可变单例 | ① 测试间互相污染；② 并行测试下不确定 | 每个用自定义平台的测试必须 `resetExtras()`；`resetExtras()` 一并清缓存 |
| **R6** | 自定义平台注册晚于采集循环启动 | 冷启动窗口内到达的通知用**不含自定义平台**的目录识别 ⇒ 那几笔落 unknown | 注入必须放在 `bootstrap()` 里 **`startCaptureLoop()` 之前**（与现有 seeds 同处） |
| **R7** | 自定义平台被**硬删除** | 历史流水的 `platformId` 变成孤儿 ⇒ 显示「未知平台」，用户以为数据坏了 | 只做**软删除**（`archived`）；`displayNameOf` 对归档条目仍返回正确名称 |
| **R8** | 把"待核实"的包名凭记忆写死 | 整类通知被**恒定错判**平台（比不识别更糟） | 留空 + 关键词兜底；按 §3.2 的 adb 方法核实后再补 |
| **R9** | `PlatformCatalog.all()` 每次重排序 | 列表每一行渲染都触发一次排序；加自定义平台后更明显 | 按 §2.3 加缓存 + 在 register/unregister/reset 时失效 |
| **R10** | Tier-2 引入 O(n²) | 历史账单批量导入变慢 | Tier-2 仅在 Tier-1 未命中时执行；窗口 3 分钟走 `occurredAtMillis` 索引；**不新增索引**已够用 |
| **R11** | `unmerge` 后主记录仍带继承值 | 用户困惑"撤销了怎么商户还在" | UI 明确提示（§4.6）；这是**有意决策**（避免覆盖用户在合并后的编辑） |
| **R12** | 截图/文档把自定义关键词当常量写死 | 用户改了关键词但识别不变 | 关键词**只从目录（含 DB 注入）读**，引擎里不得出现硬编码关键词 |

---

## 9. 一句话交付摘要

> **识别层已经好了**（`PlatformKind.ORDER > PAYMENT` 早实现），本次真正的增量是**去重层**：现有指纹用「金额+归一化商户」，而美团通知与银行短信的**商户名天然不同** ⇒ 光加平台优先级**永远不会被触发**。所以必须补 **Tier-2 层级互补匹配**（同金额 + 3 分钟 + 跨 source + 一方 ORDER 一方 ≤PAYMENT），命中后按 `PlatformPriority`（美团 3 > 微信/支付宝 2 > 银行卡 1）裁决主记录，并用新增的 `merged_into_id` 把"合并了哪几条、分别来自哪个平台"变成**可查数据**而不是字符串拼接。数据侧要补 `user_platforms` 表（**软删除**，保证历史不塌成"未知平台"）与 `MIGRATION_5_6`——**迁移与索引名写错会清库**，是本次最高风险。

---

## 10. 实现期修订记录

> **本节只增不改**：§1–§9 的正文除 §4.2 的判定表按最终口径更新外（新增 `E_WALLET` 层、`BANK ↔ BANK` 行回退为单行；Tier-2 匹配键去掉「不同 `sourceId`」），其余**保持原样**；§2.2 的条目快照保留原设计，以 §10-⑩ 为准。
> 目的正是让读者看出「**原设计是什么、后来为什么改**」—— 只改判定表会让文档显得"一开始就设计对了"，
> 那会抹掉迭代痕迹，也会掩盖真实踩过的坑。下列 ①–⑩ 按**发现顺序**排列，每条 = 偏离内容 + 触发它的反例 + 理由。

### ① Tier-1 也必须有层级护栏（原设计**完全未提**）

- **原设计**：Tier-1（指纹精确）只要「跨渠道 + 商户非空」就允许自动合并，**不看平台层级**。
- **反例**：一笔微信通知 + 一笔支付宝通知，同一家店、同金额、3 分钟内、商户名恰好一致（指纹相同）⇒ 被**静默合并**。而一次消费不可能同时走两个支付通道 ⇒ 这是**两笔真实消费**，合并即吞账。
- **修订**：新增纯函数 `tierOneAllowsAutoMerge`，只拒绝「**同层级 且 不同通道**」（微信 vs 支付宝、美团 vs 淘宝、`NONE`↔`NONE`）。
- **关键分寸**：**不能**笼统地"同层级一律拒绝" —— `BANK` 目录里只有**唯一 ID `bank`**，银行短信与银行 App 通知**都是 `bank`**，一律拒会把「同一条 bank 通道被重复抓取 ⇒ 同一笔」也拒掉，**回退 1.1.2 修过的 Bug 2**。

### ② `MERGED` / `IGNORED` 行必须进备份（原设计只导出"可见行"）

- **原设计/初版实现**：备份用 `repo.listAll(includeTransfers = true)`，SQL 层排除 `MERGED` / `IGNORED`。
- **反例**：换机备份再导入后，「这笔分别来自微信和银行卡」的**合并链**与用户"忽略这笔"的决定**全部丢失**。
- **修订**：新增专用查询 `listAllIncludingHidden()` / `listAllForBackup()`（**只给备份用**，展示路径继续排除）；导出带上 `MERGED`/`IGNORED` 行，导入时原样还原 `status` 与 `mergedIntoId`。

### ③ 同品牌不同门店消歧（原设计以为"抹括号 + 层级护栏"能兜住）

- **原设计**：`normalize()` 抹括号 —— 这是**有意**的（银行短信写「星巴克(国贸店)」、微信通知写「星巴克」，不抹则同一笔永远合不上）。原判定把"同品牌不同门店同金额"的残留风险记为**可接受**，理由「两侧平台通常同层级或都是 unknown，护栏能拦住」。
- **反例（该理由被证伪）**：微信支付通知「中石化(朝阳站)」(PAYMENT) + 银行 POS 短信「中石化(海淀站)」(BANK)，同金额 3 分钟内 ⇒ 抹括号后指纹相同，但两侧**层级不同** ⇒ 层级护栏**放行** ⇒ **吞掉一笔真实消费**。风险**并不**只落在"同层级"上。
- **修订**：新增纯函数 `branchSuffixesConflict` —— **两侧原始商户名的括号内容都非空且不同 ⇒ 不自动合并**（降级待确认）。**`normalize()` 一个字都不改**（抹括号是 Tier-1 同笔识别的核心能力），只在自动合并出口用**原始串**补一道闸；兼容全/半角括号、大小写、多段门店（顺序无关）。

### ④ Tier-2 必须排除"权威 / 导入"来源（原设计**未考虑**）

- **反例**：12:00 用户**手工**记「菜市场 现金 25 元」（`sourceId = manual`、平台 `unknown`）；12:01 美团外卖通知 25 元（`ORDER`）。商户不同 ⇒ 指纹不同 ⇒ Tier-1 不命中；但满足 Tier-2 的 `ORDER ↔ NONE` 互补 ⇒ **被静默合并**，用户的现金消费被吞。
- **修订**：新增纯函数 `noneSideIsAuthoritative` —— Tier-2 的 `unknown(NONE)` 一侧若来自**权威 / 导入来源**（`manual` / `bill_import`）⇒ 不自动合并。因为「未识别出平台」只说明**没认出来**，绝不等于"某笔订单的银行侧"。
- **附带**：来源 ID 统一到 `core:model` 的 `CaptureSourceIds`（`feature:dedup` 不依赖 `feature:capture`，故必须放在两者都依赖的 `core:model`；4 个 `CaptureSource` 实现改为引用它）。

### ⑤ `BANK ↔ BANK` 在 Tier-2 由 ❌ 改判为「待确认」（推定表见 §4.2）

- **反例**：银行短信 + 银行 App 动账通知，都落同一条 `bank` 通道、同金额、短窗，且银行短信**常抽不出商户名** ⇒ 指纹不同（Tier-1 查不到对方）+ 层级相同（Tier-2 原判 ❌ ⇒ **连候选都不是**）⇒ 同一笔扣款被**记两行、虚增支出**。
- **修订**：`complementaryVerdict(bank, bank)` 由 `REJECT` → **`REVIEW`**（浮出候选交用户，既不静默合并、也不静默双记）。
- **⚠️ 同一组合在两条通道判定不同，是**有意**的，勿"统一"**：**判据是证据强度** ——
  | 通道 | 商户名证据 | 判定 |
  |---|---|---|
  | Tier-1（指纹精确） | **完全相同**（强） | ✅ 自动合并 |
  | Tier-2（层级互补） | **不同 / 为空**（弱） | ⚖️ 待确认 |
  **`tierOneAllowsAutoMerge(bank, bank)` 保持 `true`**（⑤ 不碰它）。把两者"统一"要么丢能力（Tier-1 退化），要么吞账（Tier-2 变静默合并）。
- **未来收紧点**：目前 `BANK` 只有唯一 ID，故"同层级"即"同一通道"；若将来 `BANK` 出现第二个 ID，必须收紧为**仅当 `platformId` 相同**才 `REVIEW`（不同两张卡 = 两笔）。

### ⑥ `NeedsReview` 文案「待你确认合并」是**死承诺**（原设计**未涉及**）

- **问题**：待确认页只有「确认入账 / 忽略」两个按钮，**没有「合并」入口**；而这个 `NeedsReview` 的 `reason` 在**三个调用点全被丢弃**（`AppContainer.kt` 取 `.getOrNull()` 后只判非空、`LedgerScreens.kt` 用 `runCatching{}`、`AppStores.kt` 用 `.isSuccess`）。⇒ 用户读到一个**不存在的功能**。
- **修订**：文案改为**不含动作承诺**的表述（「发现 N 笔可能的重复，请核对」）。**"待确认页打「疑似重复」标记 + 合并入口"是另排的 UI 工作，本批不做。**

### ⑦ 其它实现期收敛（非需求偏离，但影响本设计的行为）

- **多候选择一（P2-3）**：`IngestPipeline` 原取 `duplicates.first()`；Tier-2 候选分数恒为 50、排序与"能否自动合并"无关，若一个 `REVIEW` 候选排在前面会**挡掉**本可 `AUTO_MERGE` 的候选 ⇒ 改为 `firstOrNull { canAutoMerge }`。
- **识别用例补齐（T5）**：§7.1 的 R2/R4/R5/R6/R7/R9 补测（R1/R3/R8 判定为已覆盖未重复）。
- **用例前提修正（P2-4）**：集成测试里「财付通」会被判成 `wechat`（`wechat` 弱词与 `bank` 弱词同 0.35 分，按 `sortOrder` tie-break `wechat` 胜）⇒ 改用具名商户并加**前提断言**锁住 `PlatformCatalog.BANK_ID`。

### ⑧ `digital_rmb` / `unionpay` 归入结算侧（`BANK`）；判据由「同层级」改为「**同一条通道**」

- **变更**：`PlatformKind` 由 `PAYMENT` → **`BANK`**（`core:model/.../Platform.kt`）。数字人民币钱包 / 云闪付本质上与银行卡同属「钱从哪个**卡/钱包**出去」的**结算侧**，而不是微信/支付宝那样的**支付通道**。（§2.2 的条目快照保留原样，以本节为准。）
- **反例（真实场景）**：一笔数字人民币支付会同时触发**多条结算侧通道**的通知（银行 App 短信 + 数币 App + 云闪付），商户名各不相同 ⇒ 指纹不同 ⇒ 走 Tier-2。若沿用「`BANK ↔ BANK` 一律 REVIEW」，则「银行卡短信 + 数币通知」这对**同一笔**永远合不上 ⇒ 用户看到 4 条记录，期望 1 条。
- **修订**：`complementaryVerdict` / `tierOneAllowsAutoMerge` 的 `BANK ↔ BANK` 判据由「同层级」升级为「**同一条通道**（同 `platformId`）」：

  | 通道 | 同 `platformId` | 不同 `platformId`（银行卡 ↔ 数币 ↔ 云闪付） |
  |---|---|---|
  | Tier-1 | ✅ 放行（同一条通道被重复抓取 ⇒ Bug 2） | ✅ **放行**（一笔支付触发多条结算侧通道通知） |
  | Tier-2 | ⚖️ REVIEW（交用户，必修⑤） | ✅ **AUTO_MERGE** |

- **⚠️ 旧论证失效（勿再引用）**：§10-⑤ 的「`BANK` 只有唯一 ID `bank` ⇒ 同层级即同一通道」**已不成立** —— 改后 `BANK` 有 **3** 个 ID（`bank` / `digital_rmb` / `unionpay`）。凡引用该前提的段落（§4.2、`ComplementaryMatch` KDoc、`PlatformKind.BANK` KDoc）均已同步更正。

### ⑨ Tier-2 去掉「必须跨渠道」

- **原设计**：Tier-2 要求 `other.sourceId != txn.sourceId`；理由是"同渠道同金额更像两笔真实消费"。
- **反例**：同一笔支付的多条通知**同属 `notify` 渠道**（数币 App / 云闪付 App / 银行 App），该约束让它们**连候选都不是** ⇒ 静默漏合并（用户看到 4 条，期望 1 条）。
- **修订**：`tierTwoCandidates` 去掉该过滤；`canAutoMerge` 的 COMPLEMENTARY 分支同步去掉 `crossSource` 依赖。误合并改由**层级护栏**兜底：同层级同通道 ⇒ REJECT/REVIEW（到不了 AUTO_MERGE），只有**层级互补**或**不同结算侧通道**才 AUTO_MERGE。
- **仍保留的保守分支（本批有意不改）**：同渠道**且指纹相同**的一对（如两条 `notify`）在 **Tier-1** 命中候选，但 `crossSource=false` ⇒ 不自动合并 ⇒ 落**待确认**。团队判定为"保守但安全"，与「宁可多一步确认，也不静默吞掉真实消费」一致。

### ⑩ 数币 / 云闪付改为「官方数字通道」中间层 `E_WALLET`（**推翻 §10-⑧**）

- **背景**：§10-⑧ 曾把 `digital_rmb` / `unionpay` 归入 `BANK`（与银行卡同级）。**用户随后改了口径**：
  「云闪付和数币的优先级要**高于银行卡，但低于微信支付宝**」⇒ 应**插一个中间层**，而不是并入 `BANK`。
- **变更**：`PlatformKind` 新增 `E_WALLET`（`digital_rmb` / `unionpay` 指向它，**不是** `BANK`，也**不是** `PAYMENT`）；
  `PlatformPriority` 重排为 `NONE(0) < BANK(1) < E_WALLET(2) < PAYMENT(3) < ORDER(4)`
  —— `rank` 是 `Int`，中间层靠**重排**加入，**不能**插 `1.5`。
- **判定表（用户拍板，最终版）**：

  | 组合 | 判定 |
  |---|---|
  | 恰好一侧 `ORDER` | ✅ AUTO_MERGE |
  | `PAYMENT ↔ E_WALLET` / `PAYMENT ↔ BANK` / `E_WALLET ↔ BANK` | ⚖️ REVIEW |
  | 同层级 + **同 id**（`bank↔bank` 等） | ⚖️ REVIEW |
  | 同层级 + **不同 id**（`wechat↔alipay` / `digital_rmb↔unionpay` / `meituan↔taobao`） | ❌ REJECT |
  | `NONE` 相关（含 `NONE↔NONE`） | ❌ REJECT |

- **方针**：「**能 REVIEW 就别 REJECT**」—— REJECT 连候选都不给，用户根本看不到；REVIEW 至少浮出来让用户裁决。
  故 `E_WALLET ↔ BANK`（本次需求核心一格）判 **REVIEW**，`PAYMENT ↔ E_WALLET` 亦然。
- **⚠️ §10-⑧ 作废（勿再引用）**：`BANK` 层级重新只剩**唯一 ID `bank`**，§10-⑤ 的「`BANK` 只有唯一 ID ⇒ 同层级即同一通道」
  **重新成立**；`complementaryVerdict` / `tierOneAllowsAutoMerge` 里基于「BANK 多 ID」的那两支（§10-⑧）已**回退**。
- **连带（Tier-1）**：§10-⑧ 里「同层级 BANK 一律放行」的临时分支**作废** —— `E_WALLET` 独立成层后，
  `tierOneAllowsAutoMerge(E_WALLET, BANK)` 走既有「层级不同 ⇒ 放行」语义（**实测 = 放行**），未新增特判。
- **仍未解决（属解析层，与判定表正交，本批范围外）**：复现发现截图里两条通知的金额用 `¥` 符号写出
  （如「……支付¥17.45」），`NotificationParser` 的金额规则**未命中** ⇒ `amountHint = null`
  ⇒ 落「未解析出金额」，连 Tier-2 候选都进不去。**这是"4 条 → 1 条"之外的第二道缺口**，需另立任务。

---

> **给后人的提醒**：本设计的判定表**不是**"从第一天就长这样"。①–⑤ 每一格背后都有一笔几乎被吞掉的真实消费或一次几乎静默丢失的数据。改护栏前请先读本节，尤其是 ① 与 ⑤ 的"**分层尺度 = 证据强度**"—— 把 Tier-1 和 Tier-2 对 `bank↔bank` 的不同判定"统一"起来，正是最容易被误当成 bug 修错的地方。
