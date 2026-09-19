package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.research.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 제공자를 가짜 포트로 교체해 네트워크/증권 주문 구현 없이 연구 조합 계약을 검증한다. */
class ResearchRepositoryTest {
    private val now = Instant.parse("2026-09-21T01:00:00Z")

    @Test
    fun swappedPriceProviderPreservesSourceAndDoesNotInventCorporateEvidence() = runBlocking {
        val history =
            PriceHistory("005930", emptyList(), true, DataSource.LICENSED_ADJUSTED_PRICES, now)
        val repository =
            ResearchRepository(PriceHistoryProvider { history }, null, UnlicensedNewsProvider()) {
                now
            }
        val result = repository.inspect("005930", null, 2025, "11011")
        assertEquals(history, result.prices)
        assertFalse(result.disclosuresChecked)
        assertFalse(result.newsLicensed)
        assertFalse(result.buyBlockers(now).isEmpty())
    }

    @Test
    fun providerCannotReturnAnotherSymbolsEvidence() = runBlocking {
        val repository =
            ResearchRepository(
                PriceHistoryProvider {
                    PriceHistory("000660", emptyList(), false, DataSource.NHPLUG, now)
                },
                null,
                UnlicensedNewsProvider(),
            ) {
                now
            }
        assertTrue(runCatching { repository.inspect("005930", null, 2025, "11011") }.isFailure)
    }

    @Test
    fun providerFailureIsNotReplacedBySampleHistory() = runBlocking {
        val repository =
            ResearchRepository(
                PriceHistoryProvider { error("provider unavailable") },
                null,
                UnlicensedNewsProvider(),
            ) {
                now
            }
        assertTrue(runCatching { repository.inspect("005930", null, 2025, "11011") }.isFailure)
    }
}
