# AutoLedger 体积优化与解耦重构 · 实施文档

| 项 | 值 |
|---|---|
| 文档日期 | 2026-10-03 |
| 基线提交 | `3ff7733` |
| 目标体积档位 | **档位 A —— 用户实际下载包 ≤ 8 MB** |
| 文档状态 | **执行中**（S0–S3-b 已完成，S4/S5 待执行） |
| 当前位置 | S1 ✅ `9081883`；S2 ✅ `0e5d80e`；S3-a ✅ `b8a87d0`；S3-b ✅ `2aca198`（**均只提交未推送**） |

---

## 1. 技术栈与项目结构入口

**技术栈**：Kotlin 2.0.21 / Jetpack Compose（Compose BOM 2024.12.01，Material3）/ Room 2.6.1（KSP）/ SQLCipher 4.5.5 / Coroutines 1.9.0；构建 **Gradle 8.9 + AGP 8.7.3**，依赖统一走 **version catalog** `gradle/libs.versions.toml`。平台参数：minSdk 26 / compileSdk 35 / targetSdk 35。

**结构入口**：
```
F:\WorkBuddy\2026-09-26-16-05-49\AutoLedger\
├─ settings.gradle.kts          ← 模块清单入口（实际 12 个模块）
├─ gradle/libs.versions.toml     ← 依赖版本单一真源
├─ app/build.gradle.kts                ← 签名 / buildTypes / splits（体积优化主战场）
├─ app/src/main/java/com/autoledger/app/
│   ├─ di/AppContainer.kt                  ← DI 装配入口
│   ├─ ui/screens/…                      ← 四个菜单页（记账 / 账单 / 发现 / 自由）
│   ├─ ui/stores/…                       ← Store 状态层
│   └─ ui/components/…                    ← 复用组件
├─ core/{model,crypto,database,backup}
├─ feature/{capture,classify,dedup,stats,refund,platform,transfer}
└─ docs/{design,review,repro,donate}
```

---

## 2. 体积基线（实测字节，非估算）

| 分组 | 包内字节 | MiB | 占 APK |
|---|---:|---:|---:|
| classes*.dex | 11,212,355 | 10.69 | 33.32% |
| lib/x86_64 | 6,290,128 | 6.00 | 18.65% |
| lib/arm64-v8a | 5,725,352 | 5.46 | 16.97% |
| lib/x86 | 5,316,528 | 5.07 | 15.76% |
| lib/armeabi-v7a | 4,006,600 | 3.82 | 11.88% |
| res/ | 664,710 | 0.63 | 1.97% |
| 其它 | ~438,897 | 0.42 | 1.25% |

- **native 合计 21,338,608 B（20.35 MiB）＝ 63.26%**；dex + native ＝ **96.6%**
- 未压缩合计 65,723,332 B；ZIP 内 33,654,570 B（dex 压缩率 3.86×，**`.so` 全部 STORED**）
- res/ 共 **39 个 PNG**（23 自有 + 16 个 androidx.core 的 `.9.png`）+ 26 个 XML，无 layout（全 Compose）

---

## 3. 总体目标与阶段划分

**总体目标**：不改变任何业务语义与显示口径，把**用户实际下载包**从 33.73 MB 降到 **≤ 8 MB**；同时降低模块耦合，让依赖方向与分层能被 CI 守护。

| 阶段 | 目标 | 主要动作 | 预估收益 |
|---|---|---|---|
| **S0 签名固定化** | CI/本地同签名 | `signing/autoledger.jks` + 4 个 Secrets 注入 | ✅ 已完成（v1.1.6 发布，CI 全绿） |
| **S1 ABI 分包** | 去掉模拟器架构 | `splits { abi { include("arm64-v8a","armeabi-v7a") } } }` | ✅ `9081883`：arm64 18,076,367 B（-46.4%）／armv7 16,360,463 B（-51.5%） |
| **S2 开 R8** | 剔除未用代码/资源 | `isMinifyEnabled` + `isShrinkResources` + 复用已有 `proguard-rules.pro` + 新增 `res/raw/keep.xml` | ✅ `0e5d80e`：universal 33,730,618 → 23,759,191 B（-29.56%）；dex 43.24 → 3.31 MB（2 dex→1） |
| **S3 资源精简** | 语言收敛 + 资源压缩 | S3-a `resourceConfigurations += setOf("zh")`；S3-b 23 个自有 PNG→无损 WebP | ✅ `b8a87d0` + `2aca198`：S3-a -100,500 B + S3-b -239,345 B ≈ **-339,845 B/包**（arm64 8,104,952 → **7,764,689 B**，差值含 ±420 B 构建抖动，见 §8-7） |
| **S4 依赖裁剪** | 移除未使用/可轻量替代依赖 | 逐条 grep 核查 | ⏳ 待执行 |
| **S5 职责拆分** | 上帝文件按职责拆开 | 纯 move，一处一 PR | ⏳ 待执行 |

