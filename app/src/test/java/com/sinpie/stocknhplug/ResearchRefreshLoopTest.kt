package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.application.ResearchRefreshLoop
import com.sinpie.stocknhplug.domain.ResearchConfiguration
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ResearchRefreshLoopTest {
    @Test
    fun marketClosureDefersBatchesAndSessionCancellationStopsWorker() = runTest {
        var open = false
        var calls = 0
        val job = launch {
            ResearchRefreshLoop({ open }, { calls++ }, { fail("no failure expected") }).run()
        }
        runCurrent()
        assertEquals(0, calls)
        open = true
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(899_999)
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, calls)
        job.cancelAndJoin()
        advanceTimeBy(900_000)
        assertEquals(2, calls)
    }

    @Test
    fun timeoutInvalidatesEvidenceButNextScheduledBatchStillRuns() = runTest {
        var calls = 0
        var failures = 0
        val job = launch {
            ResearchRefreshLoop(
                    { true },
                    {
                        calls++
                        if (calls == 1) withTimeout(100) { delay(200) }
                    },
                    { failures++ },
                )
                .run()
        }
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, failures)
        advanceTimeBy(900_000)
        runCurrent()
        assertEquals(2, calls)
        job.cancelAndJoin()
    }

    @Test
    fun slowBatchCannotOverlapAndCancellationIsNotReportedAsFailure() = runTest {
        var calls = 0
        var failures = 0
        val job = launch {
            ResearchRefreshLoop(
                    { true },
                    {
                        calls++
                        delay(2_000_000)
                    },
                    { failures++ },
                )
                .run()
        }
        runCurrent()
        advanceTimeBy(1_000_000)
        assertEquals(1, calls)
        job.cancelAndJoin()
        assertEquals(0, failures)
    }

    @Test
    fun configurationKeepsLeadingZerosRejectsDuplicatesAndRollsYear() {
        val config = ResearchConfiguration("005930:00126380")
        config.validate()
        assertEquals("00126380", config.mapping()["005930"])
        assertEquals(2025, config.resolvedYear(LocalDate.of(2026, 12, 31)))
        assertEquals(2026, config.resolvedYear(LocalDate.of(2027, 1, 1)))
        assertTrue(
            runCatching { ResearchConfiguration("005930:00126380,005930:00126380").validate() }
                .isFailure
        )
        assertTrue(runCatching { config.copy(year = 2000).validate() }.isFailure)
    }
}
