package com.sinpie.stocknhplug.application

import com.sinpie.stocknhplug.domain.*
import java.math.BigDecimal

enum class HistoryPeriod {
    DAY,
    MONTH,
    YEAR,
}

data class PeriodStatistics(
    val period: String,
    val days: List<AccountDay>,
    val pnl: BigDecimal?,
    val fees: BigDecimal?,
    val taxes: BigDecimal?,
    val positiveDays: Int,
    val negativeDays: Int,
    val flatDays: Int,
    val filledOrders: Int,
    val turnover: BigDecimal,
    val lastCash: Long?,
    val lastEquity: Long?,
) {
    val pnlDays
        get() = positiveDays + negativeDays + flatDays

    val positiveDayRate: Double?
        get() = if (pnlDays == 0) null else positiveDays * 100.0 / pnlDays
}

/** 저장된 날짜만 집계한다. 입출금 자료가 없으므로 자산 차액/현금 차액을 수익으로 계산하지 않는다. */
object HistoryAnalytics {
    fun summarize(days: List<AccountDay>, period: HistoryPeriod): List<PeriodStatistics> {
        require(days.map { it.date }.distinct().size == days.size)
        return days
            .groupBy {
                when (period) {
                    HistoryPeriod.DAY -> it.date.toString()
                    HistoryPeriod.MONTH -> it.date.toString().take(7)
                    HistoryPeriod.YEAR -> it.date.year.toString()
                }
            }
            .map { (key, rows) ->
                val ordered = rows.sortedBy { it.date }
                val pnl = rows.mapNotNull { it.pnl }
                val fills = rows.flatMap { it.executions }.filter { it.filled > 0 }
                fun sum(value: (DailyPnl) -> Long) =
                    pnl.takeIf { it.isNotEmpty() }
                        ?.fold(BigDecimal.ZERO) { a, p -> a + value(p).toBigDecimal() }
                val last = ordered.lastOrNull { it.portfolio != null }?.portfolio
                PeriodStatistics(
                    key,
                    ordered,
                    sum { it.amount },
                    sum { it.buyFee },
                    sum { it.sellTax },
                    pnl.count { it.amount > 0 },
                    pnl.count { it.amount < 0 },
                    pnl.count { it.amount == 0L },
                    fills.size,
                    fills.fold(BigDecimal.ZERO) { a, e ->
                        a + e.filled.toBigDecimal() * BigDecimal.valueOf(e.average)
                    },
                    last?.cash,
                    last?.equity,
                )
            }
            .sortedByDescending { it.period }
    }
}
