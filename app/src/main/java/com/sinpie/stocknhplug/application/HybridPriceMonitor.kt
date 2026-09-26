package com.sinpie.stocknhplug.application

import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.trading.*
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 전송 선택의 응용 조정자. 실패한 가격 조회만 backoff하며 주문 API를 재호출하지 않는다. */
class HybridPriceMonitor(
    private val provider: CurrentPriceProvider,
    private val stream: MarketStream,
    private val gate: ExecutionGate,
    private val onPrice: (Quote) -> Unit,
    private val onEvent: (String) -> Unit,
    private val monotonic: () -> Double = { System.nanoTime() / 1e9 },
    private val clock: () -> Instant = Instant::now,
    private val websocketEnabled: Boolean = true,
) {
    private val policy = HybridQuotePolicy(websocketEnabled)
    private val metadata = mutableMapOf<String, MarketRules>()
    private val latest = mutableMapOf<String, PriceSnapshot>()
    private var active = emptySet<String>()
    private var modes = emptyMap<String, String>()
    private var reconnectAt = 0.0
    private var reconnectDelay = 30.0
    private var generation = 0L

    /** 콜백은 controller의 Main scope에서 직렬 호출한다. 당일 REST 범위가 없으면 WS를 채택하지 않는다. */
    fun onWebsocket(quote: Quote) {
        if (!websocketEnabled || !trackingSession(clock())) return
        val rules = metadata[quote.symbol] ?: return
        if (quote.symbol !in active || quote.symbol !in stream.acknowledged()) return
        accept(PriceSnapshot(quote, rules, PriceSource.WEBSOCKET))
    }

    private fun accept(snapshot: PriceSnapshot) {
        val now = clock()
        val q = snapshot.quote
        if (!snapshot.valid(now)) return
        val old = latest[q.symbol]
        if (
            old != null &&
                (old.quote.receivedAt > q.receivedAt || old.quote.exchangeAt > q.exchangeAt)
        )
            return
        latest[q.symbol] = snapshot
        policy.observe(snapshot, monotonic(), now)
        gate.observe(snapshot, now)
        onPrice(q)
    }

    suspend fun step(symbols: Set<String>) {
        val now = clock()
        if (!trackingSession(now)) {
            pauseStream()
            return
        }
        updateTargets(symbols, now)
        syncStream()
        val healthy =
            if (stream.isConnected()) active.intersect(stream.acknowledged()) else emptySet()
        val due = policy.claim(monotonic(), healthy, now)
        if (due != null) {
            try {
                refresh(due)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TrackingStorageException) {
                throw e
            } catch (_: Exception) {
                policy.failed(due, monotonic())
                onEvent("$due 시세 조회 지연 · 가격 재확인 대기")
            }
            // A REST call can cross 15:15. Do not subscribe from its former near-target state.
            if (!trackingSession(clock())) {
                pauseStream()
                return
            }
            // REST can cross the boundary or move an armed reversal trigger this very step.
            updateTargets(symbols, clock())
            syncStream()
        }
        val current = statuses().associate { it.symbol to it.mode }
        current
            .filter { (symbol, mode) -> modes[symbol] != mode }
            .forEach { (symbol, mode) -> onEvent("$symbol · $mode") }
        modes = current
    }

    private fun updateTargets(symbols: Set<String>, now: Instant) {
        val targets = gate.targets().groupBy { it.symbol }
        policy.setRequests(
            symbols.associateWith { code ->
                targets[code].orEmpty().map {
                    // 실제 트리거가 있으면 그 거리를 사용한다. 아직 무장 전이면 전략 제시가를 사용한다.
                    QuoteTarget(
                        it.strategyPrice.toDouble(),
                        it.trigger?.takeIf { p -> p.isFinite() && p > 0 }
                            ?: it.strategyPrice.toDouble(),
                    )
                }
            },
            now,
            monotonic(),
        )
    }

    /**
     * Open no idle socket. Disabled/far-only modes close transport, including pending
     * subscriptions.
     */
    private suspend fun syncStream() {
        val desired = policy.websocketSymbols(10)
        if (desired.isEmpty()) {
            if (active.isNotEmpty() || stream.isConnected()) stream.close()
            active = emptySet()
            reconnectAt = 0.0
            reconnectDelay = 30.0
            return
        }
        if (desired != active) {
            active = desired
            if (stream.isConnected()) stream.replaceSubscriptions(desired)
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
    }

    fun statuses() = policy.statuses(active, stream.acknowledged(), clock())

    private fun pauseStream() {
        stream.close()
        active = emptySet()
        reconnectAt = 0.0
        reconnectDelay = 30.0
    }

    /** 명시적인 화면 조회도 자동 감시와 같은 검증 경로를 통과한다. 주문을 시작하지 않는다. */
    suspend fun refresh(symbol: String) {
        check(trackingSession(clock()))
        val owner = generation
        val result = provider.current(symbol)
        currentCoroutineContext().ensureActive()
        if (owner != generation) throw CancellationException("추적 연결 변경")
        require(
            trackingSession(clock()) &&
                result.quote.symbol == symbol &&
                result.source == PriceSource.REST &&
                result.valid(clock())
        )
        metadata[symbol] = result.rules
        accept(result)
    }

    fun clear() {
        generation++
        stream.close()
        policy.clear()
        metadata.clear()
        latest.clear()
        modes = emptyMap()
        active = emptySet()
        reconnectAt = 0.0
        reconnectDelay = 30.0
    }
}
