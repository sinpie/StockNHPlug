package com.sinpie.stocknhplug.application

import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.research.ResearchEvidence
import java.time.*

data class GroupDecision(
    val symbol: String,
    val side: Side,
    val quantity: Long,
    val reason: String,
    val occurrence: String,
    val targetPrice: Long? = null,
)

data class GroupContext(
    val group: StrategyGroup,
    val positions: List<GroupPosition>,
    val quotes: Map<String, Quote>,
    val cash: Long,
)

/** 전략 확장 지점. 주문/저장 구현을 갖지 않고 그룹에 대한 희망 주문만 반환한다. */
interface GroupAlgorithm {
    val id: String
    val name: String

    fun decide(context: GroupContext): List<GroupDecision>
}

class AveragingDownAlgorithm : GroupAlgorithm {
    override val id = "averaging"
    override val name = "물타기"

    /** 보유 종목은 현재 가격이 멀어도 희망 매수/매도가를 미리 추적 계층에 제시한다. */
    override fun decide(context: GroupContext): List<GroupDecision> = buildList {
        context.positions
            .filter { it.quantity > 0 && it.average > 0 }
            .forEach { p ->
                val sellTarget =
                    kotlin.math
                        .ceil(p.average * (1 + context.group.takeProfitPercent / 100))
                        .toLong()
                val sellQty = minOf(p.quantity, context.group.orderBudget / sellTarget)
                if (sellQty > 0)
                    add(
                        GroupDecision(
                            p.symbol,
                            Side.SELL,
                            sellQty,
                            "그룹 익절 타겟",
                            "take-profit",
                            sellTarget,
                        )
                    )
                if (p.buys <= context.group.maxAdditionalBuys) {
                    val buyTarget =
                        (p.average * (1 - context.group.dropPercent / 100))
                            .toLong()
                            .coerceAtLeast(1)
                    val buyQty =
                        (minOf(context.cash, context.group.orderBudget) / (buyTarget * 1.01))
                            .toLong()
                    if (buyQty > 0)
                        add(
                            GroupDecision(
                                p.symbol,
                                Side.BUY,
                                buyQty,
                                "그룹 추가매수 타겟",
                                "averaging",
                                buyTarget,
                            )
                        )
                }
            }
    }
}

class RebalancingAlgorithm : GroupAlgorithm {
    override val id = "rebalance"
    override val name = "리밸런싱"

    override fun decide(context: GroupContext): List<GroupDecision> {
        if (context.group.symbols.any { context.quotes[it.symbol] == null }) return emptyList()
        val nav =
            context.cash +
                context.positions.sumOf {
                    Math.multiplyExact(it.quantity, context.quotes.getValue(it.symbol).price)
                }
        if (nav <= 0) return emptyList()
        return context.group.symbols
            .mapNotNull { target ->
                val q = context.quotes.getValue(target.symbol)
                val owned = context.positions.find { it.symbol == target.symbol }?.quantity ?: 0
                val value = Math.multiplyExact(owned, q.price)
                val deviation = value * 100.0 / nav - target.weightPercent
                val desired = nav * target.weightPercent / 100
                when {
                    deviation > context.group.rebalanceBand -> {
                        val qty =
                            minOf(
                                owned,
                                (value - desired) / q.bid,
                                context.group.orderBudget / q.bid,
                            )
                        if (qty <= 0) null
                        else
                            GroupDecision(
                                target.symbol,
                                Side.SELL,
                                qty,
                                "목표 비중 초과 ${"%.1f".format(java.util.Locale.US, deviation)}%p",
                                "rebalance",
                            )
                    }
                    deviation < -context.group.rebalanceBand -> {
                        val qty =
                            (minOf(desired - value, context.group.orderBudget, context.cash) /
                                    (q.ask * 1.01))
                                .toLong()
                        if (qty <= 0) null
                        else GroupDecision(target.symbol, Side.BUY, qty, "목표 비중 미달", "rebalance")
                    }
                    else -> null
                }
            }
            .sortedBy { if (it.side == Side.SELL) 0 else 1 }
    }
}

class GroupAlgorithmRegistry(algorithms: List<GroupAlgorithm>) {
    private val implementations = algorithms.associateBy { it.id }

    init {
        require(implementations.size == algorithms.size)
    }

    val names: Map<String, String>
        get() = implementations.mapValues { it.value.name }

    fun get(id: String) = implementations[id] ?: error("지원하지 않는 그룹 전략")

    companion object {
        fun defaults() =
            GroupAlgorithmRegistry(listOf(AveragingDownAlgorithm(), RebalancingAlgorithm()))
    }
}

/** 설정된 지표는 AND로 결합한다. 필수 데이터/이용권한 게이트는 토글로 끌 수 없다. */
object GroupRecommendation {
    fun matches(rule: RecommendationRule, evidence: ResearchEvidence, now: Instant): Boolean {
        if (!rule.enabled || evidence.buyBlockers(now).isNotEmpty()) return false
        rule.validate()
        val today = now.atZone(SEOUL).toLocalDate()
        val candles =
            evidence.prices?.candles.orEmpty().filter { it.date < today }.sortedBy { it.date }
        if (
            SignalEngine.evaluate(evidence.symbol, candles, today) == null ||
                candles.size < rule.slowDays
        )
            return false
        val close = candles.map { it.close }
        val rsi = SignalEngine.rsi(close)
        val atr = SignalEngine.atr(candles) / close.last() * 100
        val momentum = (close.last() / close[close.lastIndex - 20] - 1) * 100
        val volume =
            candles.last().volume /
                candles.dropLast(1).takeLast(20).map { it.volume }.average().coerceAtLeast(1.0)
        return (!rule.trend ||
            (close.last() > close.takeLast(rule.fastDays).average() &&
                close.takeLast(rule.fastDays).average() >
                    close.takeLast(rule.slowDays).average())) &&
            (!rule.rsi || rsi in rule.rsiMin..rule.rsiMax) &&
            (!rule.momentum || momentum >= rule.momentumMin) &&
            (!rule.volume || volume >= rule.volumeMin) &&
            (!rule.volatility || atr <= rule.atrMax) &&
            (!rule.profitability ||
                evidence.financials?.operatingMargin?.toDouble()?.let { it >= rule.marginMin } ==
                    true) &&
            (!rule.leverage ||
                evidence.financials?.debtRatio?.toDouble()?.let { it <= rule.debtMax } == true)
    }
}
