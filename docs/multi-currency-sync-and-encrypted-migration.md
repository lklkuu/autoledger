# 多币种 / 云同步接口预留 + 换机迁移加密数据处理设计

> 状态：**设计文档（仅接口预留，不实现业务逻辑）**
> 日期：2026-09-27
> 关联：`docs/development-roadmap.md`（C1 多币种 / C2 云同步 / C4 换机迁移）、`docs/migration-design.md`（C4 传输方案）
> 说明：本文是 `migration-design.md` 的**加密与接口层补充**——后者解决"怎么传"，本文解决"传的过程中密文怎么处理、未来多币种/云同步要预留什么"。

---

## 一、现状盘点（基于代码事实，设计的前提）

### 1.1 三层密钥体系

| 层 | 实现 | 位置 | 可否跨设备 |
|---|---|---|---|
| Keystore 主密钥 `masterKey` | `KeystoreKeyProvider`（alias=`autoledger_master_key_v1`，AES-256-GCM，TEE/StrongBox） | 系统密钥容器 | ❌ 设备本地，**不可迁移、不可读出私钥材料** |
| 数据库口令（32B） | `PassphraseVault`：随机生成 → `masterKey` 包裹 → base64 存 SharedPreferences | `autoledger_vault` 的 `db_passphrase_wrapped` | ❌ 依赖 masterKey |
| 备份口令 | `PassphraseKeyDeriver`（PBKDF2-HMAC-SHA256，210k 迭代，16B salt） | 用户输入，不落盘 | ✅ 用户口令可跨设备 |

### 1.2 三类加密数据（迁移时必须逐一处理）

| 数据 | 用什么密钥加密 | 迁移时怎么办 |
|---|---|---|
| 整库（Room） | SQLCipher，口令来自 `PassphraseVault` | 旧机导出时 SQLCipher 自动解密为内存明文 |
| 原文二次加密 `rawTextSealed` | **`masterKey` 的 `CryptoBox`**（`AppContainer.kt:91` → `IngestPipeline.sealString`） | ⚠️ **换机后新机 masterKey 不同 → 直接迁移则原文永久不可读**（见 §4.2） |
| 备份档案 | `exportEncrypted`（PBKDF2 派生密钥 + AES-GCM） | 复用，口令跨设备可用 |

### 1.3 多币种现状

- `LedgerTransaction.currency: String = Money.DEFAULT_CURRENCY`（默认 `"CNY"`）——**字段已存在**，但全链路硬编码。
- `Money.currency: String = DEFAULT_CURRENCY`。
- `Account`、`Category.monthlyBudgetMinor`、`AppSettings` 的 wage/goal 金额**均无币种**（隐式 CNY）。
- 全仓 `exchangeRate` / `fxRate` 零命中，无任何汇率换算。

### 1.4 云同步现状

- `CloudSyncClient` 接口（`isReady` / `push` / `pull`）+ `NoopCloudSyncClient` 空实现。
- `SyncOutboxEntity` / `SyncOutboxDao` 已存在（写路径会登记 `op`），注释"等 CloudSyncClient 实现后消费"。
- `settings.gradle.kts` 无 `:feature:sync`。

---

## 二、C1 多币种：接口预留字段与扩展位（不实现）

> 目标：**未来加多币种时不再改表结构 / 不再改核心模型**，只在预留的扩展位上填逻辑。

### 2.1 数据模型预留

| 实体 | 预留字段 | 类型 | 说明 |
|---|---|---|---|
| `LedgerTransaction` | `currency` | `String` | **已存在**，保持；金额 `amountMinor` 与该币种的最小货币单位对应 |
| `Account` | `currency` | `String = Money.DEFAULT_CURRENCY` | 账户默认币种；转账识别/对账按账户币种归一 |
| `Category` | `budgetCurrency` | `String? = null` | `monthlyBudgetMinor` 的币种；null 视为默认币种 |
| `AppSettings` | `wageCurrency` / `goalCurrency` | `String? = null` | 时薪/目标金额币种；null 视为默认币种 |
| `Money` | `currency` | `String` | **已存在**；未来加 `convertTo(target, rates)` 签名（占位） |

