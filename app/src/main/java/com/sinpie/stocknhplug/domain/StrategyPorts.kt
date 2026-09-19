package com.sinpie.stocknhplug.domain

import java.time.LocalDate

/** 가격 추적 결과를 신호로 바꾸는 순수 정책. 주문/저장/네트워크 기능을 주입하지 않는다. */
fun interface ExitPolicy {
    fun exitReason(holding: Holding, quote: Quote, sessionPeak: Long, settings: Strategy): String?
}

/** 교체 가능한 매매 전략 계약. 공통 위험 한도와 데이터 이용권한을 우회할 권한은 없다. */
interface TradingStrategy : ExitPolicy {
    val id: String

    fun evaluate(symbol: String, candles: List<Candle>, today: LocalDate): Candidate?

    /** 0 이하면 매수 보류. 사용자 주문 한도보다 큰 값을 반환해도 응용 계층에서 제한한다. */
    fun orderBudget(candidate: Candidate, settings: Strategy): Long
}
