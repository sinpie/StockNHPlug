package com.sinpie.stocknhplug.domain

import java.time.*

val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

enum class Environment {
    MOCK,
    LIVE,
}

enum class Side {
    BUY,
    SELL,
}

/** 앱의 전송 저널 상태. ACCEPTED는 주문번호 수신이며 체결은 Execution.filled로 별도 확인한다. */
enum class OrderStatus {
    SUBMITTING,
    ACCEPTED,
    UNKNOWN,
    REJECTED,
}

/** 계좌번호는 암호화 저장 대상으로만 사용한다. 화면에는 masked를 사용한다. */
data class Account(val number: String, val type: String) {
    fun validFor(env: Environment) =
        if (env == Environment.MOCK) type == "03" else type in setOf("01", "02")

    val masked
        get() = "•••• " + number.takeLast(4)
}

/** 가격 봉 자체는 수정주가를 보증하지 않는다. PriceHistory의 출처/수정 여부와 함께 해석한다. */
data class Candle(
    val date: LocalDate,
    val close: Double,
    val high: Double,
    val low: Double,
    val volume: Double,
)

/** 수신 시각과 거래소 시각을 분리해 재접속 후 오래된 시세가 새 가격으로 오인되지 않게 한다. */
data class Quote(
    val symbol: String,
    val price: Long,
    val bid: Long,
    val ask: Long,
    val receivedAt: Instant,
    val exchangeAt: Instant,
    val regular: Boolean,
) {
    /** 15초를 정확히 적용한다. 거래소 시각은 시계 오차 2초만 허용하며 수신 시각의 미래값은 금지한다. */
    fun fresh(now: Instant) =
        !receivedAt.isAfter(now) &&
            !exchangeAt.isAfter(now.plusSeconds(2)) &&
            Duration.between(receivedAt, now) <= Duration.ofSeconds(15) &&
            Duration.between(exchangeAt, now) <= Duration.ofSeconds(15)
}

data class Holding(
    val symbol: String,
    val name: String,
    val quantity: Long,
    val average: Long,
    val price: Long,
    val pnl: Long,
)

/** 원화 정수 잔고와 조회 완료 시각. 주문 직전 신선도를 다시 검사하며 전략별 손익으로 간주하지 않는다. */
data class Portfolio(
    val cash: Long,
    val equity: Long,
    val unrealized: Long,
    val holdings: List<Holding>,
    val at: Instant,
)

/** 사용자 전략 설정. 저장 시와 최종 주문 경계에서 validate를 모두 호출한다. */
data class Strategy(
    val symbols: List<String> = listOf("005930", "000660", "005940"),
    val orderBudget: Long = 100_000,
    val dailyBudget: Long = 300_000,
    val maxPositions: Int = 3,
    val stopLossPercent: Double = 3.0,
    val takeProfitPercent: Double = 6.0,
    val trailingPercent: Double = 2.5,
    val maxSessionLoss: Long = 30_000,
    val minScore: Int = 70,
    val manageHoldings: Boolean = false,
) {
    fun validate() {
        require(
            symbols.isNotEmpty() && symbols.size <= 10 && symbols.distinct().size == symbols.size
        ) {
            "관심종목은 중복 없이 1~10개입니다."
        }
        require(symbols.all { it.matches(Regex("[0-9]{6}")) }) { "종목코드는 6자리 숫자입니다." }
        require(orderBudget in 10_000..10_000_000 && dailyBudget in orderBudget..50_000_000) {
            "주문 예산과 일 매수 한도를 확인하세요."
        }
        require(maxPositions in 1..10 && minScore in 50..100) { "종목 수 또는 점수 범위를 확인하세요." }
        require(
            stopLossPercent in 0.5..20.0 &&
                takeProfitPercent in 1.0..50.0 &&
                trailingPercent in 0.5..20.0
        ) {
            "손절·익절·추적 비율을 확인하세요."
        }
        require(maxSessionLoss in 1000..5_000_000) { "세션 손실 한도를 확인하세요." }
    }
}

data class Candidate(
    val symbol: String,
    val score: Int,
    val rsi: Double,
    val atrPercent: Double,
    val reason: String,
)

/** 네트워크 요청보다 먼저 영속화하는 주문 의도. id는 로컬 식별자이며 증권사의 멱등성 키가 아니다. */
data class OrderIntent(
    val id: String,
    val environment: Environment,
    val account: String,
    val symbol: String,
    val side: Side,
    val quantity: Long,
    val limitPrice: Long,
    val reason: String,
    val at: Instant,
)

data class OrderRecord(
    val intent: OrderIntent,
    val status: OrderStatus,
    val brokerNumber: String = "",
)

data class Event(val at: Instant, val level: String, val message: String)

data class DailyPnl(val date: LocalDate, val amount: Long, val buyFee: Long, val sellTax: Long)

data class HoldingSnapshot(
    val account: String,
    val environment: Environment,
    val portfolio: Portfolio,
)

/** 매매 계층의 실행 포트. REST 필드나 인증 방법을 위 계층에 노출하지 않는다. */
interface Broker {
    val environment: Environment

    suspend fun accounts(): List<Account>

    suspend fun portfolio(account: Account): Portfolio

    suspend fun candles(symbol: String): List<Candle>

    suspend fun available(account: Account, symbol: String, side: Side, price: Long): Long

    /** 접수 주문번호를 반환한다. 예외는 미접수를 보장하지 않으므로 자동 재호출하지 않는다. */
    suspend fun place(account: Account, intent: OrderIntent): String

    suspend fun executions(account: Account, date: LocalDate): List<Execution>
}

data class Execution(
    val number: String,
    val symbol: String,
    val name: String,
    val side: String,
    val ordered: Long,
    val filled: Long,
    val remaining: Long,
    val average: Double,
)

/** 주문 사전 영속 기록 포트. 저장 실패를 삼키지 않는다. 미확인 레코드는 재시작 이후에도 주문을 차단한다. */
interface OrderJournal {
    /** true 반환 전에 SUBMITTING을 저장해야 한다. 메모리 등록만으로 성공을 반환해서는 안 된다. */
    fun reserve(intent: OrderIntent): Boolean

    fun update(id: String, status: OrderStatus, brokerNumber: String = "")

    fun records(): List<OrderRecord>
}
