package com.sinpie.stocknhplug.application

import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.trading.ThresholdExitPolicy
import java.time.LocalDate

/** 기본 전략 조합. 다른 전략을 쓰려면 AppContainer에서 TradingStrategy 구현을 교체한다. */
class TechnicalStrategy : TradingStrategy {
    override val id = "technical-sma-rsi-atr-v1"

    override fun evaluate(symbol: String, candles: List<Candle>, today: LocalDate) =
        SignalEngine.evaluate(symbol, candles, today)

    override fun exitReason(holding: Holding, quote: Quote, sessionPeak: Long, settings: Strategy) =
        ThresholdExitPolicy.exitReason(holding, quote, sessionPeak, settings)

    /** 변동성이 클수록 매수 예산을 줄인다. 수익률 예측이나 주문 가능수량 계산은 아니다. */
    override fun orderBudget(candidate: Candidate, settings: Strategy): Long {
        val factor = (2.0 / candidate.atrPercent.coerceAtLeast(2.0)).coerceAtMost(1.0)
        return (settings.orderBudget * factor).toLong().coerceAtLeast(10_000)
    }
}
