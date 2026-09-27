# 消费退款与抵扣原路回退 —— 设计说明书

> 目标：订单发生退款时，自动记录退款流水，并按原订单的**抵扣构成**（余额、积分、优惠券、权益次数等）**原路回退**，消除/还原已抵扣的金额与数量；保证**幂等**与**金额可对账**。

## 0. 实现状态

> **已选定路线：不建订单 —— 退款作为独立流水记录（路线 3）**。见文末「附：无订单模式」。

| 批次 | 内容 | 状态 |
|---|---|---|
| ① 引擎层（纯函数 + 全场景单测） | `feature:refund`：`RefundEngine`（评估/分摊/对账）；领域类型上移 `core:model` | ✅ **已完成**（34 条单测） |
| ② 持久化层 | 5 张表 + `transactions` 补 `order_id`/`refund_id` + `RefundRepository` 单事务落库（CAS/幂等） | ✅ **已完成**（4 条真实 Room 集成测试） |
| ③ UI 层 + app 接线 | `RefundService` 编排 + `RefundScreen`（对账视图 + 发起退款 + 手动登记订单）+ 设置页入口 | ✅ **已完成** |
| ④ 采集侧自动退款识别 | 通知/短信识别退款事件 → 按订单号匹配订单 → 自动调引擎落库（**需先确定"订单来源"设计**） | ⏳ 待产品决策 |

**分层约定（已落地）**：领域类型放 `core:model.refund`（`core:database` 不依赖 `feature`）；流水映射 `core:model.refund.RefundLedgerMapper` 两侧共用；引擎只在 `feature:refund`。

### 引擎已固化的关键约定

- **错误码与文案**集中在 `RefundRejectCode`（11 个：`ORDER_NOT_FOUND/ORDER_NOT_PAID/ORDER_CANCELLED/ORDER_CLOSED/AMOUNT_NOT_POSITIVE/AMOUNT_EXCEEDS_REMAINING/AMOUNT_EXCEEDS_TOTAL/REFUND_WINDOW_EXPIRED/CONFLICT_VERSION/NO_DEDUCTION_LEFT/RETRY_EXHAUSTED`），每个都带 `message`（给用户）+ `hint`（下一步）。
- **并发竞态**：`OrderRefundState.version` 乐观锁；`RefundRequest.expectedVersion` 不匹配即 `CONFLICT_VERSION`。
- **重试上限**：`RefundEngine.MAX_ATTEMPTS = 3`。
- **引擎自检**：分摊结果 `Σ amountMinor == 退款额`，不满足直接 `check` 失败（把"账不平"挡在落库前）。
- **状态流转**：`PAID/SHIPPED/COMPLETED/REFUNDING` 可退；退满 → `REFUNDED`，未退满 → `REFUNDING`；`PENDING_PAYMENT/CANCELLED/CLOSED` 拒绝。
- **回调对账**：任一侧终态冲突（本地生效但回调失败 / 本地拒绝但回调成功 / 本地终态但回调处理中）→ 一律 `MANUAL_REVIEW`，**绝不自动改账**。

## 0. 与现状的差距（先行说明）

现有模型只有 `transactions`（流水），**没有"订单 / 抵扣构成 / 资源账本"**概念。因此本设计新增 5 张表，并给 `transactions` 补 2 个字段作为衔接。

## 1. 数据表与字段

### 1.1 新增表

**`orders`（订单）**
| 字段 | 类型 | 说明 |
|---|---|---|
| id | TEXT PK | 内部 ID |
| order_no | TEXT | 外部订单号 |
| counterparty | TEXT | 商户 |
| total_minor | INTEGER | 订单总额（分） |
| currency | TEXT | 币种 |
| occurred_at_millis | INTEGER | 下单时间 |
| status | TEXT | PAID / REFUNDING / REFUNDED / CLOSED |
| source_id / source_ref | TEXT | 来源渠道与原始标识 |
| extras | TEXT | JSON 扩展 |
| schema_version | INTEGER | 行级结构版本 |
> **UNIQUE(source_id, order_no)** —— 订单幂等，防止同一订单重复建单。

**`order_deductions`（订单抵扣构成 = 原订单用了哪些资源）**
| 字段 | 类型 | 说明 |
|---|---|---|
| id | TEXT PK | |
| order_id | TEXT FK | 所属订单 |
| kind | TEXT | BALANCE / POINTS / COUPON / ENTITLEMENT / THIRD_PARTY |
| amount_minor | INTEGER | 该方式抵扣金额（分），金额型专用 |
| quantity | INTEGER | 抵扣数量（积分/权益次数），数量型专用 |
| resource_id | TEXT | 券 ID / 权益 ID（金额型可空） |
| resource_snapshot | TEXT | JSON：券名、面值、有效期快照（便于过期后仍可折算） |
| reversed_amount_minor | INTEGER | **已回退金额**（累计） |
| reversed_quantity | INTEGER | **已回退数量**（累计） |
| status | TEXT | APPLIED / PARTIALLY_REVERSED / REVERSED |
| schema_version | INTEGER | |

