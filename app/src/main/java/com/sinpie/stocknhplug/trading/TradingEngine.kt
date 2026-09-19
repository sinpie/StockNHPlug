package com.sinpie.stocknhplug.trading

import com.sinpie.stocknhplug.domain.*
import java.time.*
import java.util.UUID
import kotlin.math.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Middle layer: quote tracking, risk gates, durable pre-send reservation, no automatic resubmit.
 */
class TradingEngine(
    private val broker: Broker,
    private val journal: OrderJournal,
    private val exitPolicy: ExitPolicy = ThresholdExitPolicy,
    private val now: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()
    @Volatile
    var running = false
        private set

    private var sessionEquity = 0L
    private val highWater = mutableMapOf<String, Long>()

    /** 세션 기준 순자산과 고점을 초기화한다. 실행 중 호출로 손실 기준을 재설정하지 못하게 한다. */
    fun start(portfolio: Portfolio) {
        check(!running) { "이미 실행 중인 세션입니다." }
        require(portfolio.equity > 0)
        sessionEquity = portfolio.equity
        highWater.clear()
        running = true
    }

    /** 네트워크 완료를 기다리지 않는 정지 플래그. 이미 전송한 요청의 결과는 저널에서 별도로 확인한다. */
    fun stop() {
        running = false
    }

    /** 같은 종목의 유효한 정규장 가격으로 손절/익절/세션 고점 추적을 평가한다. null이면 청산 조건 없음이다. */
    fun exitReason(holding: Holding, quote: Quote, settings: Strategy): String? {
        if (
            holding.symbol != quote.symbol ||
                holding.average <= 0 ||
                !quote.regular ||
                !quote.fresh(now())
        )
            return null
        val peak = max(highWater[holding.symbol] ?: holding.price, quote.price)
        highWater[holding.symbol] = peak
        return exitPolicy.exitReason(holding, quote, peak, settings)
    }

    /**
     * 주문 직렬화 진입점. 위험검사 → 증권사 가능수량 → 사전 저널 → 전송 → 접수 기록 순서다. 서버가 접수했을 가능성이 있는 예외는 UNKNOWN이며 재시도하지
     * 않는다. 반환값은 체결 확인이 아니다.
     */
    suspend fun submit(
        account: Account,
        portfolio: Portfolio,
        quote: Quote,
        side: Side,
        reason: String,
        settings: Strategy,
        allocation: GroupAllocation? = null,
    ): OrderRecord =
        mutex.withLock {
            settings.validate()
            check(running) { "자동매매가 정지되어 있습니다." }
            check(account.validFor(broker.environment) && account.brokerId == broker.id) {
                "계좌와 증권사·거래 환경이 다릅니다."
            }
            val instant = now()
            val local = instant.atZone(SEOUL)
            check(
                local.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) &&
                    local.toLocalTime() >= LocalTime.of(9, 5) &&
                    local.toLocalTime() < LocalTime.of(15, 15)
            ) {
                "자동매매 운영시간은 평일 09:05~15:15입니다."
            }
            check(quote.regular && quote.fresh(instant)) { "정규장 실시간 시세를 기다립니다." }
            check(
                !portfolio.at.isAfter(instant) &&
                    Duration.between(portfolio.at, instant) <= Duration.ofSeconds(60)
            ) {
                "잔고를 다시 동기화하세요."
            }
            check(sessionEquity - portfolio.equity < settings.maxSessionLoss) {
                stop()
                "세션 손실 한도에 도달했습니다."
            }
            check(
                quote.bid > 0 &&
                    quote.ask >= quote.bid &&
                    (quote.ask - quote.bid).toDouble() / quote.bid <= 0.01
            ) {
                "호가 간격이 너무 넓거나 유효하지 않습니다."
            }
            val records =
                journal.records().filter {
                    it.intent.brokerId == broker.id &&
                        it.intent.account == account.number &&
                        it.intent.environment == broker.environment
                }
            check(
                records.none { it.status in setOf(OrderStatus.UNKNOWN, OrderStatus.SUBMITTING) }
            ) {
                "확인되지 않은 주문이 있습니다. 증권사 주문 내역을 확인하세요."
            }
            val today =
                records.filter { it.intent.at.atZone(SEOUL).toLocalDate() == local.toLocalDate() }
            val parking = allocation?.groupId == ParkingPolicy.GROUP_ID
            if (parking)
                require(
                    allocation!!.strategyId == ParkingPolicy.STRATEGY_ID &&
                        allocation.occurrence.matches(Regex("parking:[0-9]+"))
                )
            check(
                (if (parking) records else today).none {
                    it.intent.symbol == quote.symbol &&
                        it.intent.side == side &&
                        it.intent.groupId == (allocation?.groupId ?: "") &&
                        (allocation?.groupId != ParkingPolicy.GROUP_ID ||
                            it.intent.occurrence == allocation.occurrence)
                }
            ) {
                "같은 전략 주문 또는 파킹 회차가 이미 기록되어 있습니다."
            }
            val holding = portfolio.holdings.find { it.symbol == quote.symbol }
            val price = if (side == Side.BUY) quote.ask else quote.bid
            val requested =
                if (side == Side.BUY) {
                    check(allocation != null || holding == null) { "이미 보유한 종목입니다." }
                    val pendingBuys =
                        today.filter {
                            it.intent.side == Side.BUY && it.status != OrderStatus.REJECTED
                        }
                    check(
                        (portfolio.holdings.map { it.symbol } +
                                pendingBuys.map { it.intent.symbol })
                            .distinct()
                            .size < settings.maxPositions || (allocation != null && holding != null)
                    ) {
                        "보유 종목 한도입니다."
                    }
                    val spent =
                        pendingBuys.sumOf {
                            Math.multiplyExact(it.intent.quantity, it.intent.limitPrice)
                        }
                    val budget =
                        min(settings.orderBudget, min(settings.dailyBudget - spent, portfolio.cash))
                    // ATR sizing belongs to application policy; this final gate includes a 1% cash
                    // buffer.
                    (min(budget, allocation?.cash ?: Long.MAX_VALUE).coerceAtLeast(0) /
                            (price * 1.01))
                        .toLong()
                } else {
                    check(settings.manageHoldings) { "보유종목 자동관리에 동의하세요." }
                    min(
                        min(holding?.quantity ?: 0L, allocation?.owned ?: Long.MAX_VALUE),
                        settings.orderBudget / price,
                    )
                }
            val available = broker.available(account, quote.symbol, side, price)
            if (allocation != null)
                require(
                    allocation.groupId.isNotBlank() &&
                        allocation.strategyId.isNotBlank() &&
                        allocation.quantity > 0 &&
                        allocation.owned >= 0 &&
                        allocation.cash >= 0
                )
            val qty = min(min(requested, available), allocation?.quantity ?: Long.MAX_VALUE)
            check(qty > 0) { "주문 가능한 수량이 없습니다." }
            check(running && quote.fresh(now())) { "정지 요청 또는 시세 지연으로 주문을 막았습니다." }
            val intent =
                OrderIntent(
                    UUID.randomUUID().toString(),
                    broker.environment,
                    account.number,
                    quote.symbol,
                    side,
                    qty,
                    price,
                    reason,
                    now(),
                    broker.id,
                    allocation?.strategyId ?: "",
                    allocation?.groupId ?: "",
                    allocation?.occurrence ?: "",
                )
            check(journal.reserve(intent)) { "주문 기록 저장 실패" }
            try {
                check(running) { "주문 정지" }
                val number = broker.place(account, intent)
                check(number.isNotBlank())
                journal.update(intent.id, OrderStatus.ACCEPTED, number)
                OrderRecord(intent, OrderStatus.ACCEPTED, number)
            } catch (error: Exception) {
                // Even cancellation/HTTP failures may arrive after the server accepted the request.
                // 정지 플래그가 디스크 쓰기보다 먼저다. 저장소까지 고장나도 후속 주문은 금지한다.
                stop()
                journal.update(intent.id, OrderStatus.UNKNOWN)
                throw IllegalStateException("주문 결과 미확인: 자동매매를 정지했습니다. 재주문하지 말고 증권사 내역을 확인하세요.")
            }
        }
}
