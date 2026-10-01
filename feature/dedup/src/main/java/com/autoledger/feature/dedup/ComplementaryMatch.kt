package com.autoledger.feature.dedup

import com.autoledger.core.model.platform.PlatformPriority
import com.autoledger.core.model.platform.priorityOf

/**
 * **Tier-2 层级互补匹配的护栏**（设计 §4.2 的判定表，唯一真源）。
 *
 * ## 为什么需要 Tier-2 这条新通道
 * Tier-1 指纹 = `sha256(金额 | 归一化商户)`。而需求场景里两条记录的**商户名天然不同**：
 *
 * | 来源 | 原始商户 | 归一化后 |
 * |---|---|---|
 * | 美团 App 通知 | `美团外卖` | `美团外卖` |
 * | 银行扣款短信 | `财付通` | `财付通` |
 *
 * ⇒ 指纹不同 ⇒ `findByFingerprintNear` 根本查不到对方 ⇒ **平台优先级永远没机会执行**。
 * 必须补一条「同金额 + 时间窗口 + 跨 source + 层级互补」的通道。
 *
 * ## 三态而不是布尔
 * 判定表的第三行「`PAYMENT ↔ BANK`」不是简单的「不合」而是「**证据不足，交给用户**」：
 * 「微信支付 88」+「银行卡扣 88」**可能是同一笔**（微信绑的就是这张卡），
 * **也可能真是两笔**（先充值、再消费）。所以它要作为候选浮出来提示用户，
 * 只是不能由系统**静默**合并。用一个布尔表达不了这个区别。
 *
 * ## 为什么这条判定必须是纯函数
 * 它决定「两条记录会不会被合成一条」。写松一点就是**静默吞掉真实消费**（设计 R2）。
 * 抽成纯函数后，判定表能被逐格单测钉死（含全部 `4 × 4` 组合），
 * 不必为每一格都造一遍完整 ingest 链路。
 */
enum class ComplementaryVerdict {
    /** 层级互补且证据充分 —— 允许**静默自动合并**。 */
    AUTO_MERGE,

    /** 层级互补但证据不足（唯一真实歧义的组合）—— 作为候选浮出，**等用户确认**。 */
    REVIEW,

    /** 层级不互补 —— **连候选都不是**，不得参与合并（防「同店同金额两笔真实消费」被吞）。 */
    REJECT,
}

/**
 * 按**平台层级**判定两条同金额记录能否互补合并。
 *
 * 记 `tier(p) = priorityOf(p)`：`ORDER(3) > PAYMENT(2) > BANK(1) > NONE(0)`。
 *
 * | 组合 | 判定 | 理由 |
 * |---|---|---|
 * | `ORDER ↔ PAYMENT` | [AUTO_MERGE] | 美团下单 + 微信/支付宝付款 —— **正是需求要的场景** |
 * | `ORDER ↔ BANK` | [AUTO_MERGE] | 美团下单 + 银行卡扣款 |
 * | `ORDER ↔ NONE`（unknown） | [AUTO_MERGE] | 美团通知 + 银行短信没识别出平台 |
 * | `PAYMENT ↔ BANK` | [REVIEW] | 唯一真实歧义组合，交用户 |
 * | `PAYMENT ↔ PAYMENT` | [REJECT] | 一次消费只有一个支付通道 |
 * | `ORDER ↔ ORDER` | [REJECT] | 两个消费场所 = 两笔消费 |
 * | `BANK ↔ BANK` | [REJECT] | 同层级，落回 Tier-1 规则 |
 * | `NONE ↔ NONE` | [REJECT] | 无层级信息，不合并 |
 * | `PAYMENT ↔ NONE` / `BANK ↔ NONE` | [REJECT] | 保守：unknown 那条可能什么都没识别出，没有互补证据 |
 *
 * 实现上「恰好一侧是 ORDER ⇒ AUTO_MERGE」一句覆盖了前三行与全部 `ORDER↔ORDER` 情况，
 * 不存在"某一格被写反"的空间。
 */
fun complementaryVerdict(incomingPlatformId: String, existingPlatformId: String): ComplementaryVerdict {
    val incoming = priorityOf(incomingPlatformId)
    val existing = priorityOf(existingPlatformId)

    // 恰好一侧是下单平台 ⇒ 层级互补：一侧说「在哪个平台花」，另一侧说「钱从哪出」。
    if ((incoming == PlatformPriority.ORDER) != (existing == PlatformPriority.ORDER)) {
        return ComplementaryVerdict.AUTO_MERGE
    }

    // 支付通道 ↔ 银行卡：两侧都只说「钱从哪出」，但一张卡可能就是该通道绑的卡 ⇒ 证据不足，交给用户。
    if (incoming == PlatformPriority.PAYMENT && existing == PlatformPriority.BANK) return ComplementaryVerdict.REVIEW
    if (incoming == PlatformPriority.BANK && existing == PlatformPriority.PAYMENT) return ComplementaryVerdict.REVIEW

    return ComplementaryVerdict.REJECT
}
