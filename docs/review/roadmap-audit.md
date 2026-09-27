# AutoLedger 路线图完成度审计

- **审计时间**：2026-09-27
- **审计范围**：`docs/development-roadmap.md` 定义的 A / B / C / D 各阶段任务，逐项比对代码事实
- **审计性质**：只读（不修改任何源码或配置）

## 审计方法声明

全部结论来自代码取证（文件路径 + 行号），**未采信 `docs/development-roadmap.md` 末尾那段已过时的"进度基线"**——该基线最后写于 **17:43**，而退款相关的全部实现是在 **18:30–19:51** 完成的，因此那份基线不可采信。测试基线为 `gradle test --rerun-tasks` 实测 **324 用例 0 失败**。

> 一句话结论：**路线图未全部完成。** B 阶段只有 B4 部分落地（仅首页实时）；C 阶段 5 项中 C5 完整，C1 仅"多账户"落地、"多币种"零实现，C2 仅接口 + 空实现，C3 加密导出/导入无 UI 入口，C4 零实现；A 阶段抽查 A1 / A3 / A5 均达标。

---

## ① B4 首页/账单实时刷新 — 部分完成（约 2 成）

**结论**：只有"首页/仪表盘"改成了 Room Flow 订阅；其余 7 个 Store、6 类页面仍是"进页面手动 `load()` + 增删改后手动 `load()`"。验收标准"删除手动 `load()` 依赖"**未达成**。

**已实时（仅首页 1 处）**

| 证据 | 说明 |
|---|---|
| `app/src/main/java/com/autoledger/app/ui/stores/AppStores.kt:115` | `private var observeJob: Job?`（实例级可取消订阅句柄） |
| `AppStores.kt:121` | `fun load()` —— 幂等重启订阅（先 `observeJob?.cancel()` 再 `scope.launch { observe() }`） |
| `AppStores.kt:130` | `fun close()` —— 取消订阅、释放 scope |
| `AppStores.kt:142-148` | `observe()` = `combine(observeSince(month.start), observeRawCount())` + `.flowOn(Dispatchers.Default)`（`import combine` :36、`flowOn` :38） |
| `app/src/main/java/com/autoledger/app/ui/screens/HomeScreens.kt:49` | `LaunchedEffect(container) { store.load() }` —— 仅用于启动订阅 |
| `HomeScreens.kt:52` | `DisposableEffect(store) { onDispose { store.close() } }` —— 离开页面释放 |

> 说明：`HomeScreens.kt:49` 是 Flow 的**启动订阅**（新形态），不是遗留的手动刷新；`:106` 的 `ErrorPanel { store.load() }` 是错误重试按钮，属正常。

**仍手动（7 个 Store，全部 `storeScope.launch` + 一次性 `listAll/listRange`）**

| Store | 证据（行号） |
|---|---|
| `LedgerStore` | `AppStores.kt:213 fun load(){ storeScope.launch{ listAll(includeTransfers=true) } }`；增删改后手动重载 `:262/:267/:281` |
| `CaptureStore` | `AppStores.kt:326` |
| `FreedomStore` | `AppStores.kt:386` |
| `InsightsStore` | `AppStores.kt:437` |
| `SettingsStore` | `AppStores.kt:484` |
| `RefundStore` | `AppStores.kt:561 fun load(){ storeScope.launch{ refresh() } }` |
| `CategoryStore` | `AppStores.kt:645` |

**Screen 手动刷新调用点（6 类页面）**

| 页面 | 证据（行号） |
|---|---|
| 账单 | `app/src/main/java/com/autoledger/app/ui/screens/LedgerScreens.kt:65/131/290` |
| 采集箱 / 设置 | `app/src/main/java/com/autoledger/app/ui/screens/CaptureAndSettings.kt:68/172` |
| 发现 / 月结 | `app/src/main/java/com/autoledger/app/ui/screens/InsightScreens.kt:47/133` |
| 分类管理 | `app/src/main/java/com/autoledger/app/ui/screens/CategoryScreen.kt:54` |
| 订单与退款 | `app/src/main/java/com/autoledger/app/ui/screens/RefundScreen.kt:50` |

### 缺口 → 需要做什么