---

## 4. 需要解耦的模块与依赖关系

**实际依赖矩阵**（行依赖列，A=`api`，I=`implementation`）：

| ↓依赖 \ →被依赖 | model | crypto | database | backup |
|---|:-:|:-:|:-:|:-:|
| core:database | A | I | — | |
| core:backup | A | I | A | — |

feature 层 7 个模块：**只依赖 `core:model`**，彼此零横向依赖。

**违规判定（全部为"无"）**：循环依赖、反向依赖、feature 横向依赖、纯 JVM 模块 Android 污染、`api` 误用泄漏、重复依赖/版本冲突。

---

## 5. 改造方案与预期收益（按优先级）

### 严重

**S1 · `app/ui/stores/AppStores.kt`（1282 行，9 个 Store 类）**
- **问题位置**：`app/src/main/java/com/autoledger/app/ui/stores/AppStores.kt`（1282 行）
- **原因**：9 个彼此无共享私有状态的 Store（`HomeStore` `LedgerStore` `CaptureStore` `FreedomStore` `InsightsStore` `SettingsStore` `RefundStore` `CategoryStore` `UserPlatformStore`）挤在一个文件，改任一都撞车；`LedgerStore`（216-516 行，300 行逻辑）无法独立 review
- **建议**：按 1 类 1 文件拆到 `app/ui/stores/`
- **预期影响**：最长文件 1282 → ~250 行；git 冲突面按域隔离；纯机械改动零风险（第一个 PR 只 move 不改逻辑）

**S2 · Room `@Entity` 泄漏进 Composable 签名**
- **问题位置**：`AppStores.kt:6,1027`（`allocations: List<com.autoledger.core.database.RefundAllocationEntity>`）、`RefundScreen.kt:125`
- **原因**：持久化模型（DB 表结构）直接进了 UI 状态与 Composable 参数签名（签名里写着全限定 `com.autoledger.core.database` 包名）
- **建议**：`core:model` 或 `app/ui/model/` 定义 `RefundAllocationView` 视图模型，Store 负责 Entity → View 转换
- **预期影响**：DB 表结构变更不再穿透到 UI 层

**S3 · 金额格式化三套并存 + `withSign` 死参数**
- **问题位置**：`core/model/.../ExpenseMath.kt`/`Money.kt:52`（`val sign = if (withSign) "" else ""` **两分支完全相同**）、`app/.../ui/components/Common.kt:51`（private 常量）、`AppContainer.kt:400` / `TxnEditing.kt:168` / `HomeScreens.kt:176,178`（4 处裸 `/100.0`）
- **原因**：同一语义（1 元 = 100 分）三套实现；core 侧 `withSign` 从未生效（真正吞负号的是另一条路径），app 侧有人为此绕圈补偿
- **建议**：`core/model` 建 `MoneyFormat.kt` 收口 `FEN_PER_YUAN`（public）+ 单一 `formatYuan`，app 侧 `Long.yuan()` 改别名或删，4 处裸除法改调用
- **预期影响**：口径收敛到 1 处；⚠️ 修 `withSign` 属**真实行为变更**，需单独验 `InsightScreens` 等调用点观感

### 中

