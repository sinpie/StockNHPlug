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
 * 전략 제시가/추적 극값/실제 트리거를 분리한다. 날짜 변경과 연결 종료는 미완료 매매의 이력을 지우지 않는다. 저장소 복원은 이력만 복원하며, 주문 가능 여부는 반드시 새
 * 당일 시세로 다시 판단한다.
 */
class NamuExecutionGate(private val store: TrackingStore? = null) : ExecutionGate {
    private data class Entry(
        var request: TargetRequest,
        var extreme: Long? = null,
        var minimum: Long? = null,
        var maximum: Long? = null,
        var active: Boolean = true,
        var trigger: Double? = null,
        var ready: Boolean = false,
        var message: String = "시세 대기",
    )

    private val entries = linkedMapOf<String, Entry>()
    private val snapshots = mutableMapOf<String, PriceSnapshot>()
    private var scope: Pair<Account, Environment>? = null
    private var saved = emptyList<TrackingRecord>()

    override fun activate(account: Account, environment: Environment) {
        clear()
        val records =
            try {
                store?.load(account, environment).orEmpty()
            } catch (_: Exception) {
                throw TrackingStorageException()
            }
        records.forEach { r ->
            entries[r.request.key] = Entry(r.request, r.extreme, r.minimum, r.maximum, r.active)
        }
        scope = account to environment
        saved = records
    }

    /** 극값/활성 상태가 달라질 때만 쓴다. 매 틱의 UI 메시지/ready/시세를 디스크에 쓰지 않는다. */
    private fun persist() {
        val records =
            entries.values.map {
                TrackingRecord(it.request, it.extreme, it.minimum, it.maximum, it.active)
            }
        if (records == saved) return
        try {
            scope?.let { (account, environment) -> store?.save(account, environment, records) }
        } catch (_: Exception) {
            throw TrackingStorageException()
        }
        saved = records
    }

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
        entry.active = true
        val snapshot = snapshots[request.symbol]
        if (snapshot == null || snapshot.quote != quote) {
            entry.ready = false
            entry.message = "검증 시세 대기"
            persist()
            return false
        }
        update(entry, snapshot, now)
        persist()
        return entry.ready
    }

    override fun observe(snapshot: PriceSnapshot, now: Instant) {
        val old = snapshots[snapshot.quote.symbol]
        if (
            old != null &&
                (snapshot.quote.receivedAt < old.quote.receivedAt ||
                    snapshot.quote.exchangeAt < old.quote.exchangeAt)
        )
            return
        snapshots[snapshot.quote.symbol] = snapshot
        entries.values
            .filter { it.active && it.request.symbol == snapshot.quote.symbol }
            .forEach { update(it, snapshot, now) }
        persist()
    }

    private fun update(entry: Entry, snapshot: PriceSnapshot, now: Instant) {
        val q = snapshot.quote
        val r = snapshot.rules
        val request = entry.request
        entry.ready = false
        if (
            !trackingSession(now) ||
                !snapshot.valid(now) ||
                !q.regular ||
                q.bid <= 0 ||
                q.ask < q.bid ||
                !r.contains(q.price, now) ||
                r.kind == InstrumentKind.UNKNOWN
        ) {
            entry.trigger = null
            entry.message = "장 상태·시세·가격범위 검증 대기"
            return
        }
        if (!r.contains(request.strategyPrice, now)) {
            entry.trigger = null
            entry.message = "전략 제시가가 당일 상하한가 밖 · 추적 중단"
            return
        }
        // 전일의 강제 판단 기한을 다음 날 개장 즉시 적용하지 않는다. 극값은 그대로 보존한다.
        val day = now.atZone(SEOUL).toLocalDate()
        if (entry.request.deadline.atZone(SEOUL).toLocalDate() < day) {
            entry.request =
                entry.request.copy(
                    deadline =
                        minOf(
                            now.plusSeconds(1800),
                            day.atTime(15, 14, 30).atZone(SEOUL).toInstant(),
                        )
                )
        }
        val price = if (request.side == Side.BUY) q.ask else q.bid
        entry.minimum = minOf(entry.minimum ?: price, price)
        entry.maximum = maxOf(entry.maximum ?: price, price)
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
        entry.ready =
            now >= entry.request.deadline || if (buying) price > trigger else price < trigger
        entry.message = if (entry.ready) "주문 조건 충족 · 최종 위험검사 대기" else "반전 추적 중"
    }

    override fun retain(keys: Set<String>) {
        entries.values.forEach {
            if (it.request.key !in keys) {
                it.active = false
                it.ready = false
                it.trigger = null
            }
        }
        persist()
    }

    override fun clear() {
        entries.clear()
        snapshots.clear()
        scope = null
        saved = emptyList()
    }

    override fun targets() =
        entries.values
            .filter { it.active }
            .map {
                TargetStatus(
                    it.request.key,
                    it.request.symbol,
                    it.request.side,
                    it.request.strategyPrice,
                    it.extreme,
                    it.trigger,
                    it.ready,
                    it.message,
                    it.minimum,
                    it.maximum,
                    it.request.strategyId,
                    it.request.groupId,
                    it.request.occurrence,
                )
            }
}