1. 把其余 7 个 Store 全部改为 **Flow 订阅 + `close()` 释放**（照抄 `HomeStore` 模式：实例级可取消 scope、`observeJob`、`load()` 幂等重启、`close()` 取消）。
2. 底座已具备，无需新增数据库代码：
   - `observeSince` / `observeRaw` / `observeRawCount`（`core/database/src/main/java/com/autoledger/core/database/Daos.kt:33/57/87`）
   - `SettingsDao.observeAll()`（`Daos.kt:143`）
   - `LedgerRepository.observeSince` / `observeRawCount`（`core/model/src/main/java/com/autoledger/core/model/Spi.kt:167/176`）
3. 对应 Screen 去掉 `LaunchedEffect { store.load() }`，改为 `DisposableEffect { onDispose { store.close() } }`。

---

## ② C1 多账户/多币种 — 部分完成（多账户 ✅ / 多币种 ❌ 未开始）

**多账户已具备（数据层完整）**

| 证据 | 说明 |
|---|---|
| `core/model/src/main/java/com/autoledger/core/model/Model.kt:78-87` | `data class Account(id, name, kind: AccountKind, institution, identifierHints, archived)` |
| `core/database/src/main/java/com/autoledger/core/database/Entities.kt:65-73` | `@Entity(tableName = "accounts", indices = [Index("kind")])` |
| `core/database/src/main/java/com/autoledger/core/database/Daos.kt:102-106` | `AccountDao` |
| `core/database/.../repository/RoomLedgerRepository.kt` | `listAccounts` / `upsertAccounts` |

**多币种完全未做**

| 证据 | 说明 |
|---|---|
| `core/model/src/main/java/com/autoledger/core/model/Model.kt:21` | `LedgerTransaction.currency` 字段存在，默认 `Money.DEFAULT_CURRENCY = "CNY"`（硬编码） |
| `core/model/src/main/java/com/autoledger/core/model/Money.kt:14` | `DEFAULT_CURRENCY = "CNY"` |
| `core/database/.../Entities.kt:29/137` | 实体 `currency` 字段同样默认 CNY |
| 全仓 `grep "exchangeRate\|fxRate"` | **零命中** —— 无任何汇率换算 |
| `feature/stats/` | 无任何多币种 / 汇率处理逻辑 |
| `core/database/.../repository/RefundRepository.kt:77`、`core/backup/.../BackupEnvelope.kt:84` | 备份 / 退款路径亦写死 CNY |

**结论**：C1 的"多账户"清单已落地；"多币种"未开始。若 C1 验收含多币种，则整体**未完成**。

### 缺口 → 需要做什么

1. 是否真要做多币种 —— **需产品决策**（详见「未完成项分类」）。若做：
   - 引入 `Currency` 枚举 / 币种表 + 汇率表（含时间戳，支持历史汇率）。
   - `Money` 增加币种维度；统计口径（`ExpenseMath`）增加"折算到基准币种"步骤。
   - 账本 / 汇总 / 预算所有展示区分"原币金额"与"基准币折算金额"。
2. 若不做：至少把硬编码 CNY 收敛为单一常量或配置项，并在 UI 明确"当前仅支持单币种（CNY）"，避免误导。

---

## ③ C2 云同步 — 未开始（仅接口 + 空实现）

| 证据 | 说明 |
|---|---|
| `settings.gradle.kts` | 10 个模块中**无 `:feature:sync`** |
| `core/database/src/main/java/com/autoledger/core/database/sync/CloudSyncClient.kt:14` | `interface CloudSyncClient { isReady(); push(ops); pull(sinceMillis) }` |
| `CloudSyncClient.kt:33` | `object NoopCloudSyncClient : CloudSyncClient` —— `push/pull` 直接返回空 |
| `app/src/main/java/com/autoledger/app/di/AppContainer.kt:176` | `val cloudSyncClient: CloudSyncClient = NoopCloudSyncClient` |
| `core/database/.../Entities.kt:97` | `SyncOutboxEntity`（写路径已登记 op）；注释 `:94` 明说"等 `CloudSyncClient` 实现后消费" |
| `app/.../ui/screens/CaptureAndSettings.kt:310` | 设置页自述"云同步仅有接口，当前是无操作的占位实现" |

**结论**：只有接口 + 空实现 + outbox 表，**无真实后端、无 `feature:sync` 模块** ⇒ 未开始。

### 缺口 → 需要做什么

