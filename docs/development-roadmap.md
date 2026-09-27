# AutoLedger 后续开发推进计划（Roadmap）

> 说明：本计划承接已完成的 W1（数据模型扩展），覆盖「改造收尾 → 核心功能 → 增强功能 → 发布开源」四个阶段。
> Git 初始化、GitHub 推送、开源文件（README/LICENSE 等）统一放在最后阶段 D 执行。

## 阶段总览与依赖顺序

```
A 技术债清理（改造收尾）
   W2 分类解耦 ─────────────┐
   W5 设置入库 ─────────────┤──→ B 核心功能（预算/分类UI/实时刷新）
   W3 渠道能力化 ───────────┤
   W4 写入一致性 ───────────┤
   W6 工程化收敛 ───────────┘
                              │
                              └──→ C 增强功能（标签/多账本/云同步）
                                        │
                                        └──→ D 发布开源（Git→GitHub→README/LICENSE→CI→签名）
```

## 一、各阶段工作清单（优先级 / 验收标准 / 模块 / 依赖）

### A 阶段 · 技术债清理（P0，先做，解锁后续）

| # | 工作 | 优先级 | 可量化验收标准 | 改动/新增模块 | 依赖 |
|---|---|---|---|---|---|
| A1 | W2 分类引擎解耦 | P0 | `feature:classify` 不再依赖 `core:database`；新增 ≥10 条 classify JVM 单测且全绿；`assembleDebug` 0 错误 | 改：`core:model`(加 `RuleSource`)、`core:database`(加 `RoomRuleSource`)、`feature:classify`、`feature:capture` | 无（可独立） |
| A2 | W5 设置入库 + 备份 | P0 | 时薪/目标迁入 Room、金额用 Long「分」；导出备份含 settings 段，导入往返字段不丢；移除 SharedPreferences 的 Float 存储 | 改：`core:model`(加 `FreedomGoal`)、`core:database`(新表/DAO)、`core:backup`、`app:UserSettings` | 依赖 W1（已完成） |
| A3 | W3 渠道能力化 | P1 | `CaptureScreen` 无 `when(source.id)` 硬编码；新增一个虚拟渠道仅需注册、不改 UI | 改：`feature:capture`、`app:CaptureAndSettings` | 无（可并行） |
| A4 | W4 写入一致性 + 安全 | P1 | 写路径 `@Transaction` 原子化；「清空流水」二次确认；枚举未知值不静默兜底；账户归属匹配收紧后，构造 20 条样本误判 = 0 | 改：`core:database`、`feature:dedup`、`app` | 无（可并行） |
| A5 | W6 工程化收敛 | P2 | Room schema 快照入库；依赖守卫覆盖 `api` 泄漏；死代码 0 处引用；首页接入 `observeSince` 后，新增流水 ≤3 秒内 UI 自动刷新 | 改：`core:backup`、`app:AppStores`、`.gitignore` | 依赖 A1~A4 |

### B 阶段 · 核心功能（P0/P1，产品价值）

| # | 工作 | 优先级 | 可量化验收标准 | 改动/新增模块 | 依赖 |
|---|---|---|---|---|---|
| B1 | 分类管理 UI | P0 | 支持增/删/改分类、设月度预算、收入/支出归属；操作即时持久化并重启不丢 | 新增：`app:CategoryManageScreen`；改：`core:database`(DAO 增补)、`app` 导航 | A2 |
| B2 | 预算与超支提示 | P1 | 分类月度预算进度可视；超支分类高亮；预算数据用「分」无浮点误差（单测覆盖） | 改：`feature:stats`(加 `BudgetProgressMetric`)、`app` 首页/账单 | B1、A2 |
| B3 | 流水标签/备注增强 | P1 | 流水可打标签、按标签筛选；`extras` JSON 读写正确（单测 ≥5 条） | 改：`core:model`(extras 工具)、`core:database`、`app` 记账页 | W1 |
| B4 | 首页/账单实时刷新 | P1 | 删除手动 `load()` 依赖；数据变更后 ≤3 秒刷新 | 改：`app:AppStores`、各 Screen | A5 |

### C 阶段 · 增强功能（P2）

