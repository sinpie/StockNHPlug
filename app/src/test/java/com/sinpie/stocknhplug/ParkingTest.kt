package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.data.GroupCodec
import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.trading.ParkingPlanner
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ParkingTest {
    private val now = Instant.parse("2026-09-21T01:00:00Z")
    private val policy = ParkingPolicy(enabled = true, symbol = "005940")
    private val quote = Quote("005940", 10000, 10000, 10010, now, now, true)
    private val portfolio = Portfolio(1_000_000, 2_000_000, 0, emptyList(), now)

    private fun decide(
        cash: Long,
        owned: Long = 0,
        needed: Long? = null,
        p: ParkingPolicy = policy,
    ) =
        ParkingPlanner.decide(
            p,
            portfolio.copy(cash = cash),
            quote,
            owned,
            needed,
            emptyList(),
            now,
        )

    @Test
    fun idleCashKeepsReserveAndRoundingBuffer() {
        val d = decide(150_000)!!
        assertEquals(Side.BUY, d.side)
        assertEquals(9L, d.quantity)
        assertTrue(ParkingPlanner.buffered(d.quantity * quote.ask) <= 100_000)
        assertNull(decide(50_000))
    }

    @Test
    fun fundingSellsOnlyOwnedQuantityAndNeverTreatsSellAsCash() {
        assertNull(decide(50_000, 0, 90_000))
        val d = decide(50_000, 5, 90_000)!!
        assertEquals(Side.SELL, d.side)
        assertEquals(5L, d.quantity)
        assertNull(decide(150_000, 5, 90_000))
    }

    @Test
    fun maxValueMinimumTradeAndDisabledAreRespected() {
        assertNull(decide(1_000_000, 100))
        assertNull(decide(59_000))
        assertNull(decide(1_000_000, p = policy.copy(enabled = false)))
    }

    @Test
    fun staleOrOtherSymbolPriceCannotTriggerParking() {
        assertNull(
            ParkingPlanner.decide(
                policy,
                portfolio,
                quote.copy(receivedAt = now.minusSeconds(16)),
                0,
                null,
                emptyList(),
                now,
            )
        )
        assertNull(
            ParkingPlanner.decide(
                policy,
                portfolio,
                quote.copy(symbol = "005930"),
                0,
                null,
                emptyList(),
                now,
            )
        )
    }

    @Test
    fun cooldownAndDailyTurnoverCountBothDirections() {
        fun record(id: String, side: Side, time: Instant) =
            OrderRecord(
                OrderIntent(
                    id,
                    Environment.MOCK,
                    "test",
                    policy.symbol,
                    side,
                    10,
                    10000,
                    "test",
                    time,
                    "test",
                    ParkingPolicy.STRATEGY_ID,
                    ParkingPolicy.GROUP_ID,
                    "parking:$id",
                ),
                OrderStatus.ACCEPTED,
            )
        val recent = listOf(record("1", Side.SELL, now.minusSeconds(60)))
        assertNull(ParkingPlanner.decide(policy, portfolio, quote, 0, null, recent, now))
        val full =
            listOf(
                record("1", Side.BUY, now.minusSeconds(1000)),
                record("2", Side.SELL, now.minusSeconds(900)),
                record("3", Side.BUY, now.minusSeconds(800)),
            )
        assertEquals(0L, ParkingPlanner.remaining(policy, full, now))
        assertNull(ParkingPlanner.decide(policy, portfolio, quote, 10, 2_000_000, full, now))
    }

    @Test
    fun policyRoundTripAndMissingLegacyFieldDefaultsToOff() {
        val book = StrategyBook.defaults().copy(parking = policy)
        assertEquals(book, GroupCodec.readStrategyBook(GroupCodec.encode(book)))
        val legacy = GroupCodec.encode(book).apply { remove("parking") }
        assertFalse(GroupCodec.readStrategyBook(legacy).parking.enabled)
        assertTrue(
            runCatching {
                    GroupCodec.readParkingPolicy(
                        GroupCodec.encode(policy).apply { remove("reserveCash") }
                    )
                }
                .isFailure
        )
    }

    @Test
    fun reservedIdsAndRegularGroupCollisionAreRejected() {
        val book = StrategyBook.defaults().copy(parking = policy)
        assertTrue(
            runCatching {
                    book
                        .copy(
                            groups =
                                listOf(
                                    StrategyGroup(
                                        strategyId = "averaging",
                                        name = "test",
                                        symbols = listOf(GroupSymbol(policy.symbol)),
                                    )
                                )
                        )
                        .validate()
                }
                .isFailure
        )
        assertTrue(
            runCatching {
                    book
                        .copy(
                            groups =
                                listOf(
                                    StrategyGroup(
                                        id = ParkingPolicy.GROUP_ID,
                                        strategyId = "averaging",
                                        name = "test",
                                    )
                                )
                        )
                        .validate()
                }
                .isFailure
        )
    }
}