- **需外部依赖**：需要真实后端服务（账号体系、鉴权、增量同步协议、冲突解决策略）。
- 客户端侧已备好：`SyncOutboxEntity` / `SyncOutboxDao` + `CloudSyncClient` 契约，只需实现真实 `CloudSyncClient` 并注册到 `AppContainer`，再补冲突合并 UI。
- 与 roadmap 一致：此项风险最高、排最后。

---

## ④ C3 加密备份 UI + 口令找回提示 — 未开始（后端能力在，UI 入口缺失）

**core:backup 已有能力**

| 证据 | 说明 |
|---|---|
| `core/backup/src/main/java/com/autoledger/core/backup/BackupManager.kt:52` | `exportJson(appVersion, device)` |
| `BackupManager.kt:57` | **`exportEncrypted(appVersion, device, passphrase: CharArray)`** —— 加密导出已就绪 |
| `BackupManager.kt:118` | `import(raw, strategy)` |
| `BackupManager.kt:37` | `MergeStrategy { REPLACE_ALL, MERGE_BY_ID }` |
| `BackupManager.kt:204` | `@Volatile var currentPassphrase: CharArray?` —— 加密分支的注入口 |
| `BackupManager.kt:110/247/265` | `buildPayload` 已含 `accounts` 与 `settings` |

**但 app 侧完全没接**

| 证据 | 说明 |
|---|---|
| 全仓 `grep "exportEncrypted" app/` | **零命中** ⇒ 加密导出/导入**无任何 UI 入口** |
| `app/.../ui/screens/CaptureAndSettings.kt:176` | `store.exportJson(uri)` —— 仅明文导出 |
| `CaptureAndSettings.kt:180` | `store.importJson(uri)` —— 仅明文导入 |
| `CaptureAndSettings.kt:194-204` | 按钮仅"导出 JSON" / "导入备份"，无口令输入、无加密选项 |
| 全仓 `grep "currentPassphrase"` | 仅出现在 `BackupManager.kt:123/204`，**app 从不注入** ⇒ 加密分支不可达 |
| 口令找回提示 | **零实现** —— 无 passphrase 校验失败文案或找回引导 |

**结论**：加密 API 就绪但**未接通 UI**，"口令找回提示"零实现 ⇒ 未开始。

### 缺口 → 需要做什么（无需决策、可直接做）

1. 设置页新增"加密导出 / 加密导入"入口：弹出 Passphrase 输入框（两次确认）。
2. 导出走 `BackupManager.exportEncrypted(...)`；导入前先注入 `currentPassphrase = passphrase`。
3. 口令错误时给出**明确提示**（"口令不对，无法解密"）并附"口令无法找回，请重新选择一个记得住的"引导；导出时提示"请牢记口令"。
4. 复用现有 `ImportOutcome` / `state.message` 反馈通道。

---

## ⑤ C4 换机数据迁移 — 未开始

| 证据 | 说明 |
|---|---|
| `settings.gradle.kts` | **无 `:feature:transfer`** |
| 全仓 `grep "断点续传\|WifiP2pManager\|NsdManager"` | **零命中** |
| `docs/migration-design.md` | 为**纯设计文档**，无对应实现代码 |
| Wi-Fi 直连 / 扫码配对 / 分块断点续传 / SHA-256+条数校验 | **均无代码落地** |

**结论**：未开始。

### 缺口 → 需要做什么

- **需产品决策**（工作量较大）。若做，至少落地：
  1. 传输通道（Wi-Fi 直连 / NSD 服务发现 或 扫码配对）
  2. 分块 + 断点续传
  3. 传输完整性校验（SHA-256 + 条数）
  4. 目标机导入（复用 `BackupManager.import` + 校验）

---

## ⑥ C5 退款与抵扣原路回退 — 已完成

**证据链（六层齐全）**