### 2.2 新增 `ExchangeRate` 模型 + 表（预留）

```kotlin
// core:model —— 只定义类型，不实现换算逻辑
data class ExchangeRate(
    val baseCurrency: String,   // 基准币种（ISO 4217，如 "USD"）
    val quoteCurrency: String,  // 报价币种（如 "CNY"）
    val rateMinor: Long,        // 汇率，定点（如 1 USD = 7_2000 / 1e4 = 7.2 CNY，存 72000）
    val rateScale: Int,         // 定点小数位（避免浮点误差）
    val rateDateMillis: Long,   // 生效日期
    val source: String,         // 来源（手动 / 联网）
)
```

- Room 预留表 `exchange_rates`（`UNIQUE(baseCurrency, quoteCurrency, rateDateMillis)`）+ DAO（`observeRate(base, quote, date)`）。
- **不实现**：汇率抓取、自动换算、展示币种切换。

### 2.3 聚合口径预留

`ExpenseMath` 现在跨币种直接 `sumOf { abs(amountMinor) }`（隐含同币种前提）。多币种后必须**先归一币种再聚合**。预留重载签名（只签名，不实现）：

```kotlin
// 预留：跨币种聚合需先换算到基准币种；rates 为 null 时回退为「同币种直接累加」
fun netExpenseMinor(
    txns: List<LedgerTransaction>,
    baseCurrency: String = Money.DEFAULT_CURRENCY,
    rates: Map<Pair<String, String>, ExchangeRate>? = null,
): Long
```

### 2.4 备份 / 退款路径的币种处理（已具备，需确认）

- 备份 JSON 已带 `currency`（`BackupManager.toJson` 的 `put("currency", currency)`）；导入用 `optString("currency", "CNY")` 兜底 → **旧备份天然兼容**。
- 退款引擎 `RefundRepository.saveOrder` 硬编码 `currency = "CNY"` → 预留时改为 `currency: String = Money.DEFAULT_CURRENCY` 参数。
- `BackupEnvelope` 的 `version` 可承载"引入多币种的档案版本"。

---

## 三、C2 云同步：接口预留字段与扩展位（不实现）

> 目标：**接真实后端时只写一个 `CloudSyncClient` 实现，不改数据层**。

### 3.1 `CloudSyncClient` 接口扩展（预留签名）

```kotlin
// core:model —— 契约层预留，不实现
interface CloudSyncClient {
    val isReady: Boolean
    suspend fun push(cursor: String, items: List<SyncItem>): SyncResult
    suspend fun pull(cursor: String): SyncPull
}

// 预留类型
data class SyncItem(
    val entityType: String,      // "transaction" | "category" | "account" | "rule" | "settings"
    val entityId: String,
    val idempotencyKey: String,  // 幂等：服务端据此去重
    val op: SyncOp,              // UPSERT / DELETE
    val payload: String,         // 已加密的实体 JSON（客户端加密，服务端只存密文）
    val clientVersion: Int,      // 客户端结构版本
)

enum class ConflictStrategy { LAST_WRITE_WINS, FIELD_LEVEL, MANUAL }

data class SyncResult(
    val serverCursor: String,
    val conflicts: List<SyncConflict>,
)
```

### 3.2 `SyncOutbox` 扩展字段（预留）

现有 `op` 字段保留；新增预留列：

| 字段 | 说明 |
|---|---|
| `entityType` / `entityId` | 精确到实体，支持按类型增量同步 |
| `idempotencyKey` | 幂等去重 |
| `retryCount` / `lastAttemptAtMillis` | 重试与退避 |
| `clientVersion` | 客户端结构版本 |

