package com.autoledger.core.model

/** 自由基金目标。金额以「分」存储。 */
data class FreedomGoal(
    /** 目标金额（分） */
    val targetMinor: Long = 12_000_000L,
    /** 当前已攒（分） */
    val currentMinor: Long = 0L,
)
