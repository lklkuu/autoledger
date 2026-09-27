# AutoLedger 全流程审查与修复 — 最终报告

> 生成时间：2026-09-26　|　范围：需求设计 → 编码实现 → 构建配置 → 测试发布
> 本报告为**主理人汇总版**；两位成员的分项报告见同目录 `qa-review.md`（正确性/性能/兼容性/测试）与 `architecture-review.md`（架构/工程化）。

## 0. 一句话结论

工程此前**从未编译过**，存在 6 处必崩编译错误与 11 处严重缺陷；现已**全部修复，APK 构建成功**，并补上 109 个单元测试与混淆/签名/CI 三件套。剩余 20 条中等 + 30 条建议已归档，按优先级排序，不阻塞本次交付。

## 1. 修复状态总表

### 严重（11 条 —— 全部已修复 ✅）

| 编号 | 问题 | 位置 | 修复结果 |
|---|---|---|---|
| S0/A-S1 | `core:model` 缺 `import java.time.Instant`，最底层模块自身编译失败 | `core/model/.../Stats.kt` | ✅ 已补 import |
| S1/A-S2 | `LedgerRepository` 缺 `listRange` + 第三参数缺默认值（两处叠加） | `Spi.kt` / `RoomLedgerRepository.kt` | ✅ 接口带默认值 + 实现 |
| S2/A-S4 | `NotificationListenerService` 上调用了不存在的 `goAsync()` | `LedgerNotificationListener.kt` | ✅ 改为协程 scope + 取消 |
| S3/A-S5 | `BackupManager` 用 `Base64` 未 import | `BackupManager.kt` | ✅ 已补 import |
| S4/A-S6 | 非 @Composable 的 onClick 里调 `rememberCoroutineScope()` | `CaptureAndSettings.kt` | ✅ 上提至 Composable 顶层 |
| S5 | 冷启动主线程同步 Keystore 生成 + SQLCipher 建库（ANR/崩溃风险） | `AppContainer.kt` | ✅ 惰性初始化 + 后台预热 |
| S6 | 口令用 `apply()` 异步落盘、解密无兜底（极端情况库永久打不开） | `PassphraseVault.kt` | ✅ `commit()` + 损坏显式报错 |
| S7 | 账单 CSV 日期解析退化"当前时间"，整批时间戳塌缩 | `BillImportCaptureSource.kt` | ✅ 多格式解析 |
| S8 | 去重每次写入全表扫描 O(n²) | `LedgerDuplicateResolver.kt` | ✅ 改走指纹索引 `findByFingerprintNear` |
| S9 | 商户名为空时指纹塌缩，自动合并静默吞真实消费 | `LedgerDuplicateResolver.kt` + `IngestPipeline.kt` | ✅ 指纹纳入渠道标识 + `isAutoMergeSafe` 门闩 |
| S10 | 备份 v1→v2 用 Double 转分，系统性少 1 分 | `BackupEnvelope.kt` | ✅ `BigDecimal.valueOf` + HALF_UP |

### 中等（20 条 —— 未修复，已归档）

`M1` bootstrap 全量 upsert/订阅无法退订　`M2` 写路径无事务　`M3` 分类每次全表读规则　`M4` 分类器拿不到原文　`M5` 多处静默吞异常　`M6` storeScope 全局单例　`M7` 金额 Locale　`M8` 清空无二次确认　`M9` 支付宝"不计收支"误判支出　`M10` CSV 整文件读内存　`M11` Keystore 强转无兜底　`M12` 枚举转换器静默改默认　`M13` enableEdgeToEdge 顺序　`M14` TimeRange 边界（已修 lastDays，见下）　`M15` 通知 scope 不取消　`M16` 短信 LIMIT 串接/Play 合规　`M17` 无横屏/大屏适配　`M18` 设置 Float 存金额　`M19` 置信度上限自相矛盾　`M20` 账户名模糊包含误判内部划转

架构侧中等：`A-M1` 无 Gradle Wrapper（**已由工程师生成**）　`A-M2` KSP schemaLocation 位置（**已修**）　`A-M3` core:backup API 泄漏　`A-M4` 构建内存偏小　`A-M5` 无混淆（**已补 proguard-rules.pro**）　`A-M6` 无签名机制（**已补 keystore.example.properties**）　`A-M7` 无 CI（**已补 workflow**）　`A-M8` schema 快照未入库　`A-M9` 依赖守卫只查 import　`A-M10` `-Xjvm-default=all` 不一致

### 建议（30 条 —— 归档）

