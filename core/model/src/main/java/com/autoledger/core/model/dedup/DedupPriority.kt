package com.autoledger.core.model.dedup

/**
 * 合并后「谁留下」的裁决 —— **纯函数，可 JVM 单测**。
 *
 * ## 为什么必须抽成纯函数
 * 这条规则决定「合并后用户在账单里看到哪条记录、它的商户和平台是什么」，
 * 一旦写错就是把用户的手动修改悄悄覆盖掉（[com.autoledger.core.model.platform.PlatformSource.USER]
 * 存在的全部意义就是防这件事）。把它放在 UI / 领域服务里，
 * 就只能靠「造一个完整 Room 库 + 走一遍 ingest」来验证，成本高且容易漏。
 *
 * ## 输入为什么是「标量」而不是两个 `LedgerTransaction`
 * `IngestPipeline` 手里的 `incoming` 是刚解析出来的草稿，`existing` 来自仓储查询结果，
 * 两者类型不同（一个还没有 id 之外的持久化痕迹）。用标量入参：
 * ① 调用方自己决定从哪取这几个值；② 单测不必构造两个完整流水对象。
 */
object DedupPriority {

    /**
     * 裁决结果。
     *
     * @property primaryId 合并后**留下**的那条（它的字段是基准）
     * @property mergedId  被吸收的那条（置 MERGED + 记 mergedIntoId）
     * @property reason    给人看的裁决依据，便于排查「为什么主记录变了」
     */
    data class Choice(
        val primaryId: String,
        val mergedId: String,
        val reason: String,
    )

    /**
     * 规则按序短路：
     *
     * - **R0 用户权威**：任一方 `platformSource == USER` ⇒ 该方为 primary。
     *   双方都是 USER ⇒ 保留 existing（不改写历史）。
     * - **R1 层级高者胜**：[incomingRank] > [existingRank] ⇒ incoming 为 primary
     *   （美团 ORDER=3 > 微信/支付宝 PAYMENT=2 > 银行卡 BANK=1）。
     * - **R2/R3 同层级或层级更低** ⇒ 保留 existing。
     *   刻意保留历史而不是取 incoming：主记录反复易主会让 UI 上「这条归到哪个平台」
     *   每来一条通知就跳一次；且合并前的记录才是用户已经看过的。
     *
     * @param incomingRank 通常来自 [com.autoledger.core.model.platform.priorityOf] 的 `rank`
     * @param existingRank 同上；未知平台为 0（[com.autoledger.core.model.platform.PlatformPriority.NONE]）
     */
    fun choosePrimary(
        incomingId: String,
        incomingRank: Int,
        incomingIsUser: Boolean,
        existingId: String,
        existingRank: Int,
        existingIsUser: Boolean,
    ): Choice = when {
        existingIsUser && !incomingIsUser ->
            Choice(existingId, incomingId, "已存在记录由用户指定，保留用户值（用户权威）")

        incomingIsUser && !existingIsUser ->
            Choice(incomingId, existingId, "新记录由用户指定，保留用户值（用户权威）")

        // 双方都是 USER：没有「更权威」的一方 ⇒ 不改写历史。
        // 必须显式判这一支而不能直接落到比层级：层级高的一方会把另一方吞掉，
        // 而那一方也是用户亲手指定的 —— 用户权威不该被"另一个用户选择"盖过。
        existingIsUser && incomingIsUser ->
            Choice(existingId, incomingId, "双方都由用户指定，保留历史（不改写用户已确认的值）")

        incomingRank > existingRank ->
            Choice(incomingId, existingId, "平台层级更高（$incomingRank > $existingRank）")

        else ->
            Choice(existingId, incomingId, "层级不高于已存在记录，保留历史（$existingRank ≥ $incomingRank）")
    }
}
