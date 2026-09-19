package com.sinpie.stocknhplug.domain

import java.time.*

enum class PriceSource {
    REST,
    WEBSOCKET,
}

enum class InstrumentKind {
    STOCK,
    ETF,
    UNKNOWN,
}

/** 당일 가격제한·호가단위 메타데이터. 전일 범위나 알 수 없는 상품으로 주문을 허용하지 않는다. */
data class MarketRules(
    val day: LocalDate,
    val lower: Long,
    val upper: Long,
    val kind: InstrumentKind,
) {
    fun contains(price: Long, now: Instant) =
        day == now.atZone(SEOUL).toLocalDate() &&
            lower > 0 &&
            upper > lower &&
            price in lower..upper
}

data class PriceSnapshot(val quote: Quote, val rules: MarketRules, val source: PriceSource) {
    /** 체결가뿐 아니라 실제 주문에 쓰는 양방향 호가도 당일 제한 범위에 있어야 한다. */
    fun valid(now: Instant) =
        quote.fresh(now) &&
            quote.bid > 0 &&
            quote.ask >= quote.bid &&
            listOf(quote.price, quote.bid, quote.ask).all { rules.contains(it, now) }
}

/** 가격 조회는 주문 Broker와 분리한다. 구현체만 인증/REST 필드/시세 서버를 안다. */
fun interface CurrentPriceProvider {
    suspend fun current(symbol: String): PriceSnapshot
}

data class TargetRequest(
    val key: String,
    val symbol: String,
    val side: Side,
    val strategyPrice: Long,
    val deadline: Instant,
)

data class TargetStatus(
    val key: String,
    val symbol: String,
    val side: Side,
    val strategyPrice: Long,
    val extreme: Long?,
    val trigger: Double?,
    val ready: Boolean,
    val message: String,
)

/** 전략은 제시가격만 전달한다. 추적계층을 교체해도 최종 TradingEngine의 위험/저널 검사는 유지된다. */
interface ExecutionGate {
    fun evaluate(request: TargetRequest, quote: Quote, now: Instant): Boolean

    fun observe(snapshot: PriceSnapshot, now: Instant)

    fun retain(keys: Set<String>)

    fun clear()

    fun targets(): List<TargetStatus>
}

fun trackingSession(now: Instant): Boolean {
    val local = now.atZone(SEOUL)
    return local.dayOfWeek.value <= 5 &&
        local.toLocalTime() >= LocalTime.of(9, 5) &&
        local.toLocalTime() < LocalTime.of(15, 15)
}
