package com.sinpie.stocknhplug.application

import com.sinpie.stocknhplug.domain.*
import java.math.BigDecimal
import java.math.MathContext

data class HoldingMetric(
    val holding: Holding,
    val value: BigDecimal,
    val returnPercent: Double?,
    val weightPercent: Double?,
)

/** 화면용 파생 지표. 주문 수량/위험 판정을 바꾸지 않으며 실제 잔고의 보고 손익만 사용한다. */
object AccountAnalytics {
    fun holdings(items: List<Holding>): List<HoldingMetric> {
        fun value(h: Holding) = h.quantity.toBigDecimal() * h.price.toBigDecimal()
        val total = items.fold(BigDecimal.ZERO) { sum, h -> sum + value(h) }
        return items.map { h ->
            val cost = h.quantity.toBigDecimal() * h.average.toBigDecimal()
            HoldingMetric(h, value(h), ratio(h.pnl.toBigDecimal(), cost), ratio(value(h), total))
        }
    }

    private fun ratio(numerator: BigDecimal, denominator: BigDecimal): Double? =
        if (denominator.signum() <= 0) null
        else
            numerator
                .multiply(BigDecimal(100))
                .divide(denominator, MathContext.DECIMAL64)
                .toDouble()

    /** 수량/다단계 호가를 추정하지 않는다. 불완전·역전 호가의 스프레드는 미제공이다. */
    fun spread(quote: Quote): Long? =
        if (quote.bid > 0 && quote.ask >= quote.bid) quote.ask - quote.bid else null

    fun spreadPercent(quote: Quote): Double? =
        spread(quote)?.let {
            it.toDouble() / (quote.bid.toDouble() / 2 + quote.ask.toDouble() / 2) * 100
        }
}