| # | 问题位置 → 原因 → 优化建议 → 预期影响 |
|---|---|
| M1 | `app/.../ui/screens/CaptureAndSettings.kt`（849 行；`SettingsScreen` 单函数 582 行；`:827` `copyImageToPrivate` contentResolver 文件 IO、`:840` `openExternalUrl` 外部 Intent）→ 文件名与内容不符（68% 是 Settings）+ **平台逻辑泄漏在 `@Composable` 所在的 screens 文件** → 拆 `SettingsScreen.kt` / `CaptureScreen.kt` / `PlatformIntents.kt` → 平台适配不再动 UI 文件 |
| M2 | `app/.../ui/theme/Tone.kt:64-70` vs `Color.kt:11-48,71-81` → 6 个色值两处各写一份；`BRAND` 深色 `#6FD6B4` 在 `Color.kt` **不存在**（孤儿值）→ `toneHex()` 改为从 `LedgerPalette` 派生，或补 4 个 token + 加断言让双写被 CI 抓住 → 改配色从 2 处变 1 处 |
| M3 | `app/.../UserSettings.kt:3-5,25`（直握 `SettingsDao`）/ `app/.../refund/RefundService.kt:3,16`（直握 `RefundRepository`）→ app 直接依赖 `core:database` 具体实现，"设置"这条线**没有 SPI** → 在 `core:model` 补 `SettingsRepository` 接口、`RoomSettingsRepository` 实现 → app 与 Room 解耦 |
| M5 | `feature/classify/build.gradle.kts`（`android.library` 插件 + AndroidManifest，却**零 Android 依赖**；未进 `static_check.py` 的 `JVM_MODULES`）→ 降级为 `kotlin.jvm`（与纯 JVM 的 dedup/stats 一致）→ 分类逻辑可脱离 Robolectric 纯 JVM 秒级跑 |

### ✅ 无显著问题的维度（不凑数）

- **模块循环依赖 / 反向依赖**：无（依赖矩阵严格下三角）
- **feature ↔ feature 横向依赖**：无（7 个 feature 跨模块 import 全指向 `core:model`）
- **纯 JVM 约定违反**：无（5 个纯 JVM 模块全量 grep Android 符号 **0 命中**）
- **`api` 误用 / 依赖泄漏**：无（全仓仅 3 处 `api`，均有签名层面正当理由，未扩散到上层）
- **阈值未常量化**：无（3 分钟去重窗 / 10 分钟划转窗 / 5000 条 outbox / 6 个月趋势全部已常量化）
- **平台 ID / 来源 ID / 类型枚举裸写**：无（全走 `PlatformCatalog` / `CaptureSourceIds` / 枚举）
- **反射式类名 / 包名耦合**：无实质案例（唯一 1 处是 Intent extra 标准写法）

### ⛔ 明确不建议改

- **`.so` 保持未压缩 / 不开 `useLegacyPackaging` / 不改 `extractNativeLibs`**：项目注释已**实证**——Android 14 不再解压、直接从 APK 映射加载，压缩的 `libsqlcipher.so` 无法映射 → `System.loadLibrary("sqlcipher")` 失败 → **静默降级为明文库**（真机 Android 14 / arm64 已复现）；本次实测 8 个 `.so` 全部 STORED，状态正确
- **SQLCipher 不可替代**：本地账本加密是核心安全诉求；Java 层仅 50 KB，代价全在 `.so`，只能靠 ABI 分包减，不能靠换库减
- **`kotlinx-serialization-json` 不替换为 `org.json`**：`core/model` 注释已说明这是为避开 `org.json` 在 Robolectric/纯 JVM 单测被 android.jar stub 的**有意可测性权衡**，收益小而回归风险实打实

---

## 4. 实施顺序与风险控制点

**顺序**：S0（✅）→ S1（✅）→ S2（✅）→ S3 → S4 → S5

| 阶段 | 风险控制点 |
|---|---|
| **S1** ✅ 已提交 `9081883` | 只 include `arm64-v8a` + `armeabi-v7a`；**必须保留 `isUniversalApk = true`** 兜底 |
| **S2** ✅ 已实现（构建验证通过） | 复用现有 `proguard-rules.pro`，**不新写规则**；⚠️ **必须新增 `res/raw/keep.xml` keep `@drawable/donate_wechat`**（该资源靠 `resources.getIdentifier("donate_wechat", …)` 按字符串动态解析，静态引用链看不到，R8 可能删掉 → 微信收款码静默消失） |
| **S3 资源精简** ✅ `b8a87d0` + `2aca198` | 语言收敛零风险；WebP 全部走**无损**且逐个解码回读做**像素级比对**（23/23 一致），观感在数学上不可能变；`.9.png` 一条未动（自有资源里 0 个，库里 16 个原样保留）；⚠️ 真机只需确认**桌面图标与捐赠收款码**两处 |
| **S4 依赖裁剪** ⏳ | 逐条 grep 核查后再删；保留 `kotlinx-serialization-json`（有意的可测性权衡） |
| **S5 职责拆分** ⏳ | 纯 move，一处一 PR，不动逻辑；拆完跑全量单测 |