| # | 工作 | 优先级 | 可量化验收标准 | 改动/新增模块 | 依赖 |
|---|---|---|---|---|---|
| C1 | 多账户/多币种 | P2 | 新增账户与币种不破坏统计；汇率换算无浮点误差 | 改：`core:model`、`core:database`、`feature:stats`、`app` | A、B |
| C2 | 云同步接入 | P2 | 实现真实 `CloudSyncClient` 后端适配；双端写入后同步一致（离线场景可测） | 新增：`feature:sync`（或 app 内实现）；改：`core:database` | A、B |
| C3 | 加密备份 UI + 口令找回提示 | P2 | 加密导出/导入完整可用；口令错误给出明确提示不崩 | 改：`core:backup`、`app` 设置页 | A2 |
| C4 | 换机数据迁移（Android↔Android） | P2 | Wi-Fi 热点直连 + 扫码配对；分块断点续传；SHA-256 + 条数双重校验；迁移后两端条数一致 | 新增：`feature:transfer`；改：`core:backup`、`app` | A2、C3 |

> **跨平台迁移与版本演进顺序**（见 `docs/migration-design.md`）：
> - **版本顺序：先 Android，后 iOS。** 现阶段聚焦 Android；iOS 版在 Android 版全部开发完成后再启动，避免两端并行的成本与返工。
> - **双向迁移策略**：Android 与 iOS 的本地加密/存储实现不同，无法用「设备直连」承载同一格式；因此以**平台无关的导出格式（版本化 JSON + 口令加密，即 `BackupManager` 的信封）作为统一媒介**——Android 导出 → iOS 导入、iOS 导出 → Android 导入，经文件/云端中转完成双向迁移。
> - **C4 的依赖**：A2（设置入库并入备份，否则时薪/目标迁不过去）+ C3（加密导出可用）是前置。

### D 阶段 · 发布开源（P3，全部开发完成后）

| # | 工作 | 优先级 | 可量化验收标准 | 涉及 | 依赖 |
|---|---|---|---|---|---|
| D1 | Git 初始化 + 首次提交 | P3 | `.git` 建立；`git log` 有完整提交；`.gitignore` 正确排除构建产物/密钥 | 仓库根 | C 完成后 |
| D2 | 推送到 GitHub | P3 | 远程仓库存在且 `git push` 成功、分支一致 | 远程 GitHub 仓库 | D1 |
| D3 | 开源文件 | P3 | `README.md`（含架构/构建/贡献说明）、`LICENSE`、`CONTRIBUTING.md`、`CHANGELOG.md` 齐全 | 仓库根 | D2 |
| D4 | CI 与签名 | P3 | CI（build + 单测 + 上传 APK）全绿；release 签名配置可用 | `.github/workflows`、`app/build.gradle.kts` | D2 |

## 二、模块依赖关系（关键链路）

- **A1(分类解耦) 是 B1 的前提**：分类 UI 与预算都要在分类引擎可单测、可扩展的基础上做。
- **A2(设置入库) 是 B2(预算) 的前提**：预算金额必须走「分」+ 可备份，不能在 SharedPreferences 里继续用 Float。
- **A5(实时刷新) 是 B4 的同一个动作**，合并执行。
- **C 全部依赖 A+B**：多账户/云同步/加密备份都建立在数据模型与写入一致性之上。
- **D 依赖 C 全部完成**：开源发布放在最后，避免半成品进入公开仓库。

## 三、执行节奏建议

1. **并行推进 A1/A2/A3/A4**（互不依赖），A5 收尾。
2. **A 全部绿后进 B**，B1→B2、B3、B4 可部分并行。
3. **C 按 C1→C3→C4→C2** 顺序（云同步最后，风险最高；C4 换机迁移依赖 C3 加密导出）。
4. **D 一次性完成**：Git 初始化 → GitHub 推送 → 开源文件 → CI/签名。

## 四、GitHub 推送前的待确认项（届时需要你提供）

1. **远程仓库地址**（你的 GitHub 用户名 + 仓库名，或已有仓库 URL）；
2. **公开还是私有**（开源默认公开，需你确认）；
3. **推送鉴权**：本机当前无 `gh` CLI、无 GitHub Token、无 git 身份（`user.name/email` 为空），届时需要配置其中一种；
4. **开源许可证类型**（MIT / Apache-2.0 / GPL-3.0，属法律选择，需你定）。

