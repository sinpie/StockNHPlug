package com.sinpie.stocknhplug.application

import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.trading.*
import java.time.Instant
import kotlinx.coroutines.CancellationException

/** 전송 선택의 응용 조정자. 실패한 가격 조회만 backoff하며 주문 API를 재호출하지 않는다. */
class HybridPriceMonitor(
    private val provider: CurrentPriceProvider,
    private val stream: MarketStream,
    private val gate: ExecutionGate,
    private val onPrice: (Quote) -> Unit,
    private val onEvent: (String) -> Unit,
    private val monotonic: () -> Double = { System.nanoTime() / 1e9 },
    private val clock: () -> Instant = Instant::now,
) {
    private val policy = HybridQuotePolicy()
    private val metadata = mutableMapOf<String, MarketRules>()
    private val latest = mutableMapOf<String, PriceSnapshot>()
    private var active = emptySet<String>()
    private var modes = emptyMap<String, String>()
    private var reconnectAt = monotonic() + 30
    private var reconnectDelay = 30.0

    /** 콜백은 controller의 Main scope에서 직렬 호출한다. 당일 REST 범위가 없으면 WS를 채택하지 않는다. */
    fun onWebsocket(quote: Quote) {
        val rules = metadata[quote.symbol] ?: return
        if (quote.symbol !in active || quote.symbol !in stream.acknowledged()) return
        accept(PriceSnapshot(quote, rules, PriceSource.WEBSOCKET))
    }

    private fun accept(snapshot: PriceSnapshot) {
        val now = clock()
        val q = snapshot.quote
        if (!q.fresh(now) || !snapshot.rules.contains(q.price, now)) return
        val old = latest[q.symbol]
        if (old != null && old.quote.receivedAt > q.receivedAt) return
        latest[q.symbol] = snapshot
        policy.observe(snapshot, monotonic(), now)
        gate.observe(snapshot, now)
        onPrice(q)
    }

    suspend fun step(symbols: Set<String>) {
        val now = clock()
        if (!trackingSession(now)) {
            stream.replaceSubscriptions(emptySet())
            active = emptySet()
            return
        }
        if (!stream.isConnected() && monotonic() >= reconnectAt) {
            reconnectAt = monotonic() + reconnectDelay
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(300.0)
            try {
                stream.connect(active.toList())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onEvent("WebSocket 재연결 대기 · REST 추적 유지")
            }
        } else if (stream.isConnected()) reconnectDelay = 30.0
        val targets = gate.targets().groupBy { it.symbol }
        policy.setTargets(
            symbols.associateWith { code ->
                targets[code].orEmpty().map {
                    // 실제 트리거가 있으면 그 거리를 사용한다. 아직 무장 전이면 전략 제시가를 사용한다.
                    it.trigger?.toLong()?.takeIf { p -> p > 0 } ?: it.strategyPrice
                }
            },
            now,
        )
        val desired = policy.websocketSymbols(10)
        if (desired != active) {
            stream.replaceSubscriptions(desired)
            active = desired
        }
        val due = policy.claim(monotonic())
        if (due != null) {
            try {
                val result = provider.current(due)
                require(result.quote.symbol == due && result.source == PriceSource.REST)
                require(
                    result.quote.fresh(clock()) &&
                        result.rules.contains(result.quote.price, clock())
                )
                metadata[due] = result.rules
                // 전송 중 들어온 더 최신 WS 샘플의 가격을 REST 응답으로 되돌리지 않는다.
                accept(result)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                policy.failed(due, monotonic())
                onEvent("$due 시세 조회 지연 · 가격 재확인 대기")
            }
        }
        val current = statuses().associate { it.symbol to it.mode }
        current
            .filter { (symbol, mode) -> modes[symbol] != mode }
            .forEach { (symbol, mode) -> onEvent("$symbol · $mode") }
        modes = current
    }

    fun statuses() = policy.statuses(active, stream.acknowledged(), clock())

    fun clear() {
        policy.clear()
        metadata.clear()
        latest.clear()
        modes = emptyMap()
        active = emptySet()
    }
}
