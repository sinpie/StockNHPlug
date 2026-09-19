package com.sinpie.stocknhplug.trading

import com.sinpie.stocknhplug.domain.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.*
import java.util.UUID
import kotlin.math.*

/** Middle layer: quote tracking, risk gates, durable pre-send reservation, no automatic resubmit. */
class TradingEngine(private val broker: Broker, private val journal: OrderJournal, private val now: () -> Instant = Instant::now) {
    private val mutex = Mutex()
    @Volatile var running = false; private set
    private var sessionEquity = 0L
    private val highWater = mutableMapOf<String, Long>()
    fun start(portfolio: Portfolio) { require(portfolio.equity > 0); sessionEquity = portfolio.equity; highWater.clear(); running = true }
    fun stop() { running = false }
    fun exitReason(holding: Holding, quote: Quote, settings: Strategy): String? {
        if (holding.average <= 0 || !quote.fresh(now())) return null
        val peak = max(highWater[holding.symbol] ?: holding.price, quote.price)
        highWater[holding.symbol] = peak
        val change = (quote.price.toDouble() / holding.average - 1) * 100
        return when {
            change <= -settings.stopLossPercent -> "손절 조건"
            change >= settings.takeProfitPercent -> "익절 조건"
            peak > holding.average && (1 - quote.price.toDouble() / peak) * 100 >= settings.trailingPercent -> "추적 손절 조건"
            else -> null
        }
    }
    suspend fun submit(account: Account, portfolio: Portfolio, quote: Quote, side: Side, reason: String, settings: Strategy): OrderRecord = mutex.withLock {
        settings.validate()
        check(running) { "자동매매가 정지되어 있습니다." }
        check(account.validFor(broker.environment)) { "계좌와 거래 환경이 다릅니다." }
        val instant = now(); val local = instant.atZone(SEOUL)
        check(local.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) && local.toLocalTime() >= LocalTime.of(9, 5) && local.toLocalTime() < LocalTime.of(15, 15)) { "자동매매 운영시간은 평일 09:05~15:15입니다." }
        check(quote.regular && quote.fresh(instant)) { "정규장 실시간 시세를 기다립니다." }
        check(Duration.between(portfolio.at, instant).seconds in 0..60) { "잔고를 다시 동기화하세요." }
        check(sessionEquity - portfolio.equity < settings.maxSessionLoss) { stop(); "세션 손실 한도에 도달했습니다." }
        check(quote.bid > 0 && quote.ask >= quote.bid && (quote.ask - quote.bid).toDouble() / quote.bid <= 0.01) { "호가 간격이 너무 넓거나 유효하지 않습니다." }
        val records = journal.records().filter { it.intent.account == account.number && it.intent.environment == broker.environment }
        check(records.none { it.status in setOf(OrderStatus.UNKNOWN, OrderStatus.SUBMITTING) }) { "확인되지 않은 주문이 있습니다. 증권사 주문 내역을 확인하세요." }
        val today = records.filter { it.intent.at.atZone(SEOUL).toLocalDate() == local.toLocalDate() }
        check(today.none { it.intent.symbol == quote.symbol && it.intent.side == side }) { "동일 종목·방향은 하루 한 번만 주문합니다." }
        val holding = portfolio.holdings.find { it.symbol == quote.symbol }
        val price = if (side == Side.BUY) quote.ask else quote.bid
        val requested = if (side == Side.BUY) {
            check(holding == null) { "이미 보유한 종목입니다." }
            val pendingBuys = today.filter { it.intent.side == Side.BUY && it.status != OrderStatus.REJECTED }
            check((portfolio.holdings.map { it.symbol } + pendingBuys.map { it.intent.symbol }).distinct().size < settings.maxPositions) { "보유 종목 한도입니다." }
            val spent = pendingBuys.sumOf { Math.multiplyExact(it.intent.quantity, it.intent.limitPrice) }
            val budget = min(settings.orderBudget, min(settings.dailyBudget - spent, portfolio.cash))
            // ATR sizing belongs to application policy; this final gate includes a 1% cash buffer.
            (budget.coerceAtLeast(0) / (price * 1.01)).toLong()
        } else { check(settings.manageHoldings) { "보유종목 자동관리에 동의하세요." }; holding?.quantity ?: 0L }
        val available = broker.available(account, quote.symbol, side, price)
        val qty = min(requested, available)
        check(qty > 0) { "주문 가능한 수량이 없습니다." }
        check(running && quote.fresh(now())) { "정지 요청 또는 시세 지연으로 주문을 막았습니다." }
        val intent = OrderIntent(UUID.randomUUID().toString(), broker.environment, account.number, quote.symbol, side, qty, price, reason, now())
        check(journal.reserve(intent)) { "주문 기록 저장 실패" }
        try {
            check(running) { "주문 정지" }
            val number = broker.place(account, intent)
            check(number.isNotBlank())
            journal.update(intent.id, OrderStatus.ACCEPTED, number)
            OrderRecord(intent, OrderStatus.ACCEPTED, number)
        } catch (error: Exception) {
            // Even cancellation/HTTP failures may arrive after the server accepted the request.
            journal.update(intent.id, OrderStatus.UNKNOWN)
            stop()
            throw IllegalStateException("주문 결과 미확인: 자동매매를 정지했습니다. 재주문하지 말고 증권사 내역을 확인하세요.")
        }
    }
}
