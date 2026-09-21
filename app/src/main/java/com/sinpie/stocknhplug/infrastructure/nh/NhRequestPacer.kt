package com.sinpie.stocknhplug.infrastructure.nh

/**
 * Shared transport's monotonic request spacing. A 429 fails its original operation; only later
 * calls wait for a quiet period and use reduced throughput for 30 seconds. Caller must hold the
 * transport mutex. This class neither retries nor refreshes tokens.
 */
internal class NhRequestPacer {
    private var sentAt: Long? = null
    private var limitedAt: Long? = null

    fun delayMillis(nowNanos: Long): Long {
        val sinceLimited = limitedAt?.let { (nowNanos - it) / 1_000_000 }
        val spacing = if (sinceLimited != null && sinceLimited < 30_000) 1000L else 250L
        val paced = sentAt?.let { spacing - (nowNanos - it) / 1_000_000 } ?: 0L
        val quiet = sinceLimited?.let { 2000L - it } ?: 0L
        return maxOf(0L, paced, quiet)
    }

    fun sent(nowNanos: Long) {
        sentAt = nowNanos
    }

    fun rateLimited(nowNanos: Long) {
        limitedAt = nowNanos
    }
}
