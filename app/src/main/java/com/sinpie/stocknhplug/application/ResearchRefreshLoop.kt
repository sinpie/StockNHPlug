package com.sinpie.stocknhplug.application

import kotlinx.coroutines.*

/** Session-owned worker; one batch at a time, no quote-loop blocking, no approval on failures. */
class ResearchRefreshLoop(
    private val marketOpen: () -> Boolean,
    private val refresh: suspend () -> Unit,
    private val failed: () -> Unit,
) {
    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            if (!marketOpen()) {
                delay(60_000)
                continue
            }
            try {
                refresh()
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                currentCoroutineContext().ensureActive()
                failed()
            }
            delay(900_000)
        }
    }
}