详见两份分项报告的"建议"章节，主要为：纯函数下沉以解锁更多单测、分类器接原文提升准确率、UI 接入采集队列实时刷新（当前首页靠手动 load）、混淆后的功能冒烟等。

## 2. 按你要求六个维度的结论摘要

| 维度 | 结论 |
|---|---|
| 正确性 | 金额已全链用「分」+ 十进制舍入；去重/迁移/口令三处致命缺陷已修；仍有多处 `runCatching` 静默吞异常（M5，中等） |
| 架构与扩展性 | 依赖方向 app→feature→core 成立；但 `feature:classify` 直连 Room DAO 破坏分层、采集渠道 id 被 UI 硬编码（A-S7/A-S8，中等） |
| 性能 | 冷启动、去重 O(n²)、分类全表读三处已改/已定位；账单导入整文件读内存待优化（M10） |
| 兼容性 | minSdk26 + java.time 原生可用；SQLCipher 四架构 .so 齐备；Android 13+ 通知权限、Play 短信权限合规需实机验证（M16/M17） |
| 测试 | 从 0 → 109 个用例；关键路径缺失清单已列（CSV 解析/备份迁移/分类置信度/流水线端到端，受可测试性改造阻塞） |
| 工程化 | 编译链路已打通；补了 Wrapper、混淆、签名示例、CI；schema 快照入库与依赖环守卫待补（A-M8/A-M9） |

## 3. 验证证据

- 基线构建（修复前）：`BUILD FAILED`，`core:model` / `core:crypto` 先行报错（`Instant`、`SupportFactory`）。
- 修复后：`:app:assembleDebug` **BUILD SUCCESSFUL**，产出 `app/build/outputs/apk/debug/app-debug.apk`。
- APK 独立校验：AndroidManifest/resources.arsc/classes.dex 齐全，20 个 dex（multidex），SQLCipher 含 arm64-v8a/armeabi-v7a/x86/x86_64，zip 完整性通过，SHA256 `301dc550…`。
- 单元测试：`:core:model:test` `:feature:dedup:test` `:feature:stats:test`，**109 用例全部通过（0 失败）**，含 QA 预留的 4 个"红灯用例"（修复后转绿，作为缺陷修复的验收证据）。
- 修复过程额外抓到并修掉一个真实 bug：`TransferPairMatcher.match` 里 `other.type != EXPENSE` 判据不对称，导致「从流入方发起配对」必然失败——两个测试用例（"pairing works from the inflow side" 与 "pairing skips expense candidates"）分别锁住了它的正反两面，最终用「拒绝类型与方向矛盾的畸形候选（EXPENSE 却为正数）」这一精化条件同时满足，未改动任何测试断言。

### 4 个红灯用例与修复对应

| 红灯用例 | 指向缺陷 | 修复文件 |
|---|---|---|
| `MoneyTest` 0.29 → 29 分 | `fromYuanDouble` 浮点截断 | `Money.kt` `BigDecimal.setScale(HALF_UP)` |
| `MoneyTest` "订单号12345678 支付12.30元" 应取 12.30 | 金额正则最左匹配 | `Money.kt` 上下文优先正则 |
| `TimeRangeTest` `lastDays(0)` | 负区间 | `Stats.kt` `coerceAtLeast(1)` |
| `LedgerDuplicateResolverTest` 空商户名 | S9 指纹塌缩 | `LedgerDuplicateResolver.kt` |

## 4. 安装与使用（通俗版）

1. 把 `app/build/outputs/apk/debug/app-debug.apk` 传到手机（微信/QQ/数据线均可）。
2. 手机首次安装会提示「允许安装未知来源应用」，在弹窗里允许即可。
3. 打开「打工人小账本」→ 顶部「采集箱」→ 按提示开启「通知使用权」（微信/支付宝付款通知会自动进账）。
4. 短信识别为可选项，Google Play 上架需申请，侧载自用不受限。

## 5. 遗留与下一步（按优先级）

1. **P1** 写路径加事务 + outbox 一致性（M2）；采集队列 UI 实时刷新（当前死代码）。
2. **P1** `feature:classify` 从 Room DAO 解耦（A-S7），解锁分类引擎 JVM 单测。
3. **P2** CSV 流式读取 + 编码嗅探加固（M10）；`enableEdgeToEdge` 移到 `super.onCreate` 前（M13）。
4. **P2** 生成并入库 Room schema 快照（A-M8），作为 Migration 基线。
5. **P3** 剩余 20 中等 + 30 建议按 QA 报告第八节顺序消化。