---

## 5. 验证方式（每阶段）

```bash
cd "F:/WorkBuddy/2026-09-26-16-05-49/AutoLedger"
export JAVA_HOME='D:/Android/jdk17'
export ANDROID_HOME='D:/Android/sdk'

# 1) 构建 release（体积 / 签名验证）
./gradlew :app:assembleRelease
# 核对产物：app-arm64-v8a-release.apk / app-armeabi-v7a-release.apk / app-universal-release.apk

# 2) 静态一致性（依赖方向 / 分层 / 包名 / API 泄漏）
python3 tools/static_check.py

# 3) 全量单测（必须带 --rerun-tasks，防构建缓存假绿）
./gradlew test --rerun-tasks
```

每阶段需留存的实测数据：APK 字节数（release 与各 ABI 分包）、单测总数 / 失败数、是否出现 R8 `missing_rules.txt` 告警。

---

## 6. 进度状态（实时更新）

| 阶段 | 状态 | 实测数据 |
|---|---|---|
| **S0 签名固定化** | ✅ 已完成（v1.1.6 已推送，CI 全绿） | CI 与本地同签名；钥匙不入库 |
| **S1 ABI 分包** | ✅ 已提交 `9081883` | universal 33,730,618 B（不变，兜底包）；**arm64 18,076,367 B（-46.4%）**；**armv7 16,360,463 B（-51.5%）** |
| **S2 开启 R8** | ✅ 已提交 `0e5d80e` | universal 33,730,618 → **23,759,191 B（-29.56%）**；arm64 → 8,104,952 B；armv7 → 6,389,048 B；dex 43.24 → 3.31 MB（2 dex→1）；mapping 目录**无 `missing_rules.txt`** |
| **S3-a 语言收敛** | ✅ 已提交 `b8a87d0` | 语言配置 84 → 0；`resources.arsc` 116,912 → **16,412 B（-85.96%）**；三包各 **-100,500 B**（universal 23,658,285／arm64 8,004,034／armv7 6,288,130） |
| **S3-b PNG→WebP** | ✅ 已提交 `2aca198` | 23 个自有 PNG 677,022 → 411,442 B（全无损，像素逐字节一致）；`res/` 668,513 → 429,109 B；三包各 **-239,345 B**（universal **23,418,940**／arm64 **7,764,689**／armv7 **6,048,785**） |
| **累计（对基线）** | — | 用户实际下载包：arm64 33,730,618 → **7,764,689 B（-77.0%）**；armv7 → **6,048,785 B（-82.1%）** ⇒ 已达 **档位 A（≤ 8 MB）** |
| **S4 依赖裁剪** | ⏳ 待执行 | — |
| **S5 职责拆分** | ⏳ 待执行 | — |

---

## 7. 阶段实测记录

### S1 · ABI 分包（已完成，提交 `9081883`）
构建：`./gradlew :app:assembleRelease` → `BUILD SUCCESSFUL`，`270 actionable tasks / 9 executed`。
产物体积经 `unzip -l` 逐包核对：arm64 包仅含 `lib/arm64-v8a/*`、armv7 包仅含 `lib/armeabi-v7a/*`、universal 含 4 个 ABI（141 files vs 135 files，差 6 = 3 ABI × 2 so），分包语义正确。

### S2 · 开启 R8（已提交 `0e5d80e`）

**改动**：`app/build.gradle.kts` 的 `buildTypes.release` 新增：
```kotlin
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
```
并新增 `app/src/main/res/raw/keep.xml`：`tools:keep="@drawable/donate_wechat"`。

