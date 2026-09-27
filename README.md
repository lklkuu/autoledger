# 打工人小账本 · Android 全自动记账

一个**本地优先、自动采集、自带去重与自学习分类**的个人记账 App。
视觉与信息层级向参考仪表盘对齐，底部功能入口完整保留：**今日 / 时薪 / 记账 / 月结 / 自由 / 发现**。

---

## 一、先说清楚三件事

| 问题 | 结论 |
| --- | --- |
| 能真正做到"全自动"吗？ | **接近，但不是 100%**。唯一可行的路径是 `NotificationListenerService` 读支付通知（无需 root、无需 Hook）。App 做不到在后台替你"偷看"App 内容。 |
| 银行/微信/支付宝有开放接口吗？ | **没有面向个人的开放接口**。官方账单只能通过他们自家 App 导出 CSV，所以本 App 把它做成一个正式渠道：账单文件导入。 |
| 数据去哪了？ | **哪也没去。** 整库 SQLCipher 加密，密钥在系统 Keystore 里，云同步只有接口 + 一个无操作实现，现在不发一个字节。 |

### 自动化程度的现实边界

- 微信/支付宝付款 → 支付通知 → **自动入账**
- 银行卡消费 → 银行 App 通知 or 银行短信 → **自动入账**
- 官方账单对账 → CSV 导入 → **一次性补录**
- 现金/小额 → 手动补记 → **仍然记**

---

## 二、架构

```
                 ┌──────────────── app（Compose UI / 容器装配）────────────────┐
                 │  今日   时薪   记账   月结   自由   发现   + 采集箱 / 设置  │
                 └───────────────────────────┬────────────────────────────────┘
                                             │ 只依赖接口
     ┌───────────────┬───────────────┬───────┴────────┬──────────────────┐
     │ feature:capture│feature:classify│ feature:dedup │ feature:stats    │
     │ 采集渠道插件    │ 分类引擎插件     │ 转账识别/去重  │ 统计维度插件      │
     └───────┬───────┴───────┬───────┴───────┬───────┴───────┬────────────┘
             └───────────────┴───────┬───────┴───────────────┘
                                     │
              ┌──────────┬───────────┼────────────┬────────────────┐
              │core:backup│core:database│ core:crypto│  core:model   │
              │导出/迁移   │ Room+迁移    │ Keystore   │ 模型 + SPI 契约│
              └──────────┴───────────┴────────────┴────────────────┘
```

**依赖方向永远是 `app → feature → core`，反向依赖在 lint 里被拦住**（见 `tools/static_check.py` 第 6 项）。

> 图中 `feature` 层还包含 **`feature:refund`（退款分摊引擎，纯 JVM）**：与上述插件同层，同样只依赖 `core:model` 的 SPI 契约。

### 为什么手动 DI 而不是 Hilt
多模块 + KSP(Room) + Compose 的编译链路已经够复杂。再叠 Hilt 只会增加"某两个插件版本不搭就整个工程编译不过"的概率。
而 `AppContainer` 里那张装配清单本身就是一份**可读性最好的架构文档**：谁依赖谁、哪个实现可以被替换，一目了然。

### 新增能力的成本
| 想加什么 | 要做什么 |
| --- | --- |
| 新的支付渠道 | 实现 `CaptureSource`，在 `AppContainer.captureSources` 加一行 |
| 新的分类器 | 实现 `TransactionClassifier`，塞进 `CompositeClassifier` 的列表 |
| 新的统计维度 | 实现 `MetricProvider`，注册进 `MetricRegistry`，UI 自动多一张卡 |
| 接真实云同步 | 实现 `CloudSyncClient`，把 `AppContainer.cloudSyncClient` 换成它（outbox 表已经在写） |

---

## 三、核心数据模型

