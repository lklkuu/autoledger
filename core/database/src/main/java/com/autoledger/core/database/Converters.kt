package com.autoledger.core.database

import androidx.room.TypeConverter
import com.autoledger.core.model.AccountKind
import com.autoledger.core.model.CategoryKind
import com.autoledger.core.model.Direction
import com.autoledger.core.model.RuleKind
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.refund.DeductionKind
import com.autoledger.core.model.refund.OrderStatus
import com.autoledger.core.model.refund.RefundOutcome
import com.autoledger.core.model.refund.RefundStatus

/**
 * Room 类型转换器。枚举统一以字符串落盘，便于跨版本迁移与人工排查。
 *
 * 遇到未知枚举值时**不再静默兜底**：先打一条 warning 日志再回退默认值，数据异常有迹可循。
 */
class Converters {

    private companion object {
        const val LIST_SEP = "\u0001"

        inline fun <reified E : Enum<E>> parse(value: String, fallback: E): E =
            runCatching { enumValueOf<E>(value) }.getOrElse { e ->
                android.util.Log.w("Converters", "未知枚举值 ${E::class.simpleName}=$value，回退 $fallback", e)
                fallback
            }
    }

    @TypeConverter fun txnType(v: String): TxnType = parse(v, TxnType.EXPENSE)
    @TypeConverter fun txnTypeToDb(v: TxnType): String = v.name

    @TypeConverter fun status(v: String): TxnStatus = parse(v, TxnStatus.CONFIRMED)
    @TypeConverter fun statusToDb(v: TxnStatus): String = v.name

    @TypeConverter fun direction(v: String): Direction = parse(v, Direction.OUT)
    @TypeConverter fun directionToDb(v: Direction): String = v.name

    @TypeConverter fun accountKind(v: String): AccountKind = parse(v, AccountKind.OTHER)
    @TypeConverter fun accountKindToDb(v: AccountKind): String = v.name

    @TypeConverter fun ruleKind(v: String): RuleKind = parse(v, RuleKind.KEYWORD)
    @TypeConverter fun ruleKindToDb(v: RuleKind): String = v.name

    @TypeConverter fun categoryKind(v: String): CategoryKind = parse(v, CategoryKind.EXPENSE)
    @TypeConverter fun categoryKindToDb(v: CategoryKind): String = v.name

    // ---- 订单 / 退款 ----
    @TypeConverter fun orderStatus(v: String): OrderStatus = parse(v, OrderStatus.PAID)
    @TypeConverter fun orderStatusToDb(v: OrderStatus): String = v.name

    @TypeConverter fun deductionKind(v: String): DeductionKind = parse(v, DeductionKind.BALANCE)
    @TypeConverter fun deductionKindToDb(v: DeductionKind): String = v.name

    @TypeConverter fun refundStatus(v: String): RefundStatus = parse(v, RefundStatus.PENDING)
    @TypeConverter fun refundStatusToDb(v: RefundStatus): String = v.name

    @TypeConverter fun refundOutcome(v: String): RefundOutcome = parse(v, RefundOutcome.RETURNED)
    @TypeConverter fun refundOutcomeToDb(v: RefundOutcome): String = v.name

    @TypeConverter fun stringList(v: String?): List<String> = v?.split(LIST_SEP)?.filter { it.isNotBlank() }.orEmpty()
    @TypeConverter fun stringListToDb(v: List<String>): String = v.joinToString(LIST_SEP)
}