> 进度基线：**A 阶段（技术债）全部完成**——W1 数据模型扩展、A1 分类解耦（`RuleSource`）、A2 设置入库（Room + 金额「分」+ 纳入备份）、A3 渠道能力化（`CaptureAction`，UI 去 `when(source.id)`）、A4 写入一致性（事务化写、清空二次确认、枚举严格、账户匹配收紧）、A5 工程化收敛（schema 快照入库、API 泄漏检查、死代码清理、首页 Flow 实时刷新，经 QA 两轮复核 PASS）。
> **B 阶段进行中：B1 ✅ / B2 ✅ / B3 ✅** — B3 新增领域编码器 `TxnExtras`(core:model，JSON 编解码，容错解析，9 条单测)；`LedgerStore` 加 `setTags/allTags/setTagFilter`；记账页支持行内打标签、移除标签、常用标签建议、按标签筛选。为 `extras` 引入 `kotlinx-serialization-json`（仅 JsonElement API，无需编译器插件；避免 org.json 在单测被 stub）。
> **🔥 真机验证发现并修复「整库加密从未生效」**：① `CryptoBox.seal()` 把自带 IV 传给 Android Keystore → `InvalidAlgorithmParameterException: Caller-provided IV not permitted`（Android 14 必现），修为不传 IV、用 `cipher.iv` 取回；② sqlcipher-android 4.5.5 **不会自动加载原生库**（javap 核实 jar 内无任何 `System.loadLibrary`，旧版 `loadLibs()` 已移除）→ 首次开库 `UnsatisfiedLinkError`，修为显式 `System.loadLibrary("sqlcipher")`。此前两者都被"静默降级明文"掩盖。现真机 DB 头部为随机字节、**加密已真正生效**，回归 173 用例全绿。
> **🛡️ 加密防回归护栏（已落地）**：`SqliteHeader.isPlaintext(bytes)`（纯函数，5 条单测）+ `SqlCipherSupport.isPlaintextDatabase()`；每次开库都验证文件头确为随机字节，否则视为失败。加密**最多尝试 3 次**，超限则放弃加密并**弹窗明确告知用户**（明文存储，但通知/短信原文仍经 Keystore 密封）；曾加密过（有旧数据）一律显式报错、绝不降级。真机复验加密仍生效、无误伤。
---

## 五、最新进度（以此为准）

> ⚠️ 上方零散的进度记录已过时且互相矛盾（同时出现 178 与 169 两个测试数，且把已完成的 C5 标注为"待做"）。
> **准确状态以代码取证的审计报告为准 → [docs/review/roadmap-audit.md](review/roadmap-audit.md)。**

**截至 2026-09-27 的实际状态**：

| 阶段 | 状态 |
|---|---|
| A 技术债清理 | ✅ 全部完成（W1–W6，经 QA 两轮复核 PASS） |
| B 核心功能 | ✅ **B1 / B2 / B3 / B4 全部完成** —— B4 已把 6 个 Store（账单 / 记账 / 自由 / 发现 / 分类 / 退款 / 采集箱）改为 Room Flow 订阅；`SettingsStore` 因低频统计保留任务型（合理例外） |
| C 增强功能 | ⚠️ **C5 退款、C3 加密备份 UI 已完成**；C1 仅"多账户"落地、**多币种未实现**；C2 云同步 / C4 换机迁移 **未开始** |
| D 发布开源 | 🔄 进行中（已补 Gradle wrapper、修 CI 全量测试、接入 release 签名、补齐 LICENSE / CONTRIBUTING / CHANGELOG / SECURITY） |

**验证基线**：`./gradlew test --rerun-tasks` → **324 用例 / 0 失败**（覆盖 10 个模块）；`:app:assembleDebug` 通过；`tools/static_check.py` 通过；真机（Android 13 / 14）运行态验证通过。

**未完成项分类**：

- **需产品决策**：C1 多币种（当前 `Model.kt` 硬编码 CNY，退款与备份路径同样写死）、C4 换机迁移
- **需外部依赖**：C2 云同步（需要真实后端服务，目前仅 `CloudSyncClient` 接口 + `NoopCloudSyncClient`）