```kotlin
LedgerTransaction            // 一条流水
├── id / sourceId / sourceRef      // 来源渠道 + 渠道侧标识，可追溯到具体那条通知
├── amountMinor: Long              // 有符号「分」；负数=流出。杜绝浮点误差
├── occurredAt / bookedAt          // 发生时间 vs 记账时间
├── type: EXPENSE|INCOME|TRANSFER|REFUND
├── status: RAW|CONFIRMED|MERGED|IGNORED
├── counterparty / note            // 商户 / 备注
├── categoryId / confidence        // 机器学习？不，是规则信心值
├── fingerprint                    // 跨渠道去重指纹 = sha256(金额 + 归一化商户)；商户缺失时并入渠道标识，避免"同金额误合并"
├── transferGroupId                // 成对转账共享组 ID
├── rawTextSealed                  // 原文 AES-GCM 密文（二次加密）
├── schemaVersion                  // 行级结构版本
└── extras                         // 渠道特有扩展字段（JSON 字符串，可空）
```

配套实体：`Category` / `Account`（带 `identifierHints` 用于识别"这是我自己另一个口袋"）/ `ClassifierRule`（含 `learned` 标记）/ `SyncOutbox`。

### 金额一律用「分」
账单做久了最痛的问题之一：`0.1 + 0.2 != 0.3`。所有金额统一用 `Long` 的最小货币单位存储，对外只暴露一个便于阅读的格式化函数就够了。

---

## 四、三件"心脏一样的东西"

### 1. 采集流水线（唯一写入口）

```
原始信封 → 金额/商户解析 → 转账识别 → 消费分类 → 跨渠道去重 → 落库 or 待确认
```

顺序是有讲究的：
- **转账识别必须在分类之前**——给「微信零钱充值」分了「餐饮」，整套分类数据就脏了；
- **去重必须最后做**——指纹里包含前面步骤的结果，换顺序会导致同一笔账算出两个指纹；
- **拒绝（未命中任何规则）绝不静默丢**——解析不出金额也会入库并送进待确认队列。

### 2. 内部转账过滤（决定账本有没有意义）

把「银行卡充值微信」算成消费，月度支出直接翻倍——这是记账类 App 最伤信任的 bug。

判定优先级：**账户归属 > 关键词 > 金额对称配对**
- 对方是名下另一个账户（按卡号尾号/手机号/昵称匹配）→ 最可靠
- 关键词：充值 / 提现 / 还款 / 互转 / 退款 / 冲正
- 金额相反 + 时间相近 + 一方是自己账户 → 成对配账（招行扣 1000 ↔ 微信零钱 +1000）

### 3. 分类闭环

```
用户纠正 → 写入 learned 规则 → 下次同一商户直接命中（0.98 置信度）
```
不需要联网、不需要模型。**唯一长期增长资产是用户的手指点击**。

三级分类：记忆（0.98）→ 关键词（0.6~0.85，词越长越可信）→ 金额时间启发式（0.35）。
启发式低置信度**刻意不自动入账**，它的作用是给出排序合理的候选，让人点一下。

### 4. 退款与净额口径

退款不建订单也能算清楚：**退款就是一笔独立的 `REFUND` 流水**，它对账单的影响是**冲抵支出**，而不是计入收入。

- **自动识别**：微信/支付宝退款通知、银行退款短信、账单 CSV 里的退款行，三类来源都能自动入账。
  解析规则在 `NotificationRule` 里**显式标注账本类型**并一路下发到落库，
  而不是让下游靠"退款"二字猜；规则排在付款规则之前，避免被付款规则抢走。
- **唯一口径**：净支出 = 毛支出 − 退款，收敛在 `core:model` 的 `ExpenseMath`。
  所有聚合（统计卡片 / 预算 / 首页 / 账单）都必须走它，UI 层不许手写 `sumOf`。
- **展示**：账单页月/年汇总同时给出「付款总额」与「退款总额」两个独立数值；首页本月支出提示「付款 ¥X · 退款 ¥Y」。
- **引擎**（`feature:refund`，纯 JVM）：支持全额/部分退款与抵扣（券/资源）分摊，
  幂等键 + 乐观锁 CAS + 「Σ分摊 == 退款额」自检，防重复回退与超退。

