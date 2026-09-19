package com.sinpie.stocknhplug.trading

import com.sinpie.stocknhplug.domain.*
import java.time.Instant
import kotlin.math.*

/** KRX 현물 호가단위. ETF는 2천원 미만 1원, 그 이상 5원. 미확인 상품은 추정하지 않는다. */
object KrxTicks {
    fun unit(price: Long, kind: InstrumentKind): Long {
        require(price > 0 && kind != InstrumentKind.UNKNOWN)
        if (kind == InstrumentKind.ETF) return if (price < 2000) 1 else 5
        return when {
            price < 2000 -> 1
            price < 5000 -> 5
            price < 20000 -> 10
            price < 50000 -> 50
            price < 200000 -> 100
            price < 500000 -> 500
            else -> 1000
        }
    }

    fun down(price: Double, kind: InstrumentKind): Long {
        require(price.isFinite() && price >= 1 && price < Long.MAX_VALUE.toDouble())
        val p = price.toLong()
        return p / unit(p, kind) * unit(p, kind)
    }

    fun offset(price: Long, steps: Int, kind: InstrumentKind): Long {
        var p = down(price.toDouble(), kind)
        repeat(abs(steps)) {
            p =
                if (steps >= 0) Math.addExact(p, unit(p, kind))
                else (p - unit((p - 1).coerceAtLeast(1), kind)).coerceAtLeast(1)
        }
        return p
    }
}

/**
 * 전략 제시가/추적 극값/실제 트리거를 분리한다. NamuMagic hybrid 기본 수식에 근거한 독립 Kotlin 구현. 세션 내 상태만 유지하며 정지/계좌 변경 시
 * 폐기한다. 실제 전송 의도는 별도 영속 저널이 소유한다.
 */
class NamuExecutionGate : ExecutionGate {
    private data class Entry(
        val request: TargetRequest,
        var extreme: Long? = null,
        var trigger: Double? = null,
        var ready: Boolean = false,
        var message: String = "시세 대기",
    )

    private val entries = linkedMapOf<String, Entry>()
    private val snapshots = mutableMapOf<String, PriceSnapshot>()

    override fun evaluate(request: TargetRequest, quote: Quote, now: Instant): Boolean {
        require(request.strategyPrice > 0 && request.symbol == quote.symbol)
        val old = entries[request.key]
        // 같은 회차에서 타겟/방향이 바뀌면 극값도 새 요청으로 초기화한다. deadline은 최초값을 보존한다.
        val entry =
            if (
                old == null ||
                    old.request.strategyPrice != request.strategyPrice ||
                    old.request.symbol != request.symbol ||
                    old.request.side != request.side
            )
                Entry(request).also { entries[request.key] = it }
            else old
        val snapshot = snapshots[request.symbol]
        if (snapshot == null || snapshot.quote != quote) {
            entry.ready = false
            entry.message = "검증 시세 대기"
            return false
        }
        update(entry, snapshot, now)
        return entry.ready
    }

    override fun observe(snapshot: PriceSnapshot, now: Instant) {
        val old = snapshots[snapshot.quote.symbol]
        if (old != null && snapshot.quote.receivedAt < old.quote.receivedAt) return
        snapshots[snapshot.quote.symbol] = snapshot
        entries.values
            .filter { it.request.symbol == snapshot.quote.symbol }
            .forEach { update(it, snapshot, now) }
    }

    private fun update(entry: Entry, snapshot: PriceSnapshot, now: Instant) {
        val q = snapshot.quote
        val r = snapshot.rules
        val request = entry.request
        entry.ready = false
        if (
            !trackingSession(now) ||
                !q.fresh(now) ||
                !q.regular ||
                q.bid <= 0 ||
                q.ask < q.bid ||
                !r.contains(q.price, now) ||
                !r.contains(request.strategyPrice, now) ||
                r.kind == InstrumentKind.UNKNOWN
        ) {
            entry.trigger = null
            entry.message = "장 상태·시세·가격범위 검증 대기"
            return
        }
        val price = if (request.side == Side.BUY) q.ask else q.bid
        val acceptable =
            if (request.side == Side.BUY) price <= request.strategyPrice
            else price >= request.strategyPrice
        if (!acceptable) {
            entry.message = "제시가격 도달 대기"
            return
        }
        val previous = entry.extreme ?: request.strategyPrice
        val extreme =
            if (request.side == Side.BUY) minOf(previous, price) else maxOf(previous, price)
        entry.extreme = extreme
        val buying = request.side == Side.BUY
        val offset = KrxTicks.offset(request.strategyPrice, if (buying) 10 else -10, r.kind)
        val legacy =
            if (buying) min((extreme.toDouble() + offset) / 2, extreme * 1.1)
            else max((extreme.toDouble() + offset) / 2, extreme * 0.9)
        val deep =
            if (buying) extreme <= request.strategyPrice * 0.95
            else extreme >= request.strategyPrice * 1.05
        var trigger = legacy
        if (deep) {
            val bound = KrxTicks.offset(request.strategyPrice, if (buying) -1 else 1, r.kind)
            val excess =
                if (buying) max(0.0, bound.toDouble() - extreme)
                else max(0.0, extreme.toDouble() - bound)
            val callback = min(max(extreme * 0.04, excess * 0.5), extreme * 0.1)
            val raw =
                if (buying) min(bound.toDouble(), extreme + callback)
                else max(bound.toDouble(), extreme - callback)
            val rounded = KrxTicks.down(raw, r.kind).toDouble()
            if (buying && rounded > extreme) trigger = min(bound.toDouble(), max(legacy, rounded))
            if (!buying && rounded < extreme) trigger = max(bound.toDouble(), rounded)
        }
        entry.trigger = trigger
        entry.ready = now >= request.deadline || if (buying) price > trigger else price < trigger
        entry.message = if (entry.ready) "주문 조건 충족 · 최종 위험검사 대기" else "반전 추적 중"
    }

    override fun retain(keys: Set<String>) {
        entries.keys.retainAll(keys)
    }

    override fun clear() {
        entries.clear()
        snapshots.clear()
    }

    override fun targets() =
        entries.values.map {
            TargetStatus(
                it.request.key,
                it.request.symbol,
                it.request.side,
                it.request.strategyPrice,
                it.extreme,
                it.trigger,
                it.ready,
                it.message,
            )
        }
}
