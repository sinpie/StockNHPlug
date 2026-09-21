package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.infrastructure.nh.NhRequestPacer
import org.junit.Assert.*
import org.junit.Test

class NhRequestPacerTest {
    private fun ms(value: Long) = value * 1_000_000

    @Test
    fun normalRequestsRespectSpacingWithoutWallClock() {
        val pacer = NhRequestPacer()
        assertEquals(0L, pacer.delayMillis(ms(0)))
        pacer.sent(ms(100))
        assertEquals(250L, pacer.delayMillis(ms(100)))
        assertEquals(1L, pacer.delayMillis(ms(349)))
        assertEquals(0L, pacer.delayMillis(ms(350)))
    }

    @Test
    fun throttlingAddsQuietPeriodThenSlowerSubsequentCalls() {
        val pacer = NhRequestPacer()
        pacer.sent(ms(0))
        pacer.rateLimited(ms(100))
        assertEquals(2000L, pacer.delayMillis(ms(100)))
        assertEquals(1L, pacer.delayMillis(ms(2099)))
        assertEquals(0L, pacer.delayMillis(ms(2100)))
        pacer.sent(ms(2100))
        assertEquals(750L, pacer.delayMillis(ms(2350)))
        assertEquals(0L, pacer.delayMillis(ms(3100)))
    }

    @Test
    fun repeatedLimitExtendsCooldownAndRecoveryRestoresNormalPacing() {
        val pacer = NhRequestPacer()
        pacer.rateLimited(ms(100))
        pacer.rateLimited(ms(2000))
        assertEquals(2000L, pacer.delayMillis(ms(2000)))
        pacer.sent(ms(31500))
        assertEquals(501L, pacer.delayMillis(ms(31999)))
        assertEquals(0L, pacer.delayMillis(ms(32000)))
        pacer.sent(ms(32000))
        assertEquals(250L, pacer.delayMillis(ms(32000)))
    }

    @Test
    fun negativeMonotonicOriginIsValid() {
        val pacer = NhRequestPacer()
        pacer.sent(ms(-1000))
        pacer.rateLimited(ms(-900))
        assertEquals(2000L, pacer.delayMillis(ms(-900)))
        assertEquals(0L, pacer.delayMillis(ms(1100)))
    }
}
