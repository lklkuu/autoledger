# 贡献指南

感谢你有兴趣改进 AutoLedger。本项目以 **GPL-3.0** 发布，提交贡献即表示你同意以同一许可证授权你的贡献。

## 开发环境

| 依赖 | 版本 |
| --- | --- |
| JDK | 17 |
| Android SDK | compileSdk 35 / minSdk 26 |
| Gradle | 8.9 —— **用仓库自带的 wrapper（`./gradlew`），不要用系统 gradle** |
| Python | 3.x（仅静态检查脚本需要） |

Windows 下若构建时报找不到 JDK，先设置：

```bash
export JAVA_HOME='D:\Android\jdk17'
export ANDROID_HOME='D:\Android\sdk'
```

> **`./gradlew` 提示无法下载 Gradle 发行包？** 部分网络环境访问 `services.gradle.org` 会失败。
> 把 `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 临时替换为
> `https\://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip` 即可（**提交前请改回官方地址**）。
> 也可直接用本机已安装的 Gradle 8.9 执行，命令等价。

## 构建与测试

```bash
./gradlew :app:assembleDebug        # 构建 debug APK
./gradlew test --rerun-tasks        # 全量单测（10 个模块）
python3 tools/static_check.py       # 静态一致性校验
```

> **`--rerun-tasks` 不能省。** 不带该参数时 Gradle 会把已有结果判定为 `UP-TO-DATE` 直接跳过，
> 测试"通过"可能只是缓存结论——等于没跑。这个坑我们踩过，写在这里省得你再踩一次。

## 架构约定

依赖方向**永远**是 `app → feature → core`，反向依赖会被 `tools/static_check.py` 拒绝。

```
app                  Compose UI + 装配（AppContainer）
 ├── feature:capture   采集渠道插件（通知 / 短信 / 账单 CSV / 手动）
 ├── feature:classify  分类引擎插件
 ├── feature:dedup     转账识别 + 跨渠道去重（纯 JVM）
 ├── feature:stats     统计维度插件（纯 JVM）
 ├── feature:refund    退款分摊引擎（纯 JVM）
 └── core:crypto / core:database / core:backup / core:model
```

几条硬性规则：

1. **`core:database` 不得被任何 feature 依赖。** feature 只面向 `core:model` 里的 SPI 契约编程
   （`LedgerRepository`、`TransactionClassifier`、`TransferDetector`、`CaptureSource` …）。
2. **领域类型放 `core:model`**，不要留在 feature 或 database 里。
   （退款领域类型就因为最初放在 `feature:refund` 而被上移——`core:database` 要用它。）
3. **想加能力时优先加数据，而不是加分支。** 新增支付渠道 = 实现一个 `CaptureSource`；
   新增一条通知规则 = 在 `NotificationRule` 里追加一条数据；新增统计维度 = 实现 `MetricProvider`。
   UI 里不应出现 `when (source.id)` 这类硬编码。

## 口径约定（最容易出错的地方）

- **所有支出/退款的聚合必须走 `core:model` 的 `ExpenseMath`**：
  毛支出 `grossExpenseMinor`、退款 `refundMinor`、净支出 `netExpenseMinor = 毛支出 − 退款`。
  不要在 UI 或 Store 层手写 `sumOf { abs(...) }`，否则口径会漂移。
- **`LedgerRepository` 的 `includeTransfers = false` 会同时排除 `TRANSFER` 与 `REFUND`**，
  并不只是字面上的"排除划转"。需要退款参与统计时必须显式传 `true`，再自行按 `type` 过滤。
  这个"参数名与行为不一致"的坑已经害过一次（见 `docs/review/roadmap-audit.md`）。
- **采集侧的显式类型要一路传下去**：解析结果 → `RawEnvelope.explicitType` → `IngestPipeline`。
  不要依赖下游的关键词二次命中来决定"一笔退款是不是退款"。

## 测试要求

- 新增或修改**聚合、解析、口径、状态机**类逻辑，必须配**纯 JVM 单测**（这类逻辑刻意设计成不依赖 Android）。
- 涉及 Room 的用 **Robolectric + 真实数据库**（参考 `core:database` 的集成测试），不要只测夹具。
- Compose UI 测试放在 `src/testDebug`（`ui-test-manifest` 只挂 debug 变体）。
- **禁止为了让测试变绿而放宽断言。** 测试失败时先判断是"引擎缺陷"还是"夹具写错"，修对的那一个。
- 修完一个 bug，顺手确认：**把修复删掉，测试会不会变红？** 不会变红的测试等于没有保护。

## 提交与 PR

提交信息用 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)：

```
feat: 账单 CSV 导入时识别退款方向
fix: 修复首页月份裁剪误删退款导致退款总额恒为 0
docs: 补充 includeTransfers 参数的真实语义
refactor: 把 InsightsStore 的口径计算抽为纯函数
test: 补采集链路 explicitType 传递的护栏
chore: 提交 gradle wrapper
```

PR 前请确认：

- [ ] `./gradlew test --rerun-tasks` 全绿
- [ ] `python3 tools/static_check.py` 通过
- [ ] `./gradlew :app:assembleDebug` 成功
- [ ] 新增逻辑有对应测试
- [ ] PR 描述写清**动机**与**验证方式**（不只写"改了什么"）

## 不要提交的东西

`keystore.properties`、`*.jks`、`local.properties`、`app/build/`、`*.apk`、`*.ledgerbak`（导出的账本）
都已由 `.gitignore` 排除。**尤其不要提交任何真实账本数据。**

## 许可证

本项目以 **GNU General Public License v3.0** 发布，详见 [LICENSE](LICENSE)。
