package com.sinpie.stocknhplug.trading

import com.sinpie.stocknhplug.domain.*
import java.time.*
import kotlin.math.*

data class QuoteRouteStatus(
    val symbol: String,
    val mode: String,
    val distance: Double?,
    val intervalSeconds: Double,
)

/** 단조 시각으로 조회 기한을 관리한다. 같은 캐시 재독은 기한/극값을 새 관측처럼 갱신하지 않는다. */
class HybridQuotePolicy {
    private data class Route(
        var targets: List<Long> = emptyList(),
        var day: LocalDate? = null,
        var sample: PriceSnapshot? = null,
        var observed: Double = 0.0,
        var distance: Double? = null,
        var speed: Double = 0.0,
        var due: Double = 0.0,
        var interval: Double = 5.0,
        var failures: Int = 0,
        var suspended: Boolean = false,
    )

    private val routes = linkedMapOf<String, Route>()
    private var nextAdmission = 0.0

    fun setTargets(targets: Map<String, List<Long>>, now: Instant) {
        routes.keys.retainAll(targets.keys)
        val day = now.atZone(SEOUL).toLocalDate()
        targets.forEach { (symbol, prices) ->
            val r = routes.getOrPut(symbol) { Route() }
            val sorted = prices.filter { it > 0 }.distinct().sorted()
            if (r.day != day) {
                routes[symbol] = Route(sorted, day)
            } else if (r.targets != sorted) {
                val wasSuspended = r.suspended
                r.targets = sorted
                r.suspended = false
                r.sample?.let { classify(r, it, now) }
                if (wasSuspended && !r.suspended) r.due = 0.0
            }
        }
    }

    private fun classify(r: Route, sample: PriceSnapshot, now: Instant): Boolean {
        val price = sample.quote.price
        if (!sample.rules.contains(price, now)) return false
        val valid = r.targets.filter { sample.rules.contains(it, now) }
        r.suspended = r.targets.isNotEmpty() && valid.isEmpty()
        r.distance = valid.minOfOrNull { abs(it.toDouble() - price) / price * 100 }
        return true
    }

    fun observe(sample: PriceSnapshot, monotonic: Double, now: Instant): Boolean {
        val r = routes[sample.quote.symbol] ?: return false
        if (r.sample == sample) return !r.suspended
        if (r.sample?.quote?.receivedAt?.isAfter(sample.quote.receivedAt) == true) return false
        val previousDistance = r.distance
        if (!classify(r, sample, now)) {
            failed(sample.quote.symbol, monotonic)
            return false
        }
        val old = r.sample
        val elapsed = monotonic - r.observed
        if (old != null && elapsed >= 0.5) {
            val movement = abs(sample.quote.price.toDouble() / old.quote.price - 1) * 100 / elapsed
            val closing =
                if (previousDistance != null && r.distance != null)
                    max(0.0, previousDistance - r.distance!!) / elapsed
                else 0.0
            r.speed = max(max(movement, closing), r.speed * 0.75)
        }
        r.sample = sample
        r.observed = monotonic
        r.failures = 0
        val d = r.distance
        var interval =
            when {
                r.suspended -> Double.POSITIVE_INFINITY
                d == null -> 15.0 // 전략 제안 전의 발견용 시세. 제시가를 임의로 만들지 않는다.
                d <= 1 -> 2.0
                d <= 3 -> 3.0
                d <= 10 -> 5.0
                d <= 15 -> 15.0
                d <= 20 -> 30.0
                d <= 30 -> 60.0
                else -> 180.0
            }
        if (d != null && r.speed > 0 && !r.suspended) {
            val margin = if (d > 10) d - 10 else d
            if (margin > 0) interval = min(interval, max(2.0, margin / r.speed * 0.5))
        }
        r.interval = interval
        r.due = monotonic + interval
        return !r.suspended
    }

    fun websocketSymbols(capacity: Int) =
        routes.entries
            .filter {
                !it.value.suspended && it.value.distance?.let { d -> d <= 10.0 + 1e-9 } == true
            }
            .sortedWith(compareBy({ it.value.distance }, { it.key }))
            .take(capacity)
            .map { it.key }
            .toSet()

    /** 루프 한 번에 한 요청만 승인하며 누적 지연 시 오래 기다린 종목부터 처리한다. */
    fun claim(monotonic: Double): String? {
        if (monotonic < nextAdmission) return null
        val row =
            routes.entries
                .filter { !it.value.suspended && it.value.due <= monotonic }
                .minByOrNull { it.value.due } ?: return null
        nextAdmission = monotonic + 0.5
        row.value.due = monotonic + 5 // 전송 중 중복 승인 방지
        return row.key
    }

    fun failed(symbol: String, monotonic: Double) {
        val r = routes[symbol] ?: return
        r.failures = min(6, r.failures + 1)
        r.interval = min(120.0, 5.0 * 2.0.pow(r.failures - 1))
        r.due = monotonic + r.interval
    }

    fun statuses(active: Set<String>, acknowledged: Set<String>, now: Instant) =
        routes.map { (symbol, r) ->
            val mode =
                when {
                    r.suspended -> "가격범위 밖 · 중단"
                    r.sample == null -> "REST 초기 조회"
                    r.distance == null -> "REST 전략 대기"
                    r.distance!! > 10.0 + 1e-9 -> "REST 원거리"
                    symbol !in active -> "REST 구독 용량 대기"
                    symbol !in acknowledged -> "REST 구독 확인 대기"
                    r.sample!!.source != PriceSource.WEBSOCKET || !r.sample!!.quote.fresh(now) ->
                        "REST 웹소켓 복구 대기"
                    else -> "웹소켓 상세 추적"
                }
            QuoteRouteStatus(symbol, mode, r.distance, r.interval)
        }

    fun clear() {
        routes.clear()
        nextAdmission = 0.0
    }
}
