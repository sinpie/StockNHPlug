package com.sinpie.stocknhplug.trading

import com.sinpie.stocknhplug.domain.*

/** 기본 손절·익절·추적 손절 정책. 유효한 같은 종목 시세와 세션 고점만 엔진에서 전달받는다. */
object ThresholdExitPolicy : ExitPolicy {
    override fun exitReason(
        holding: Holding,
        quote: Quote,
        sessionPeak: Long,
        settings: Strategy,
    ): String? {
        val change = (quote.price.toDouble() / holding.average - 1) * 100
        return when {
            change <= -settings.stopLossPercent -> "손절 조건"
            change >= settings.takeProfitPercent -> "익절 조건"
            sessionPeak > holding.average &&
                (1 - quote.price.toDouble() / sessionPeak) * 100 >= settings.trailingPercent ->
                "추적 손절 조건"
            else -> null
        }
    }
}
