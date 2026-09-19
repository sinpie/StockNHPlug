package com.sinpie.stocknhplug.trading

import com.sinpie.stocknhplug.domain.*
import java.time.Duration
import java.time.Instant

data class ParkingDecision(val side: Side, val quantity: Long, val reason: String)

/** 순수 자금 정책. 접수/예상 매도금액은 현금에 더하지 않고 다음 잔고 조회를 기다린다. */
object ParkingPlanner {
    fun buffered(value: Long): Long = Math.addExact(Math.multiplyExact(value, 101), 99) / 100

    /** 매수와 매도 모두 하루 회전 한도를 소비한다. 주문금액으로 예약하여 부분체결도 과다 회전을 막는다. */
    fun remaining(policy: ParkingPolicy, orders: List<OrderRecord>, now: Instant): Long {
        val spent =
            orders
                .filter {
                    it.intent.groupId == ParkingPolicy.GROUP_ID &&
                        it.status != OrderStatus.REJECTED &&
                        it.intent.at.atZone(SEOUL).toLocalDate() == now.atZone(SEOUL).toLocalDate()
                }
                .fold(0L) { total, row ->
                    Math.addExact(
                        total,
                        Math.multiplyExact(row.intent.quantity, row.intent.limitPrice),
                    )
                }
        return (policy.dailyTurnover - spent).coerceAtLeast(0)
    }

    /** 필요금액이 있으면 파킹 매도만 제안한다. 없으면 최소 현금을 남기고 유휴 현금을 파킹한다. */
    fun decide(
        policy: ParkingPolicy,
        portfolio: Portfolio,
        quote: Quote,
        owned: Long,
        requiredBuyCash: Long?,
        orders: List<OrderRecord>,
        now: Instant,
    ): ParkingDecision? {
        policy.validate()
        require(owned >= 0 && (requiredBuyCash == null || requiredBuyCash >= 0))
        if (
            !policy.enabled ||
                quote.symbol != policy.symbol ||
                !quote.regular ||
                !quote.fresh(now) ||
                quote.bid <= 0 ||
                quote.ask < quote.bid ||
                quote.price <= 0
        )
            return null
        val previous =
            orders
                .filter { it.intent.groupId == ParkingPolicy.GROUP_ID }
                .maxByOrNull { it.intent.at }
        if (
            previous != null &&
                Duration.between(previous.intent.at, now).seconds < policy.cooldownSeconds
        )
            return null
        val cap = minOf(policy.orderBudget, remaining(policy, orders, now))
        if (cap < policy.minimumTrade) return null
        if (requiredBuyCash != null) {
            val deficit = Math.addExact(requiredBuyCash, policy.reserveCash) - portfolio.cash
            if (deficit <= 0 || owned == 0L) return null
            // 수수료 여유 1%를 차감한 예상 순수입으로 수량만 산정한다. 현금 반영은 확정 조회만 한다.
            val netPerShare = quote.bid * 99 / 100
            if (netPerShare <= 0) return null
            val needed = maxOf(deficit, policy.minimumTrade)
            val qty = minOf((needed + netPerShare - 1) / netPerShare, owned, cap / quote.bid)
            return if (qty > 0 && qty * quote.bid >= policy.minimumTrade)
                ParkingDecision(Side.SELL, qty, "전략 매수 자금 확보 · 파킹 매도")
            else null
        }
        val room = (policy.maxValue - Math.multiplyExact(owned, quote.ask)).coerceAtLeast(0)
        val cash = (portfolio.cash - policy.reserveCash).coerceAtLeast(0)
        val budget = minOf(cap, room, cash)
        val qty = budget / buffered(quote.ask)
        return if (qty > 0 && qty * quote.ask >= policy.minimumTrade)
            ParkingDecision(Side.BUY, qty, "유휴 현금 파킹")
        else null
    }
}
