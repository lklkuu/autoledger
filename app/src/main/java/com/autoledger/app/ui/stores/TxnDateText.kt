package com.autoledger.app.ui.stores

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 时间戳 → `yyyy-MM-dd` 文本；**脏时间戳返回 null**（不抛异常）。
 *
 * 抽成独立纯函数的原因：`TxnEditDialog` 的日期初值必须与单测走**同一份**实现。
 * 若 UI 里另写一套（此前就是），单测只能测副本，真正的线上路径无人覆盖 ——
 * v1.1.14 的「编辑账单日期被改成今天」正是这样漏出去的。
 *
 * ⚠️ **必须用 Java 8 API**：`Instant.ofEpochMilli(...).atZone(zone).toLocalDate()`。
 * 反面教材是 `LocalDate.ofInstant(Instant, ZoneId)` —— 它是 **API 34** 才加的方法，
 * 本项目 `minSdk = 26` 且未开 core library desugaring，在 Android 13 及以下会抛
 * [NoSuchMethodError]。它是 `Error` 而不是 `Exception`，`runCatching` 同样会吞掉，
 * 于是低版本设备上「每次打开编辑框都拿不到日期」，而日志里什么都不留。
 *
 * ⚠️ **失败时返回 null，绝不返回「今天」**：时间戳可能是脏数据（备份导入 / 解析异常），
 * 把「读不出来」解释成「用户想改成今天」，会让用户在只改备注时把一笔历史账单的日期
 * 静默挪到今天。调用方拿到 null 后应当**留空并禁用保存**，把决定权交还用户。
 *
 * 「脏」的判据有两条，缺一不可：
 *  1. `java.time` 自己拒绝（`atZone` 抛 [java.time.DateTimeException]，如超出
 *     `LocalDate` 年份范围 ±999999999 的瞬间）⇒ [runCatching] 收敛成 null；
 *  2. **年份必须落在 0..9999**。这一条不能省：`Long.MIN_VALUE` / `Long.MAX_VALUE`
 *     毫秒只对应约 ±2.9 亿年，**仍在** `LocalDate` 的表示范围内，`atZone` 不会抛异常，
 *     会老老实实格式化成 `+292278994-08-17` 这种 14 字符的字符串。而本函数的唯一消费方
 *     是 `yyyy-MM-dd` 输入框（10 字符、4 位年份）：这种值既显示不下、用户也重新输不进来，
 *     回写时还会被 `LocalDate.parse(ISO_LOCAL_DATE)` 接受成一个荒唐年份的流水。
 *     因此按 **UI 契约**（4 位年份）判脏，而不是只靠「会不会抛异常」。
 *
 * @param millis epoch 毫秒
 * @param zone 展示时区（UI 传 `ZoneId.systemDefault()`，单测固定传具体时区以保证可重复）
 */
internal fun isoDateOf(millis: Long, zone: ZoneId): String? = runCatching {
    val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    // 0..9999 是 `yyyy-MM-dd` 这种 4 位年份写法的表示范围，也是本函数对外的契约。
    if (date.year !in 0..9999) null else date.format(DateTimeFormatter.ISO_LOCAL_DATE)
}.getOrNull()