> **两个踩过的坑**（都写进测试护栏了）：<br>
> ① 退款金额进链路时是**正数**（资金流入），会被"按金额正负推断类型"先判成**收入**——所以显式类型必须一路传到底，不能靠下游关键词补救。<br>
> ② 支付宝账单"收/支"列的取值是 `收入 / 支出 / 不计收支`，而**退款行恰恰是"不计收支"**——用 `contains("支")` 判断方向会把退款记成**支出**（比不记更糟，直接虚增支出）。

---

## 五、版本与迁移（三层保险）

三层保险，缺一不可：

| 层级 | 位置 | 作用 |
| --- | --- | --- |
| 库结构版本 | `LedgerSchema.DATABASE_VERSION` = 4 | 未发布期用 `fallbackToDestructiveMigration()`，版本不匹配即重建（正式发布后须改回逐版本 Migration） |
| 档案版本 | `LedgerSchema.BACKUP_VERSION` = 4 | 导出文件自带版本号；未发布期导入按当前结构直接处理，暂不维护迁移链 |
| 行级版本 | `LedgerTransaction.schemaVersion` | 档案版本万一丢了，还能按行自救 |

**一条真实的迁移（v1 → v2）**：把「元的 Double」改成「分的 Long」，修掉浮点误差导致的对账错位（用 `BigDecimal` 定标，杜绝 `0.29 → 28` 的少一分），老数据自动补齐新字段，一行不丢。
未发布期不做链式迁移：`BackupMigrator` 直接按当前结构处理；正式发布后再恢复「逐版本迁移、缺链即报错」的约定。

备份支持加密导出（PBKDF2-HMAC-SHA256 派生 + AES-GCM），用于"存网盘/发别人"的场景；本机备份默认不需要口令，因为库本身就是加密的。

---

## 六、隐私

| 措施 | 说明 |
| --- | --- |
| 整库加密 | SQLCipher，口令由 Keystore 主密钥 AES-GCM 包裹后存 SharedPreferences |
| 原文二次加密 | 通知/短信正文单独加密后再落盘，处理好备受争议的字段 |
| 日志出口不含敏感数据 | 现有日志只有三类——后台任务异常、加密初始化失败、未知枚举值——**均不输出通知/短信原文、金额或卡号**。约定：**若将来新增会输出用户数据的日志出口，必须先在此处接入脱敏**（旧版 `Redactor` 工具类因全工程零引用已移除） |
| 不进系统备份 | `backup_rules.xml` / `data_extraction_rules.xml` 明确排除数据库与 Vault——换机后 Keystore 解不开，备份出去也没用，反而会覆盖出问题 |
| 云同步 | `CloudSyncClient` 只是接口 + `NoopCloudSyncClient`；要接云就把这一行换掉 |

---

## 七、构建与运行

```bash
# 前置：JDK 17 + Android SDK(compileSdk 35)
cd AutoLedger
python3 tools/static_check.py     # 静态一致性校验（不依赖 Android SDK）

# 用 Android Studio 打开本目录，或用命令行：
gradle :app:assembleDebug
```

仓库**已提交 Gradle wrapper**（`gradlew` / `gradlew.bat` / `gradle/wrapper/*`），用于锁定 Gradle 版本，保证任何人 clone 后构建结果一致：

```bash
./gradlew :app:assembleDebug
./gradlew test --rerun-tasks      # 注意别省 --rerun-tasks，否则可能是缓存假绿
```

> 若 `./gradlew` 报**无法下载 Gradle 发行包**（国内网络访问 `services.gradle.org` 常失败），
> 把 `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 临时换成
> `https\://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip` 即可；也可直接用本机已装的 Gradle 8.9。

### 首次使用需要两步授权（都在「采集箱」里引导）
1. **通知使用权**：系统设置 → 通知使用权 → 允许本 App（无需危险权限弹窗）
2. **短信读取**（可选）：仅当你需要银行短信补录才开