**`refunds`（退款单）**
| 字段 | 类型 | 说明 |
|---|---|---|
| id | TEXT PK | |
| refund_no | TEXT | 退款单号 |
| order_id | TEXT FK | 关联订单 |
| amount_minor | INTEGER | 退款金额（分） |
| reason | TEXT | 退款原因 |
| occurred_at_millis | INTEGER | 退款时间 |
| status | TEXT | PENDING / APPLIED / REJECTED |
| idempotency_key | TEXT **UNIQUE** | 幂等键 |
| source_id / source_ref | TEXT | 来源 |
| schema_version | INTEGER | |

**`refund_allocations`（退款分摊 / 回退明细）**
| 字段 | 类型 | 说明 |
|---|---|---|
| id | TEXT PK | |
| refund_id | TEXT FK | |
| deduction_id | TEXT FK | 对应哪一条原抵扣 |
| kind | TEXT | 与 deduction 一致 |
| amount_minor | INTEGER | 本次回退金额（分） |
| quantity | INTEGER | 本次回退数量 |
| outcome | TEXT | RETURNED / EXPIRED_FALLBACK / USED_FALLBACK / SKIPPED |
| fallback_amount_minor | INTEGER | 无法原路回退时的折现金额（退到余额） |
| resource_id | TEXT | |
| schema_version | INTEGER | |
> **UNIQUE(refund_id, deduction_id)** —— 一次退款对同一抵扣项只产生一条分摊（幂等）。

**`resource_balances`（资源可用量账本）**
| 字段 | 类型 | 说明 |
|---|---|---|
| id | TEXT PK | |
| kind | TEXT | BALANCE / POINTS / COUPON / ENTITLEMENT |
| resource_id | TEXT | 券/权益标识（BALANCE/POINTS 可为账户标识） |
| owner_account_id | TEXT | 归属账户（可空） |
| available_minor | INTEGER | 可用金额（分） |
| available_quantity | INTEGER | 可用次数/数量 |
| expires_at_millis | INTEGER | 过期时间（可空） |
| status | TEXT | ACTIVE / EXPIRED / EXHAUSTED |
> **UNIQUE(kind, resource_id)**。

### 1.2 对现有 `transactions` 的补充

| 新增字段 | 说明 |
|---|---|
| order_id | 可空；该流水归属的订单 |
| refund_id | 可空；由退款产生的流水指向退款单 |

- 退款会写入一笔 `transactions`，`type = REFUND`、`amountMinor > 0`。
- 现有统计逻辑**已把 REFUND 排除在月度支出之外**，因此退款会自动冲抵支出，无需改统计。

## 2. 核心处理流程

```
① 接单（幂等入口）
   └ 解析到「退款」事件（通知/短信），构造 idempotency_key
      若 refunds 已存在同 key → 直接返回既有结果（不重复回退）
② 匹配原订单
   └ 按 order_no + 金额 + 时间窗口反查 orders
      匹配不到 → 记 PENDING，待人工关联（不臆造订单）
③ 额度校验
   └ applied  = Σ refunds(order_id, status=APPLIED)
     remaining = order.total_minor - applied
     amount > remaining → REJECTED（默认不静默截断；可配置改为截断+记 SKIPPED）
④ 分摊（allocation）
   └ 金额型：按 refund/total 比例分摊到各 deduction 的「剩余可退」
     券/次数型：整单退→整张返还；部分退→不拆，折算退余额
     取整残差 → 显式归入余额项，保证 Σ allocations == refund.amount
⑤ 回退执行（单事务 + 幂等）
   └ 可原路：resource_balances.available += 金额/次数；deduction.reversed += …
     不可原路：等额折现退余额，记 outcome=FALLBACK + fallback_amount_minor
     更新 deduction.status / order.status / 写 transactions(REFUND) / 登记 outbox
     refunds.status = APPLIED
⑥ 对账校验（提交前断言，任一失败即回滚）
   └ Σ allocations.amount == refund.amount
     ∀ deduction: reversed ≤ 原抵扣
     Σ refunds(APPLIED) ≤ order.total
```

## 3. 四类关键场景的处理规则

| 场景 | 规则 | 可对账保障 |
|---|---|---|
| **部分退款** | 金额型（余额/积分/第三方）按 `退款额/订单额` **等比例**分摊到各抵扣项的剩余可退额；**优惠券/权益次数不拆分**（要么整张退，要么折算成余额）；比例取整的**残差显式归入余额项** | `Σ allocations == 退款额`（残差有明确归属，不丢分） |
| **多次退款** | 每次基于**剩余可退**（`原抵扣 − 已回退`）继续分摊；累计退款 ≤ 订单额；每次生成独立 refund + allocations，靠 `idempotency_key` 去重 | `Σ refunds(APPLIED) ≤ order.total` |
| **退款额 > 抵扣额/订单额** | **默认 REJECTED 并进入待确认**（不静默截断，避免金额对不上）；可通过配置切换为"截断到剩余额 + 记 `SKIPPED` 差额" | 有 `SKIPPED` 记录 → 差额可追溯 |
| **原资源已使用 / 已过期** | 券过期、次数已消耗、积分已用 → **不能原路回退**，改为**等额折现退余额**；allocation 记 `outcome = EXPIRED_FALLBACK / USED_FALLBACK`，并写 `fallback_amount_minor` | 金额不消失：折现额 = 应退额，账目闭合 |

