package com.autoledger.app

/**
 * 「记账时弹通知」(`notifyOnRecord`) 的默认值解析 —— **纯函数**，便于纯 JVM 单测。
 *
 * ## 为什么单独抽出来（v1.1.4 改默认值的风险点）
 * 把默认值从 off 改成 on 时，最危险的坑是**「从没设置过」与「用户主动关过」无法区分**：
 * 若实现是"读不到就用新默认值"，那么用户**明明关掉**的开关会在升级后**被重新打开**，
 * 于是开始收到他明确不想要的通知 —— 典型的"改默认值打脸老用户"事故。
 *
 * 因此本对象要求调用方**显式**把三态传进来（`true` / `false` / `null` = 键不存在）：
 *
 * | 传入 | 含义 | 结果 |
 * |---|---|---|
 * | `null`  | 从没设置过（键不存在） | 跟随默认 [DEFAULT_ENABLED] = `true` |
 * | `true`  | 用户开着 | `true` |
 * | `false` | **用户主动关过** | `false`（**不得**被默认值覆盖） |
 *
 * 三态的无损获取由调用方负责 —— `AppContainer` 用 `SharedPreferences.contains(key)` 区分
 * "键不存在"（`null`）与"键存在且为 false"（`false`），因为只有用户**拨动过**开关才会落盘该键。
 */
object NotifyOnRecordDefault {

    /**
     * 新用户默认「开」：自动记账后应能看到「已自动记一笔账」——
     * 否则补上 `POST_NOTIFICATIONS` 运行时请求的收益等于 0（用户不知道自动记账在正常工作）。
     */
    const val DEFAULT_ENABLED: Boolean = true

    /** 解析"最终开关值"：仅在**从未设置过**（[stored] 为 `null`）时才落到默认值。 */
    fun resolve(stored: Boolean?): Boolean = stored ?: DEFAULT_ENABLED
}