### 3.3 云端的加密与币种约定（设计约束，不实现）

- **云端零明文**：实体先经客户端密钥加密（复用 `CryptoBox` 或 PBKDF2 派生）再上传；服务端只见密文。→ 与本地「整库加密 + 原文二次加密」的隐私立场一致。
- **同步内容复用 `BackupEnvelope` 的版本化 + currency 字段**：避免云同步与备份两套格式漂移。
- **多币种同步**：汇率表 `exchange_rates` 作为独立 entityType 同步（带 `rateDateMillis` 幂等键）。

---

## 四、C4 换机迁移：加密数据处理策略（重点）

### 4.1 核心原则

1. **密钥不迁移**：Keystore 主密钥是设备本地（TEE/StrongBox），**永不离开设备**。换机 = 新机重新生成 `masterKey` + 新口令。
2. **数据重加密**：迁移的本质 = 「旧机用旧密钥解密 → 传输层加密 → 新机解密 → 新机新密钥重新加密」。
3. **原文二次加密必须重加密**：`rawTextSealed` 用旧机 masterKey 加密，换机后新机 masterKey 不同 → **不重加密则原文永久丢失**。

### 4.2 加密数据分步处理策略（逐类）

#### 第一类：整库数据（SQLCipher）

| 步骤 | 旧机（源） | 新机（目标） |
|---|---|---|
| 解密 | `BackupManager.exportJson` 读 Room，SQLCipher 自动用旧口令解密 → 内存明文 JSON | —— |
| 传输 | 用**会话密钥**加密（§4.4） | 会话密钥解密 → 明文 JSON |
| 重加密 | —— | `BackupManager.import` 写入 Room，SQLCipher 用**新机新口令**自动加密 |

#### 第二类：原文二次加密 `rawTextSealed`（**关键难点**）

现状：`rawTextSealed` = 用旧机 `masterKey` 加密的 base64 密文。直接迁移 → 新机 masterKey 解不开。

**策略 A（推荐，保原文）—— 迁移时原地"解密 → 重加密"：**

```
旧机：
  1. 对每条 rawTextSealed，用旧机 masterKey 的 CryptoBox.open() 解密 → 原文明文
  2. 原文明文随迁移包传输（传输层已用会话密钥加密）
新机：
  3. 解密传输层 → 得到原文明文
  4. 用新机 masterKey 的 CryptoBox.sealString() 重新加密 → rawTextSealed'
  5. 入库（带密钥版本前缀，见 §4.3）
```

**策略 B（降级，丢原文但保账目）：**

- 不重加密，直接迁移密文；换机后原文不可读，但**金额 / 商户 / 分类 / 时间完整保留**。
- UI 对这类记录显示「原文已随旧设备密钥失效」。
- 适用：用户不关心历史通知原文、或旧机已无法解密（masterKey 失效）的场景。

**选择机制**：迁移协议里带 `rawTextPolicy: REWRAP | DROP`，由旧机按"能否解出 masterKey"自动决定，用户可在迁移前手动覆盖。

#### 第三类：备份档案本身（可选口令加密）

- 迁移复用 `exportEncrypted`（PBKDF2 派生 + AES-GCM），口令由用户在旧机/新机输入一次。
- 或改用**临时会话密钥**（§4.4），避免用户手动输口令，做到"扫码即迁移、全程无输入"。

### 4.3 密钥版本管理

| 层 | 现状 | 预留 |
|---|---|---|
| Keystore 主密钥 | alias 已带 `v1`（`autoledger_master_key_v1`） | 轮换时生成 `autoledger_master_key_v2`；**旧 key 保留不解密数据**，新数据用新 key |
| `rawTextSealed` | 纯 base64 密文，**无版本前缀** | 预留 `v{n}:base64` 前缀，导入/迁移时按版本选解密 key |
| 备份信封 | 带 `version` | 加 `keyVersion` 字段，记录加密时用的密钥版本 |
| 迁移包 | —— | 加 `sourceKeyVersion` / `targetKeyVersion` 元数据 |

