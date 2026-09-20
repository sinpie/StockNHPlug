package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.application.*
import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.trading.*
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.*
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Each resource row is a separate JUnit test, with a stable ID and independently generated oracle.
 */
@RunWith(Parameterized::class)
class CombinationCaseTest(private val case: Case) {
    data class Case(
        val id: String,
        val family: String,
        val values: List<Long>,
        val expected: JSONObject,
    ) {
        fun int(i: Int) = values[i].toInt()

        override fun toString() = "$id-$family-${values.joinToString("_")}"
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val rows =
                requireNotNull(
                        CombinationCaseTest::class.java.getResourceAsStream("/combination-v1.tsv")
                    )
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readLines() }
                    .drop(1)
                    .filter { it.isNotBlank() }
                    .map { line ->
                        val v = line.split('\t')
                        require(v.size == 8)
                        Case(v[0], v[1], v.subList(2, 7).map(String::toLong), JSONObject(v[7]))
                    }
            require(rows.size == 1000 && rows.map { it.id }.toSet().size == 1000)
            return rows.map { arrayOf<Any>(it) }
        }
    }

    private val now = Instant.parse("2026-09-21T01:00:00Z")
    private val account = Account("fixture-account-1111", Environment.MOCK, "nhplug")
    private val symbol = "005930"

    private fun quote() = Quote(symbol, 10000, 10000, 10000, now, now, true)

    @Test
    fun verifyContract() {
        when (case.family) {
            "ROUTE" -> route()
            "ORDER" -> order()
            "LEDGER" -> ledger()
            "EXPORT" -> export()
            "SCHEDULE" -> schedule()
            "ALGORITHM" -> algorithm()
            else -> error("Unknown matrix family ${case.family}")
        }
    }

    private fun route() {
        val delta = listOf(1, 100, 300, 999, 1000, 1001, 1500, 2000, 3000, 4000)[case.int(0)]
        val enabled = case.int(1) == 1
        val scenario = case.int(3)
        val target = 10000.0 + delta * case.int(2)
        val requests =
            when (scenario) {
                2 ->
                    listOf(QuoteTarget(target), QuoteTarget(10100.0), QuoteTarget(51000.0, 10000.0))
                3 -> listOf(QuoteTarget(51000.0 + delta, target))
                4 -> listOf(QuoteTarget(500.0 + case.int(2) * delta / 10.0, target))
                else -> listOf(QuoteTarget(target))
            }
        val policy = HybridQuotePolicy(enabled)
        policy.setRequests(mapOf(symbol to requests), now)
        val sample =
            PriceSnapshot(
                quote(),
                MarketRules(now.atZone(SEOUL).toLocalDate(), 1000, 50000, InstrumentKind.STOCK),
                if (scenario == 1) PriceSource.WEBSOCKET else PriceSource.REST,
            )
        policy.observe(sample, 0.0, now)
        assertEquals(case.expected.getBoolean("ws"), symbol in policy.websocketSymbols(10))
        val interval = case.expected.getDouble("interval")
        val actual = policy.statuses(emptySet(), emptySet(), now).single()
        val streaming = policy.websocketSymbols(10)
        if (scenario == 1 && case.expected.getBoolean("ws")) {
            assertEquals("웹소켓 상세 추적", policy.statuses(streaming, streaming, now).single().mode)
            assertNull(policy.claim(interval, streaming, now))
        }
        if (interval < 0) {
            assertTrue(actual.intervalSeconds.isInfinite())
            assertNull(policy.claim(100000.0))
        } else {
            assertEquals(interval, actual.intervalSeconds, 0.0)
            assertNull(policy.claim(interval - 0.001))
            // Re-reading cached data cannot postpone the due read.
            policy.observe(sample, interval - 0.001, now)
            assertEquals(symbol, policy.claim(interval))
            assertNull(policy.claim(interval + 0.1))
        }
    }

    private class Journal : OrderJournal {
        val rows = mutableListOf<OrderRecord>()

        override fun records() = rows.toList()

        override fun reserve(intent: OrderIntent): Boolean {
            check(rows.none { it.intent.id == intent.id })
            rows += OrderRecord(intent, OrderStatus.SUBMITTING)
            return true
        }

        override fun update(id: String, status: OrderStatus, brokerNumber: String) {
            val i = rows.indexOfFirst { it.intent.id == id }
            check(i >= 0)
            rows[i] = rows[i].copy(status = status, brokerNumber = brokerNumber)
        }
    }

    /** This broker has no transport, URLs, credential storage or live API implementation. */
    private class MemoryBroker(val journal: Journal) : Broker {
        override val id = "nhplug"
        override val environment = Environment.MOCK
        var sent = 0
        var timeout = false
        var availableHook: () -> Unit = {}

        override suspend fun accounts() = error("not part of scenario")

        override suspend fun portfolio(account: Account): Portfolio = error("not part of scenario")

        override suspend fun executions(account: Account, date: LocalDate) =
            error("not part of scenario")

        override suspend fun dailyPnl(account: Account) = error("not part of scenario")

        override suspend fun available(
            account: Account,
            symbol: String,
            side: Side,
            price: Long,
        ): Long {
            availableHook()
            return 100
        }

        override suspend fun place(account: Account, intent: OrderIntent): String {
            assertEquals(
                OrderStatus.SUBMITTING,
                journal.rows.single { it.intent.id == intent.id }.status,
            )
            sent++
            if (timeout) throw java.io.IOException("synthetic uncertain outcome")
            return "fixture-accepted"
        }
    }

    private fun order() = runBlocking {
        var clock = now
        val journal = Journal()
        val broker = MemoryBroker(journal)
        val side = Side.values()[case.int(3)]
        val p =
            Portfolio(
                1000000,
                1200000,
                0,
                listOf(Holding(symbol, "fixture", 20, 10000, 10000, 0)),
                now,
            )
        val q =
            when (case.int(0)) {
                1 -> quote().copy(exchangeAt = now.minusSeconds(15))
                2 -> quote().copy(exchangeAt = now.minusSeconds(15).minusNanos(1))
                3 -> quote().copy(receivedAt = now.plusNanos(1))
                4 -> quote().copy(regular = false)
                else -> quote()
            }
        if (case.int(1) != 0)
            journal.rows +=
                OrderRecord(
                    OrderIntent(
                        "prior",
                        Environment.MOCK,
                        if (case.int(1) == 4) "other-account" else account.number,
                        symbol,
                        side,
                        1,
                        10000,
                        "fixture",
                        now,
                        account.brokerId,
                        "averaging",
                        if (case.int(1) == 3) "other-group" else "g1",
                        "prior",
                    ),
                    if (case.int(1) in listOf(1, 4)) OrderStatus.UNKNOWN else OrderStatus.ACCEPTED,
                )
        val before = journal.records()
        val engine = TradingEngine(broker, journal) { clock }
        engine.start(p)
        when (case.int(2)) {
            1 -> broker.availableHook = { clock = now.plusSeconds(16) }
            2 -> broker.timeout = true
            3 -> engine.stop()
        }
        val allocation = GroupAllocation("averaging", "g1", "matrix", 1, 20, 100000)
        val result = runCatching {
            engine.submit(
                account,
                p,
                q,
                side,
                "matrix",
                Strategy(manageHoldings = true),
                allocation,
            )
        }
        assertEquals(case.expected.getInt("sent"), broker.sent)
        when (case.expected.getString("result")) {
            "ACCEPTED" -> {
                assertEquals(OrderStatus.ACCEPTED, result.getOrThrow().status)
                assertEquals(1L, result.getOrThrow().intent.quantity)
            }
            "UNKNOWN" -> {
                assertTrue(result.isFailure)
                assertFalse(engine.running)
                assertEquals(OrderStatus.UNKNOWN, journal.rows.last().status)
                engine.start(p)
                assertTrue(
                    runCatching {
                            engine.submit(
                                account,
                                p,
                                quote(),
                                side,
                                "retry",
                                Strategy(manageHoldings = true),
                                allocation,
                            )
                        }
                        .isFailure
                )
                assertEquals(1, broker.sent)
            }
            else -> {
                assertTrue(result.isFailure)
                assertEquals(before, journal.records())
            }
        }
        assertEquals(before.size + broker.sent, journal.rows.size)
        assertEquals(before, journal.rows.take(before.size))
    }

    private fun ledger() {
        val quantity = case.values[0]
        val fee = case.values[1]
        val algorithm = if (case.int(3) == 0) "averaging" else "rebalance"
        val group = StrategyGroup("g1", algorithm, "fixture", symbols = listOf(GroupSymbol(symbol)))
        fun record(id: String, side: Side, qty: Long, time: Instant) =
            OrderRecord(
                OrderIntent(
                    id,
                    account.environment,
                    account.number,
                    symbol,
                    side,
                    qty,
                    10000,
                    "fixture",
                    time,
                    account.brokerId,
                    algorithm,
                    group.id,
                ),
                OrderStatus.ACCEPTED,
                id,
            )
        val buy = record("buy", Side.BUY, quantity, now)
        val other =
            record("other", Side.BUY, 99, now).let {
                it.copy(
                    intent =
                        when (case.int(2)) {
                            0 -> it.intent.copy(account = "other")
                            1 -> it.intent.copy(brokerId = "other")
                            2 -> it.intent.copy(environment = Environment.LIVE)
                            else ->
                                it.intent.copy(
                                    groupId = ParkingPolicy.GROUP_ID,
                                    strategyId = ParkingPolicy.STRATEGY_ID,
                                )
                        }
                )
            }
        val orders = mutableListOf(buy, other)
        val incoming =
            mutableListOf(
                GroupFillReport("buy", quantity, quantity * 10000, fee, true, now),
                GroupFillReport("other", 99, 990000, 99, true, now),
            )
        val sold = quantity / 2
        if (sold > 0) {
            orders += record("sell", Side.SELL, sold, now.plusSeconds(1))
            incoming += GroupFillReport("sell", sold, sold * 12000, 1, true, now.plusSeconds(1))
        }
        val first = GroupLedger.merge(orders, emptyList(), incoming)
        val repeated = GroupLedger.merge(orders, first, incoming)
        assertEquals(first, repeated)
        val position = GroupLedger.positions(group, account, orders, repeated).single()
        assertEquals(case.expected.getLong("quantity"), position.quantity)
        assertEquals(case.expected.getLong("cost"), position.cost)
        assertEquals(case.expected.getLong("realized"), position.realized)
        assertEquals(
            case.expected.getLong("cash"),
            GroupLedger.cash(group, account, orders, repeated),
        )
        assertFalse(GroupLedger.hasPending(account, orders, repeated))
        assertEquals(1, position.buys)
        assertTrue(
            runCatching {
                    GroupLedger.merge(
                        orders,
                        repeated,
                        listOf(incoming.first().copy(quantity = quantity - 1)),
                    )
                }
                .isFailure
        )
    }

    private fun export() {
        val names = listOf("검증종목", "=SUM(1,2)", "종목,\"이름\"", "두 줄\n종목", "@SUM(1,2)")
        val safeNames = listOf("검증종목", "'=SUM(1,2)", "종목,\"이름\"", "두 줄\n종목", "'@SUM(1,2)")
        val dates =
            if (case.int(3) == 0) listOf("2025-12-31", "2026-01-02", "2026-01-03")
            else listOf("2026-01-31", "2026-02-01", "2026-02-02")
        val rows =
            dates
                .mapIndexed { i, text ->
                    val d = LocalDate.parse(text)
                    AccountDay(
                        d,
                        if (i == 2) null
                        else
                            Portfolio(
                                1000 + i.toLong(),
                                10000,
                                0,
                                emptyList(),
                                d.atTime(15, 0).atZone(SEOUL).toInstant(),
                            ),
                        listOf(
                            Execution(
                                "private-order",
                                symbol,
                                names[case.int(1)],
                                "매수",
                                5,
                                3,
                                2,
                                12.5,
                            )
                        ),
                        d.atTime(15, 0).atZone(SEOUL).toInstant(),
                        if (i == 2) null else DailyPnl(d, case.values[0], 1, 2),
                        if (i == 2) null else now,
                    )
                }
                .toMutableList()
        val format = if (case.int(2) == 3) HistoryExportFormat.ARCHIVE else HistoryExportFormat.CSV
        val period = HistoryPeriod.values()[minOf(case.int(2), 2)]
        val request = HistoryExport.capture(account, rows, period, format, now)
        rows.clear() // Must retain the original selection snapshot.
        val output = ByteArrayOutputStream()
        request.write(output)
        val files =
            if (format == HistoryExportFormat.CSV) mapOf("summary" to output.toString("UTF-8"))
            else
                buildMap {
                    ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                        }
                    }
                }
        assertFalse(
            files.values.any { it.contains(account.number) || it.contains("private-order") }
        )
        val summary = files["summary"] ?: files.getValue("year.csv")
        if (format == HistoryExportFormat.CSV) assertFalse(summary.contains(names[case.int(1)]))
        assertTrue(summary.startsWith("\uFEFF"))
        val parsed = csv(summary)
        val data = parsed.drop(1)
        assertTrue(data.all { it.size == parsed.first().size })
        assertEquals(case.expected.getInt("rows"), data.size)
        val total =
            data
                .map { it[2] }
                .filter { it.isNotEmpty() }
                .fold(BigDecimal.ZERO) { a, v -> a + v.toBigDecimal() }
        assertEquals(0, total.compareTo(case.expected.getString("total").toBigDecimal()))
        assertEquals(case.expected.getInt("missing"), data.count { it[2].isEmpty() })
        if (format == HistoryExportFormat.ARCHIVE) {
            assertEquals(
                setOf(
                    "day.csv",
                    "month.csv",
                    "year.csv",
                    "balances.csv",
                    "trades.csv",
                    "README.txt",
                ),
                files.keys,
            )
            val trades = csv(files.getValue("trades.csv")).drop(1)
            assertEquals(3, trades.size)
            assertTrue(
                trades.all { it[2] == "'005930" && it[3] == safeNames[case.int(1)] && it[6] == "3" }
            )
            assertEquals(3, csv(files.getValue("day.csv")).drop(1).size)
            assertEquals(2, csv(files.getValue("month.csv")).drop(1).size)
        }
    }

    /**
     * Independent small RFC-4180 reader: verifies decoded cells, including commas/quotes/newlines.
     */
    private fun csv(text: String): List<List<String>> {
        val result = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = if (text.startsWith("\uFEFF")) 1 else 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '"') {
                if (quoted && i + 1 < text.length && text[i + 1] == '"') {
                    field.append('"')
                    i++
                } else quoted = !quoted
            } else if (!quoted && ch == ',') {
                row += field.toString()
                field.setLength(0)
            } else if (!quoted && ch == '\r') {
                check(i + 1 < text.length && text[i + 1] == '\n')
                i++
                row += field.toString()
                field.setLength(0)
                result += row
                row = mutableListOf()
            } else field.append(ch)
            i++
        }
        check(!quoted && field.isEmpty() && row.isEmpty())
        return result
    }

    private fun schedule() {
        val definitions =
            listOf(
                PurchaseSchedule(true, ScheduleFrequency.WEEKDAYS),
                PurchaseSchedule(true, ScheduleFrequency.WEEKLY, 1),
                PurchaseSchedule(true, ScheduleFrequency.MONTHLY, 31),
                PurchaseSchedule(true, ScheduleFrequency.MONTHLY, 19),
                PurchaseSchedule(false),
            )
        val date =
            listOf("2026-03-02", "2026-09-21", "2026-09-22", "2026-09-19", "2026-10-01")[
                case.int(1)]
        val time = listOf("09:59:59", "10:00:00", "15:14:59", "15:15:00")[case.int(2)]
        val instant = LocalDateTime.parse("${date}T$time").atZone(SEOUL).toInstant()
        val selected = definitions[case.int(0)]
        selected.validate()
        val expected =
            if (case.expected.isNull("occurrence")) null else case.expected.getString("occurrence")
        assertEquals(expected, selected.occurrence(instant))
        assertNull(selected.copy(enabled = false).occurrence(instant))
    }

    private fun algorithm() {
        val id = if (case.int(0) == 0) "averaging" else "rebalance"
        val variant = case.int(3)
        val group =
            StrategyGroup(
                "g1",
                id,
                "fixture",
                symbols = listOf(GroupSymbol(symbol, 50)),
                dropPercent = if (variant == 0) 3.0 else 10.0,
                takeProfitPercent = if (variant == 0) 6.0 else 10.0,
                rebalanceBand = if (variant == 0) 5.0 else 10.0,
            )
        val position =
            GroupPosition(
                symbol,
                case.values[1],
                case.values[1] * 10000,
                0,
                if (variant == 0) 2 else 4,
            )
        val context =
            GroupContext(group, listOf(position), mapOf(symbol to quote()), case.values[2])
        val algorithm: GroupAlgorithm =
            if (case.int(0) == 0) AveragingDownAlgorithm() else RebalancingAlgorithm()
        val decisions = algorithm.decide(context)
        assertEquals(case.expected.length(), decisions.size)
        decisions.forEach { d ->
            assertTrue(case.expected.has(d.side.name))
            val e = case.expected.getJSONArray(d.side.name)
            assertEquals(e.getLong(0), d.quantity)
            assertEquals(if (e.isNull(1)) null else e.getLong(1), d.targetPrice)
            assertEquals(symbol, d.symbol)
            assertTrue(d.quantity > 0)
        }
        // Changing the group identity never borrows another group's positions or changes this
        // decision.
        assertEquals(decisions, algorithm.decide(context.copy(group = group.copy(id = "g2"))))
    }
}