| 层 | 证据 |
|---|---|
| 引擎（纯 JVM） | `feature/refund/src/main/java/com/autoledger/feature/refund/RefundEngine.kt` —— `evaluate`(:26)、`allocate`(:133)、`rejectCodeForStatus`(:208)、`reconcile`(:224)；`MAX_ATTEMPTS = 3`(:18)；分摊自检 `check(sum == amount)`(:103) |
| 引擎测试 | `feature/refund/src/test/.../RefundEngineTest.kt`、`RefundReconcileTest.kt` |
| 领域类型 | `core/model/src/main/java/com/autoledger/core/model/refund/RefundModel.kt`、`RefundLedgerMapper.kt` |
| 5 张表 | `core/database/.../Entities.kt:131 orders`、`:149 order_deductions`、`:168 refunds`、`:186 refund_allocations`、`:203 resource_balances`；`transactions` 补 `:46 orderId` / `:47 refundId` |
| DAO / 仓储 | `core/database/.../Daos.kt:181 ResourceBalanceDao` 等；`repository/RefundRepository.kt` + `RefundMappers.kt`（单事务 / CAS / 幂等） |
| app 接线 | `app/src/main/java/com/autoledger/app/refund/RefundService.kt`、`ui/screens/RefundScreen.kt`、`RefundStore`(`AppStores.kt:545-629`：`requestRefund/addOrder/select/refresh/listOrders`)、设置页入口 `CaptureAndSettings.kt:221-229 "订单与退款"` |

**设计 vs 实现一致性**

- 设计批次 ①②③（引擎 / 持久化 / UI）**全部落地**；批次 ④"采集侧自动退款识别"在 `docs/refund-design.md:14` **设计自身即标注 ⏳ 待产品决策**，非 C5 缺口。
- 幂等约束与设计一致：`refunds.idempotencyKey` 唯一（`Entities.kt:169`）、`refund_allocations UNIQUE(refundId, deductionId)`（`Entities.kt:187`）。
- 唯一次要偏差：`orders` 的唯一索引是 `UNIQUE(orderNo)`（`Entities.kt:131`），设计写的是 `UNIQUE(source_id, order_no)`；实际约束更严格，**非缺口**。

### 缺口 → 需要做什么

- **C5 核心无缺口。** 唯一未做的是设计批次 ④「采集侧自动退款识别」（通知/短信识别退款事件 → 匹配订单 → 自动落库），该批次设计自身已标注"待产品决策"，属**后续增强**，不影响 C5 主体完成度判定。

---

## ⑦ A 阶段抽查（A1 / A3 / A5）

**A1「`feature:classify` 不再依赖 `core:database`」✅ 已达成**

| 证据 | 说明 |
|---|---|
| `feature/classify/build.gradle.kts:19-26` | 只声明 `implementation(project(":core:model"))` + `kotlinx-coroutines-core`，**无 `core:database`** |
| `feature/classify/src/main/java/com/autoledger/feature/classify/CorrectionLearner.kt:3-5` | 依赖契约 `RuleSource` / `RuleKind` / `ClassifierRule`（`ruleSource.allRules/upsertRules/countLearned/clearLearned`） |
| classify 全模块 `grep "core.database\|Room\|Dao\|Entity\|androidx.room"` | **零命中**；`RoomRuleSource.kt` 已迁至 `core:database` |

**A3「`CaptureScreen` 无 `when(source.id)` 硬编码」✅ 已达成**

| 证据 | 说明 |
|---|---|
| `app/.../ui/screens/CaptureAndSettings.kt:106-128` | 改为 `row.source.actions.forEach { action -> when (action) { is CaptureAction.OpenSystemSettings / RequestPermission / ScanBacklog / PickFile -> ... } }` —— 对 sealed `CaptureAction` 分派、**数据驱动**，不再对具体来源 ID 打分支 |
| `core/model/src/main/java/com/autoledger/core/model/Spi.kt` | `CaptureSource` 已含 `statusHint(st)` 等契约方法 |

**A5「Room schema 快照入库」✅ 已达成**

| 证据 | 说明 |
|---|---|
| `core/database/schemas/com.autoledger.core.database.LedgerDatabase/{2.json,3.json,4.json}` | 均已入库 |
| `core/model/src/main/java/com/autoledger/core/model/Schema.kt` | `DATABASE_VERSION = 4`、`BACKUP_VERSION = 4` |

### 缺口 → 需要做什么

- **A1 / A3 / A5 均无缺口。**

---

## 总表