| 产物 | 字节 | MiB | 对比基线 33,730,618 B |
|---|---:|---:|---:|
| `app-universal-release.apk` | 23,759,191 | 22.66 | **-9,971,427（-29.56%）** |
| `app-arm64-v8a-release.apk` | 8,104,952 | 7.73 | **-76.0%** |
| `app-armeabi-v7a-release.apk` | 6,389,048 | 6.09 | **-81.0%** |

**验证证据**：
- 构建 `BUILD SUCCESSFUL in 25s`，`265 actionable tasks: 1 executed, 264 up-to-date`
- `app/build/outputs/mapping/release/` 下有 `configuration.txt` / `mapping.txt` / `seeds.txt` / `usage.txt`，**无 `missing_rules.txt`** ⇒ R8 未报缺失保留规则
- dex 未压缩大小由 43,243,312 B 降到约 3.31 MB（-92.3%），2 个 dex 合并为 1 个（对照组实测）

### S3-a · 语言收敛（已提交 `b8a87d0`）

**改动**：`app/build.gradle.kts` 的 `defaultConfig` 新增 `resourceConfigurations += setOf("zh")`。

| 指标 | 收敛前 | 收敛后 | Δ |
|---|---:|---:|---:|
| 配置总数（`aapt2 dump configurations`） | 91 | 6 | -85 |
| 其中语言配置 | **84** | **0** | -84 |
| `resources.arsc` | 116,912 B | 16,412 B | **-100,500 B（-85.96%）** |
| universal | 23,758,785 B | 23,658,285 B | -100,500 |
| arm64 | 8,104,534 B | 8,004,034 B | -100,500（-1.24%） |
| armv7 | 6,388,630 B | 6,288,130 B | -100,500（-1.57%） |

- 84 个语言配置 × 每语言 85 条字符串 = **7,140 条**，全部来自 androidx 的通知模板文案
  （`call_notification_*` 等，`merged.dir/values-fr/values-fr.xml` 抽查确认）；
  本项目自身文案只在 `values/` 默认目录（88 条），**没有任何 `values-xx` 目录**。
- 包内条目数不变（132／126／126），说明只动 `arsc`，没有误删 `res` 文件。
- ⚠️ **已知副作用**：aapt2 的 `-c zh` 不做子语言匹配，`zh-rCN`／`zh-rHK`／`zh-rTW` 一并被剔除
  （收敛后语言配置实测为 0）。影响面仅限 androidx 那 85 条通知模板文案回落到英文默认值；
  App 自身中文文案全在默认目录，不受影响。若要保住 `zh-rCN`，需写成 `setOf("zh", "zh-rCN")`。

### S3-b · 自有 PNG → 无损 WebP（已提交 `2aca198`）

**改动**：`app/src/main/res/` 下 23 个自有 PNG 全部替换为同名 `.webp`（46 files changed，业务代码零改动）。

| 包 | 转换前 | 转换后 | Δ |
|---|---:|---:|---:|
| universal | 23,658,285 B | **23,418,940 B** | -239,345（-1.01%） |
| arm64 | 8,004,034 B | **7,764,689 B** | -239,345（-2.99%） |
| armv7 | 6,288,130 B | **6,048,785 B** | -239,345（-3.81%） |

- 源资源合计 **677,022 → 411,442 B**；`res/` 未压缩合计 668,513 → 429,109 B；
  包内 webp 23 个、自有 png 0 个；`resources.arsc` 16,412 → 16,436 B（+24 B，资源名未变）。
- **转换清单：转了 23/23，保留 PNG 0 个**。收益前三：`ic_launcher_foreground(xxxhdpi)`
  141,333→84,840、`donate_wechat` 102,739→58,064、`ic_launcher_foreground(xxhdpi)` 84,022→50,024。
- **硬约束落实**：
  - 九宫格：自有资源里 `.9.png` **0 个**（`find` 确认）；库里那 16 个 PNG（含 9 个 `.9.png`）原样保留。
  - `donate_wechat` 走**无损**（Pillow `lossless=True, exact=True`），611×611 RGB。
- **观感自查方法**：每转一个就解码回读，把原图与 WebP 都转 RGBA 后**逐字节比对像素**——
  23/23 尺寸一致 + 像素完全一致，因此收款码可扫性、图标观感在数学上不可能发生变化；
  23/23 体积均变小，无一需要回退。