**轮换策略**：旧 key 永不删除（用于解密历史密文）；迁移时"旧 key 解密 → 新 key 重加密"完成密钥滚动。

### 4.4 传输层会话密钥（用完即弃）

- 复用 `migration-design.md` 的一次性 token（30s 有效）。
- 会话密钥 = HKDF(token, salt) 派生（或 QR 码内嵌临时密钥 + ECDH 协商）。
- **只加密传输层**，不参与落盘；传输结束即擦除（`PassphraseKeyDeriver.wipe`）。

### 4.5 异常回滚机制

| 异常 | 处理 |
|---|---|
| 新机导入失败（校验不过 / 版本不匹配） | 临时文件**不替换**正式库，旧数据完整；删除临时文件 |
| 中途断电 / 断连 | 临时文件 + 已收块位图，重连续传（只拉缺块） |
| 重加密失败（新机 masterKey 异常） | 原始密文保留，**不部分提交**；显式报错，不静默降级 |
| 口令错误 | `AEADBadTagException` → 明确提示"口令错误"，不落库 |
| 密钥版本不匹配 | 读 `keyVersion`，走对应迁移链；无对应 key 则显式报错（绝不静默） |
| 旧机 masterKey 已失效（策略 B 触发条件） | 走 `DROP` 策略，保账目丢原文，并明确告知用户 |

### 4.6 迁移加密数据的完整分步时序

```
旧机（源）                                 新机（目标）
  ① 读库（SQLCipher 自动解密）→ 内存明文
  ② rawTextSealed：masterKey 解密 → 原文明文（或按策略 B 跳过）
  ③ 组装迁移包：明文 JSON + keyVersion + rawTextPolicy 元数据
  ④ 会话密钥（token 派生）AES-GCM 加密迁移包
  ⑤ 分块传输（64KB + 序号，断点续传） ──────►  ⑥ 逐块收临时文件
                                             ⑦ 会话密钥解密 → 明文 JSON
                                             ⑧ SHA-256 + 条数双校验
                                             ⑨ rawTextSealed 明文 → 新机 masterKey 重加密
                                             ⑩ 写入 Room（SQLCipher 新口令自动加密）
                                             ⑪ 原子提交（临时文件 → 正式库）
                                             ⑫ 失败回滚：删临时文件，旧数据完好
```

---

## 五、接口预留范围汇总

| 功能 | 预留内容 | 是否改现有代码 |
|---|---|---|
| C1 多币种 | `Account.currency`、`Category.budgetCurrency`、`AppSettings` 币种、`ExchangeRate` 模型+表、`ExpenseMath` 换算重载、`RefundRepository.saveOrder` 的 currency 参数 | 仅加字段/签名，**不改业务** |
| C2 云同步 | `CloudSyncClient` 增量/冲突/幂等签名、`SyncOutbox` 扩展列、`SyncItem`/`SyncResult` 类型 | 仅加接口/列，**不接后端** |
| C4 换机迁移 | 迁移包元数据（keyVersion / rawTextPolicy / sourceKeyVersion）、`rawTextSealed` 版本前缀、会话密钥 HKDF、回滚与续传协议 | 新增 `feature:transfer`，复用 `core:backup` |

## 六、决策记录（2026-09-27 已确定）

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 原文二次加密的换机处理 | ✅ **策略 A**：旧机解密 → 传输 → 新机重新加密（**保原文**）；仅在旧机 masterKey 已失效时降级为策略 B（丢原文保账目，并明确告知） |
| 2 | 多币种聚合基准币种 | ✅ **人民币（CNY）** |
| 3 | 云同步冲突解决 | ✅ **LAST_WRITE_WINS**（最后写入者胜） |
| 4 | 会话密钥来源 | ✅ **扫码 token 派生**（HKDF），不引入 ECDH |
