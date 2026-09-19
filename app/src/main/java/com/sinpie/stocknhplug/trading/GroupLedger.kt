package com.sinpie.stocknhplug.trading

import com.sinpie.stocknhplug.domain.*
import java.math.BigInteger

/** 누적 체결을 재생해 그룹 원장을 만든다. 접수 주문·계좌 합산 잔고로 체결량을 추정하지 않는다. */
object GroupLedger {
    fun merge(
        orders: List<OrderRecord>,
        previous: List<GroupFillReport>,
        incoming: List<GroupFillReport>,
    ): List<GroupFillReport> {
        val known = orders.associateBy { it.intent.id }
        require(previous.map { it.orderId }.distinct().size == previous.size)
        val result = previous.associateBy { it.orderId }.toMutableMap()
        incoming.forEach { report ->
            val order = known[report.orderId] ?: error("알 수 없는 체결 주문")
            require(order.intent.groupId.isNotBlank() && order.status == OrderStatus.ACCEPTED)
            require(
                report.quantity in 0..order.intent.quantity && report.gross >= 0 && report.fees >= 0
            )
            require((report.quantity == 0L) == (report.gross == 0L))
            val old = result[report.orderId]
            if (old != null) {
                require(
                    report.quantity >= old.quantity &&
                        report.gross >= old.gross &&
                        report.fees >= old.fees &&
                        report.at >= old.at
                )
                require(!old.terminal || report == old.copy(at = report.at)) {
                    "종료 체결 정정은 자동 반영할 수 없습니다."
                }
            }
            result[report.orderId] = report
        }
        return result.values.toList()
    }

    fun ordersFor(account: Account, orders: List<OrderRecord>) =
        orders.filter {
            it.intent.account == account.number &&
                it.intent.brokerId == account.brokerId &&
                it.intent.environment == account.environment
        }

    fun positions(
        group: StrategyGroup,
        account: Account,
        orders: List<OrderRecord>,
        fills: List<GroupFillReport>,
    ): List<GroupPosition> {
        val reports = fills.associateBy { it.orderId }
        val positions = mutableMapOf<String, GroupPosition>()
        ordersFor(account, orders)
            .filter { it.intent.groupId == group.id }
            .sortedBy { it.intent.at }
            .forEach { order ->
                val r = reports[order.intent.id] ?: return@forEach
                if (r.quantity == 0L) return@forEach
                val symbol = order.intent.symbol
                val old = positions[symbol] ?: GroupPosition(symbol, 0, 0, 0, 0)
                positions[symbol] =
                    if (order.intent.side == Side.BUY) {
                        old.copy(
                            quantity = Math.addExact(old.quantity, r.quantity),
                            cost = Math.addExact(old.cost, Math.addExact(r.gross, r.fees)),
                            buys = old.buys + 1,
                        )
                    } else {
                        require(r.quantity <= old.quantity) { "그룹 보유량보다 많은 매도 체결" }
                        val cost =
                            BigInteger.valueOf(old.cost)
                                .multiply(BigInteger.valueOf(r.quantity))
                                .divide(BigInteger.valueOf(old.quantity))
                                .also { require(it.signum() >= 0 && it.bitLength() <= 63) }
                                .toLong()
                        old.copy(
                            quantity = old.quantity - r.quantity,
                            cost = old.cost - cost,
                            realized =
                                Math.addExact(
                                    old.realized,
                                    Math.subtractExact(Math.subtractExact(r.gross, r.fees), cost),
                                ),
                        )
                    }
            }
        return positions.values.toList()
    }

    fun cash(
        group: StrategyGroup,
        account: Account,
        orders: List<OrderRecord>,
        fills: List<GroupFillReport>,
    ): Long {
        val reports = fills.associateBy { it.orderId }
        return ordersFor(account, orders)
            .filter { it.intent.groupId == group.id }
            .fold(group.capital) { cash, order ->
                val r = reports[order.intent.id] ?: return@fold cash
                if (order.intent.side == Side.BUY)
                    Math.subtractExact(cash, Math.addExact(r.gross, r.fees))
                else Math.addExact(cash, Math.subtractExact(r.gross, r.fees))
            }
    }

    fun hasPending(
        account: Account,
        orders: List<OrderRecord>,
        fills: List<GroupFillReport>,
    ): Boolean {
        val completed = fills.filter { it.terminal }.map { it.orderId }.toSet()
        return ordersFor(account, orders).any {
            it.intent.groupId.isNotBlank() &&
                it.status != OrderStatus.REJECTED &&
                it.intent.id !in completed
        }
    }
}