## 4. 幂等设计

1. `refunds.idempotency_key` **唯一约束** —— 重复退款事件命中即返回既有结果。
2. `refund_allocations` **UNIQUE(refund_id, deduction_id)** —— 一次退款对同一抵扣项不会重复回退。
3. 资源回退采用「**增量累加 + 单事务**」，重复执行会因事务内的既有记录校验而短路。
4. `orders` 的 `UNIQUE(source_id, order_no)` 保证原订单不重复建单。

## 5. 可对账设计

**三条不变式（事务提交前断言）**
- `Σ refund_allocations.amount_minor == refunds.amount_minor`
- `∀ order_deductions: reversed_amount_minor ≤ amount_minor`
- `Σ refunds(status=APPLIED).amount_minor ≤ orders.total_minor`

**对账视图（按订单）**：原抵扣构成 → 已回退（金额/次数）→ 剩余可退 → 累计退款额，逐项可核。

**审计链**：退款事件 → refunds → refund_allocations → transactions(REFUND)，四者金额一致；任何折现/跳过的部分均由 `outcome` 显式标注，不存在"静默丢失"。

## 6. 与现有架构的衔接（分层）

| 层 | 承担 |
|---|---|
| `core:model` | 新增领域类型：`Order`、`OrderDeduction`、`Refund`、`RefundAllocation`、`ResourceBalance`、`RefundOutcome` |
| `core:database` | 新增实体/DAO/迁移（未发布期可破坏性重建）；`transactions` 补 `order_id`/`refund_id` |
| `feature:capture` | 退款事件识别（复用 `TransferRulePack.REFUND` 关键词）+ 订单号/金额解析 |
| `feature:refund`（建议新增） | **退款分摊与回退引擎**：纯逻辑、可 JVM 单测（pro-rata、残差、多次退款累计、折现规则） |
| `app` | 退款/回退明细的 UI 与对账视图 |

> 建议把分摊与回退做成**纯函数引擎**（输入：订单+抵扣构成+已退记录+退款额 → 输出：allocations 列表），这样四条关键规则都能被单元测试钉死，且未来接"多币种/多权益类型"只需扩展引擎，不动 UI。

---

## 附：无订单模式（已选定路线）—— 退款作为独立流水

**前提**：不建立订单/抵扣构成，退款就是一条**独立的 `REFUND` 流水**。

### 1) 记录方式
| 项 | 处理 |
|---|---|
| **金额** | 存**正数**（与 `EXPENSE` 的负数相反，表示资金流入）。`netExpenseMinor = Σ|EXPENSE| − Σ|REFUND|`。 |
| **时间** | 用**退款到账时间**作为 `occurredAtMillis`（即它落在**退款发生的那一天/月**，而不是原消费那天）。这让"本月净支出"反映真实现金流。 |
| **类型** | `TxnType.REFUND`，与 `TRANSFER` 一样**不计入收入**，只用于冲抵支出。 |
| **来源** | 自动：通知/短信正文命中退款关键词 → `TransferRulePack.REFUND` → `TxnType.REFUND`。手动：记账页切到「退款」录入（正数 + 显式类型）。 |
| **去重** | 走既有跨渠道指纹去重；同渠道同金额的重复退款不会被静默合并（沿用 A4 的收窄规则）。 |

### 2) 账单汇总：如何冲抵与展示
统一口径（**唯一真源** `core:model.ExpenseMath`）：

| 指标 | 定义 |
|---|---|
| 毛支出 | `Σ|EXPENSE|`（排除 MERGED） |
| 退款 | `Σ|REFUND|` |
| **净支出** | 毛支出 − 退款（**不下限为 0**：负数代表数据异常，展示层再夹取） |

- **账单页（月度/年度）**：「支出」显示**净支出**，并在下方提示 `已扣退款 ¥X`；「结余 = 收入 − 净支出」。
- **首页**：「本月支出」同样是净额，提示 `已扣退款 ¥X`。
- **分类结构 / 商户排行 / 渠道分布 / 月度趋势**：按分组做净额（退款按其 `categoryId` 冲抵**同一分类**；无分类的退款只冲抵总额）。**净额 ≤ 0 的分组不展示**，避免出现负占比。
- **预算**：某分类的"已花"用**净额**，退款会实时把该分类的预算执行率降下来。
- **时间成本（花掉的时间）**：用净支出换算，退款把"花掉的人生"还回来。

### 3) 与订单模式的取舍
- 无订单模式的**优点**：零额外数据依赖、当场可用、账单口径清晰。
- **代价**：退款无法"按原抵扣构成原路回退"（券/积分/权益无法精确还原）、也无法按订单对账——那正是引擎/5 张表那条路线的价值，**已实现并保留**，将来确定"订单来源"后可直接切换（`RefundService` 已就位）。
