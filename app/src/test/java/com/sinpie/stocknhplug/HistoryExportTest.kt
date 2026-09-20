package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.application.*
import com.sinpie.stocknhplug.domain.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.*
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test

class HistoryExportTest {
    @Test
    fun archiveStreamsBlocksAndLeavesDestinationCloseToOwner() {
        var singles = 0
        var blocks = 0
        var closed = false
        val output =
            object : OutputStream() {
                override fun write(b: Int) {
                    singles++
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    blocks++
                }

                override fun close() {
                    closed = true
                }
            }
        request(listOf(day())).write(output)
        assertTrue(blocks > 0)
        assertFalse(closed)
        // ZIP 구조 헤더의 단일 바이트 쓰기는 허용하되 압축 본문을 한 바이트씩 보내지 않는다.
        assertTrue(singles < 1000)
    }

    private val account = Account("sensitive-12345678", Environment.MOCK, "nhplug")
    private val at = Instant.parse("2026-09-20T05:00:00Z")
    private val date = at.atZone(SEOUL).toLocalDate()

    private fun day(amount: Long = -500) =
        AccountDay(
            date,
            Portfolio(1000, 10000, -50, emptyList(), at),
            listOf(
                Execution(
                    "private-order",
                    "005930",
                    "=HYPERLINK(\"bad\")\r\n종목,명",
                    "매수",
                    10,
                    3,
                    7,
                    12.5,
                )
            ),
            at,
            DailyPnl(date, amount, 2, 3),
            at,
        )

    private fun request(
        rows: List<AccountDay>,
        format: HistoryExportFormat = HistoryExportFormat.ARCHIVE,
    ) = HistoryExport.capture(account, rows, HistoryPeriod.MONTH, format, at)

    private fun archive(request: HistoryExport): Map<String, String> {
        val output = ByteArrayOutputStream()
        request.write(output)
        return buildMap {
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                }
            }
        }
    }

    @Test
    fun csvPreservesNegativeNumbersUnicodeBomAndUnknownVersusZero() {
        val missing = AccountDay(date.minusMonths(1))
        val out = ByteArrayOutputStream()
        request(listOf(day(), missing), HistoryExportFormat.CSV).write(out)
        val csv = out.toString("UTF-8")
        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("\"-500\""))
        assertTrue(csv.contains("\"2026-08\",\"\",\"\",\"\",\"0\""))
        assertTrue(csv.contains("\"37.5\""))
        assertTrue(csv.contains("•••• 5678"))
        assertFalse(csv.contains(account.number))
        assertTrue(csv.endsWith("\r\n"))
    }

    @Test
    fun zipContainsAllPeriodsBalancesAndSafeTradeTextWithoutOrderNumbers() {
        val files = archive(request(listOf(day())))
        assertEquals(
            setOf("day.csv", "month.csv", "year.csv", "balances.csv", "trades.csv", "README.txt"),
            files.keys,
        )
        val trades = files.getValue("trades.csv")
        assertTrue(trades.contains("\"'005930\""))
        assertTrue(trades.contains("\"'=HYPERLINK(\"\"bad\"\")\r\n종목,명\""))
        assertFalse(
            files.values.any { it.contains("private-order") || it.contains(account.number) }
        )
        assertTrue(files.getValue("README.txt").contains("암호화되지 않습니다"))
    }

    @Test
    fun snapshotDoesNotFollowLaterMutableListsOrAccountSelections() {
        val trades = day().executions.toMutableList()
        val rows = mutableListOf(day().copy(executions = trades))
        val captured = request(rows)
        trades.clear()
        rows.clear()
        val files = archive(captured)
        assertTrue(files.getValue("trades.csv").contains("005930"))
        assertTrue(files.getValue("day.csv").contains("-500"))
    }

    @Test
    fun missingTradesRemainUnobservedAndDoNotInventFills() {
        val files = archive(request(listOf(AccountDay(date))))
        assertEquals(1, files.getValue("trades.csv").trim().lines().size)
        assertTrue(files.getValue("balances.csv").contains("\"$date\",\"\",\"\",\"\""))
    }

    @Test
    fun writeAndCancellationFailuresAreNotSwallowed() {
        val bad =
            object : OutputStream() {
                override fun write(b: Int) {
                    throw IOException("full")
                }
            }
        assertTrue(
            runCatching { request(listOf(day())).write(bad) }.exceptionOrNull() is IOException
        )
        val stopped = runCatching {
            request(listOf(day())).write(ByteArrayOutputStream()) {
                throw java.util.concurrent.CancellationException()
            }
        }
        assertTrue(stopped.exceptionOrNull() is java.util.concurrent.CancellationException)
    }

    @Test
    fun repeatedCumulativeFillsDoNotDoubleAndLongMoneyDoesNotOverflow() {
        val files =
            archive(
                request(
                    listOf(
                        day(Long.MAX_VALUE),
                        day(Long.MAX_VALUE)
                            .copy(
                                date = date.minusDays(1),
                                pnl = DailyPnl(date.minusDays(1), Long.MAX_VALUE, 0, 0),
                            ),
                    )
                )
            )
        assertTrue(files.getValue("month.csv").contains("18446744073709551614"))
        assertTrue(files.getValue("month.csv").contains("\"75.0\""))
    }

    @Test
    fun emptyAndDuplicateDatesAreRejected() {
        assertTrue(runCatching { request(emptyList()) }.isFailure)
        assertTrue(runCatching { request(listOf(day(), day())) }.isFailure)
    }
}
