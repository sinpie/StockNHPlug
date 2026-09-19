package com.sinpie.stocknhplug.domain

import java.time.*

val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
enum class Environment { MOCK, LIVE }
enum class Side { BUY, SELL }
enum class OrderStatus { SUBMITTING, ACCEPTED, UNKNOWN, REJECTED }
data class Account(val number: String, val type: String) {
    fun validFor(env: Environment) = if (env == Environment.MOCK) type == "03" else type in setOf("01", "02")
    val masked get() = "•••• " + number.takeLast(4)
}
data class Candle(val date: LocalDate, val close: Double, val high: Double, val low: Double, val volume: Double)
data class Quote(val symbol: String, val price: Long, val bid: Long, val ask: Long,
    val receivedAt: Instant, val exchangeAt: Instant, val regular: Boolean) {
    fun fresh(now: Instant) = !receivedAt.isAfter(now) && !exchangeAt.isAfter(now.plusSeconds(2)) &&
        Duration.between(receivedAt, now).seconds <= 15 && Duration.between(exchangeAt, now).seconds <= 15
}
data class Holding(val symbol: String, val name: String, val quantity: Long, val average: Long, val price: Long, val pnl: Long)
data class Portfolio(val cash: Long, val equity: Long, val unrealized: Long, val holdings: List<Holding>, val at: Instant)
data class Strategy(val symbols: List<String> = listOf("005930", "000660", "005940"),
    val orderBudget: Long = 100_000, val dailyBudget: Long = 300_000, val maxPositions: Int = 3,
    val stopLossPercent: Double = 3.0, val takeProfitPercent: Double = 6.0,
    val trailingPercent: Double = 2.5, val maxSessionLoss: Long = 30_000,
    val minScore: Int = 70, val manageHoldings: Boolean = false) {
    fun validate() {
        require(symbols.isNotEmpty() && symbols.size <= 10 && symbols.distinct().size == symbols.size) { "관심종목은 중복 없이 1~10개입니다." }
        require(symbols.all { it.matches(Regex("[0-9]{6}")) }) { "종목코드는 6자리 숫자입니다." }
        require(orderBudget in 10_000..10_000_000 && dailyBudget in orderBudget..50_000_000) { "주문 예산과 일 매수 한도를 확인하세요." }
        require(maxPositions in 1..10 && minScore in 50..100) { "종목 수 또는 점수 범위를 확인하세요." }
        require(stopLossPercent in 0.5..20.0 && takeProfitPercent in 1.0..50.0 && trailingPercent in 0.5..20.0) { "손절·익절·추적 비율을 확인하세요." }
        require(maxSessionLoss in 1000..5_000_000) { "세션 손실 한도를 확인하세요." }
    }
}
data class Candidate(val symbol: String, val score: Int, val rsi: Double, val atrPercent: Double, val reason: String)
data class OrderIntent(val id: String, val environment: Environment, val account: String, val symbol: String,
    val side: Side, val quantity: Long, val limitPrice: Long, val reason: String, val at: Instant)
data class OrderRecord(val intent: OrderIntent, val status: OrderStatus, val brokerNumber: String = "")
data class Event(val at: Instant, val level: String, val message: String)
data class DailyPnl(val date: LocalDate, val amount: Long, val buyFee: Long, val sellTax: Long)
data class HoldingSnapshot(val account: String, val environment: Environment, val portfolio: Portfolio)

interface Broker {
    val environment: Environment
    suspend fun accounts(): List<Account>
    suspend fun portfolio(account: Account): Portfolio
    suspend fun candles(symbol: String): List<Candle>
    suspend fun available(account: Account, symbol: String, side: Side, price: Long): Long
    suspend fun place(account: Account, intent: OrderIntent): String
    suspend fun executions(account: Account, date: LocalDate): List<Execution>
}
data class Execution(val number: String, val symbol: String, val name: String, val side: String,
    val ordered: Long, val filled: Long, val remaining: Long, val average: Double)
interface OrderJournal {
    fun reserve(intent: OrderIntent): Boolean
    fun update(id: String, status: OrderStatus, brokerNumber: String = "")
    fun records(): List<OrderRecord>
}