| 阶段 | 项 | 状态 | 证据（文件:行号） |
|---|---|---|---|
| B | B4 首页/账单实时刷新 | **部分完成**（仅首页实时） | 已实时：`AppStores.kt:115/121/130/142-148` + `HomeScreens.kt:49/52`；仍手动：`AppStores.kt:213/326/386/437/484/561/645` + `LedgerScreens.kt:65/131/290`、`CaptureAndSettings.kt:68/172`、`InsightScreens.kt:47/133`、`CategoryScreen.kt:54`、`RefundScreen.kt:50` |
| C | C1 多账户/多币种 | **部分完成**（多账户 ✅ / 多币种 ❌） | 账户：`Model.kt:78-87`、`Entities.kt:65-73`、`Daos.kt:102-106`；多币种：全仓 `exchangeRate` 零命中、硬编码 CNY `Model.kt:21`、`RefundRepository.kt:77`、`BackupEnvelope.kt:84` |
| C | C2 云同步 | **未开始** | `CloudSyncClient.kt:14/33`、`AppContainer.kt:176`、`settings.gradle.kts`（无 `:feature:sync`） |
| C | C3 加密备份 UI + 口令找回 | **未开始** | API 就绪：`BackupManager.kt:57/204`；app 未接：`grep exportEncrypted app/` 零命中、`CaptureAndSettings.kt:176/180/194-204` 仅明文 |
| C | C4 换机数据迁移 | **未开始** | `settings.gradle.kts`（无 `:feature:transfer`）、`断点续传/WifiP2p/NsdManager` 零命中、`migration-design.md` 纯设计 |
| C | C5 退款与抵扣原路回退 | **已完成** | `feature/refund/RefundEngine.kt` + `Entities.kt:131/149/168/186/203` + `Daos.kt:181` + `RefundRepository.kt` + `RefundService.kt` + `RefundScreen.kt` + `AppStores.kt:545-629` + `CaptureAndSettings.kt:221-229` |
| A | A1 classify 解耦 core:database | **已完成** | `feature/classify/build.gradle.kts:19-26`、`CorrectionLearner.kt:3-5`、全模块零 Room 引用 |
| A | A3 去掉 `when(source.id)` 硬编码 | **已完成** | `CaptureAndSettings.kt:106-128`（数据驱动 `when(action)`）、`Spi.kt`（`statusHint`） |
| A | A5 Room schema 快照入库 | **已完成** | `core/database/schemas/.../{2,3,4}.json`、`Schema.kt`（`DATABASE_VERSION=4`） |

---

## 未完成项分类

### 一、无需决策、可直接做

| 项 | 现状 | 直接可做的动作 |
|---|---|---|
| **B4**（其余 7 个 Store 改 Flow 订阅） | 仅 `HomeStore` 用了 Flow，其余手动 | 照抄 `HomeStore` 模式；底座 `observeSince`/`observeRawCount`/`SettingsDao.observeAll()` 已具备，无需新增 DB 代码 |
| **C3**（加密导出/导入 UI 入口） | `BackupManager.exportEncrypted` / `currentPassphrase` 已就绪，app 未接 | 设置页加 Passphrase 输入 + 加密导出/导入按钮 + 口令错误/找回提示 |

### 二、需产品决策

| 项 | 决策点 | 影响面 |
|---|---|---|
| **C1 多币种** | 是否真要做多币种？ | `Model.kt:21` 硬编码 CNY；`RefundRepository.kt:77`、`BackupEnvelope.kt:84` 也写死；统计口径 `ExpenseMath` 需引入折算 |
| **C4 换机数据迁移** | 是否投入做换机迁移？ | Wi-Fi 直连 + 扫码配对 + 分块断点续传，工作量较大；`migration-design.md` 仅为设计 |

### 三、需外部依赖

| 项 | 依赖 | 说明 |
|---|---|---|
| **C2 云同步** | 真实后端服务 | 目前仅 `CloudSyncClient` 接口 + `NoopCloudSyncClient`；客户端 side 的 outbox 已备好；roadmap 早已注明风险最高、排最后 |

---

## 一句话回答

**(a) B 阶段是否全部完成？→ 否。** B4 部分完成：仅**首页/仪表盘**已实时（Room Flow 订阅），其余 **6 类页面**（账单 / 采集箱 / 设置 / 发现 / 月结 / 分类 / 退款）仍依赖手动 `load()` 刷新。

**(b) 路线图是否全部完成？→ 否。**

| 分类 | 清单 |
|---|---|
| **已完成** | A1、A3、A5、C5（退款与抵扣原路回退） |
| **部分完成** | B4（仅首页实时）、C1（多账户 ✅ / 多币种 ❌） |
| **未开始** | C2（云同步）、C3（加密备份 UI + 口令找回）、C4（换机数据迁移），以及 C1 的多币种部分 |