---

## 八、已知限制（不藏）

1. **已通过编译、单元测试与真机验证**：本工程已在 JDK 17 + Android SDK 35 + Gradle 8.9 下 `:app:assembleDebug` **BUILD SUCCESSFUL**，产出 `app/build/outputs/apk/debug/app-debug.apk`；全量 **324** 个单元测试覆盖 10 个模块全绿（`./gradlew test --rerun-tasks`）。`tools/static_check.py` 另做模块、依赖、包名、分层、API 泄漏的静态交叉校验。真机（Android 13 / 14）运行态验证也已完成——正是这轮验证揪出并修复了两处会让**整库加密静默失效**的缺陷（Keystore 的 IV 用法错误、SQLCipher 原生库未显式加载），现真机数据库文件头确认为随机字节、加密真正生效。
2. **设置已纳入备份**：时薪参数 / 自由基金目标 / 自动去重开关现存于 Room（单行 `app_settings`，金额以「分」存），随账本备份一起导出与导入，换机不再需要重填。
3. **分类规则是中文语境的默认词表**：覆盖常见的外卖/打车/超市/房租等，小众商户靠用户纠正补上。
4. **去重有误判边界**：同一家店、同金额、几分钟内的两笔真实消费会被判成重复。因此自动合并的记录都留了标记，可在「待确认」里恢复。
5. **未做 Android 自适应布局 / 桌面组件**：目前是手机竖屏优化。
6. **图标用了 Material Icons 近似还原**参考页的内联 SVG。原版 6 个图标的 path 数据如下，需要 100% 还原时替换 `LedgerIcons` 即可：
   <br>`dashboard` M4 13h6V4H4zM14 20h6v-9h-6zM4 20h6v-3H4zM14 7h6V4h-6z`
   <br>`clock` circle(12,12,8.5) + M12 7.5v5l3.2 2`
   <br>`receipt` M6 3.5h12v17l-3-1.8-3 1.8-3-1.8-3 1.8zM9 8h6M9 12h6`
   <br>`calendar` rect(4,5.5,16,14,r2) + M8 3.5v4M16 3.5v4M4 10h16M8 14h2M14 14h2`
   <br>`pig` M5 10.5c0-3.3 3.1-5.5 7.4-5.5 3.7 0 6.6 2 6.6 5v3.5l1.5 1v2h-3l-1 2.5h-2v-2H9v2H7l-1.2-3A5.8 5.8 0 0 1 5 10.5zM5.5 8 3 6.5V11h2`
   <br>`spark` m12 3 1.7 5.3L19 10l-5.3 1.7L12 17l-1.7-5.3L5 10l5.3-1.7z` + 小星

---

## 九、参考项目（GitHub 调研结论）

调研是先把它们的方案读明白了才动手的，重点借鉴了三件事：

| 项目 | 借鉴点 |
| --- | --- |
| `GreenIcePhoenix/TraceLedger` | 隐私优先的本地-first 架构；「学习引擎基于用户纠正」「导入前 review」的产品逻辑 |
| `wealth-wave/Auto-Expense-Tracker` | Compose + Clean MVVM 的分层结构；SMS 交易检测 pipeline |
| `lyenrowe/bill-parser` | 按文件头部特征自动判别账单格式再选解析器的思路（本项目改成表头别名推断，支持支付宝/微信双格式 + GBK 自动嗅探） |

---

## 十、许可证

本项目以 **GNU General Public License v3.0** 发布，全文见 [LICENSE](LICENSE)。

- 贡献指南：[CONTRIBUTING.md](CONTRIBUTING.md)
- 变更记录：[CHANGELOG.md](CHANGELOG.md)
- 安全政策与漏洞报告：[SECURITY.md](SECURITY.md)
- 路线图完成度审计：[docs/review/roadmap-audit.md](docs/review/roadmap-audit.md)