- **引用点自查（4 条链全部仍解析）**：`AndroidManifest` 的 `@mipmap/ic_launcher`／`ic_launcher_round`；
  `mipmap-anydpi-v26/*.xml` 的 `@mipmap/ic_launcher_background`／`_foreground`；
  `AppContainer.kt` 的 `R.drawable.notify_small_icon`／`notify_large_icon`；
  `DonationConfig` → `resources.getIdentifier("donate_wechat", …)`。全部按**资源名**解析，
  与扩展名无关；构建后 `resources.arsc` 字符串池中 `donate_wechat`／`notify_small_icon`／
  `ic_launcher_foreground` 均存在，实测确认。

### 风险与后续验证（必须做，不能省）

开启 R8 是本轮唯一带**真实行为变更风险**的改动，发布前必须完成以下冒烟：
1. **打开 DB 校验文件头不是 `"SQLite format 3"`** —— 防 SQLCipher 被 R8 破坏后静默降级为明文库
2. Room 增删改查 + 各统计页（消费结构 / 商户排行 / 月度趋势 / 本月收入与结余）
3. 全部 Compose 页面，尤其**换机迁移 C4 流程**（Socket + 加密重封装，反射密集）
4. **捐赠弹窗能正常显示微信收款码**（`keep.xml` 生效验证；S3-b 后该图已是 WebP，需一并确认能显示且能扫）
5. **桌面图标正确显示**（S3-b 把 5 档 `ic_launcher`／`ic_launcher_round`／`_background`／`_foreground`
   全部换成 WebP。像素级一致，但自适应图标在真机 launcher 上的合成效果需肉眼确认一次）

---

## 8. 待确认 / 未决项

1. **ABI 分包后 versionCode 冲突**：当前三个包 versionCode 全为 7（AGP 不会自动加偏移）。若将来走 Play / 多渠道分发会冲突，需加 `versionCodeOverride`（编码方案待你定）；当前只发 universal 单包则无影响。
2. **深色模式实际观感**：QA 按色值算出的对比度（6.25:1 等）已达标，但实际渲染观感（尤其 BRAND `#6FD6B4` 与 EXPENSE `#5FC7AC` 两者自身仅 1.17:1，并排时能否可靠区分）需真机截图复核。
3. **版本号命名**：CHANGELOG 当前把这些改动写在 `## [1.1.5]` 段下；若你希望本轮改造独立成 `1.1.6`，需要调整 CHANGELOG 段落与 `versionName`。
4. **10 个 legacy launcher PNG 是死资源（建议删，未动手）**：`mipmap-{mdpi..xxxhdpi}/ic_launcher.png`
   与 `ic_launcher_round.png` 共 10 个（源资源约 262 KB）。minSdk 26 + `mipmap-anydpi-v26/ic_launcher.xml`
   存在 ⇒ anydpi-v26 优先级高于密度桶，且不存在 API<26 的设备 ⇒ 这 10 个**永远不会被选中**。
   删掉可再省约 260 KB 包内字节，属 §5 第 5 项原本就列的动作，等你点头。
5. **文档里 `donate_wechat.png` 措辞已过时（未动手）**：`DonationConfig.kt` KDoc 3 处、
   `res/raw/keep.xml` 注释 2 处、`docs/donate/README.md` 1 处仍写 `.png`，实际文件已是 `.webp`。
   资源名没变所以不影响运行，纯文案问题。
6. **本机构建有间歇性 Windows 文件访问失败**（与代码无关）：`shrinkReleaseRes` 写 `resources.txt`
   报"拒绝访问"、`compileReleaseKotlin` 报无明细的 Compilation error、`mergeReleaseResources`
   incremental 目录不一致、`:app:clean` 删不掉目录——**全部重试即过**，S1–S3 期间共遇到 6 次。
   CI 若遇到同类报错，建议先原样重试一次再排查。
7. **APK 字节数有 ±420 B 抖动**：同一份代码重复 `assembleRelease`，三个包都会差几百字节
   （ZIP 时间戳／对齐元数据），不要按"必须逐字节相等"来卡验收。
