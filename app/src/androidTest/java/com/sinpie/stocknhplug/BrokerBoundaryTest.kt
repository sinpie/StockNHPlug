package com.sinpie.stocknhplug

import androidx.test.platform.app.InstrumentationRegistry
import com.sinpie.stocknhplug.data.SecureVault
import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.execution.NhBroker
import com.sinpie.stocknhplug.infrastructure.nh.NhTransport
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** No keys or API calls: invalid requests must fail before transport authentication. */
class BrokerBoundaryTest {
    @Test
    fun everyAccountInquiryRejectsForeignEnvironmentOrBrokerBeforeIo(): Unit = runBlocking {
        val transport =
            NhTransport(
                SecureVault(InstrumentationRegistry.getInstrumentation().targetContext),
                Environment.MOCK,
            )
        val broker = NhBroker(transport)
        try {
            for (account in
                listOf(
                    Account("12345678", Environment.LIVE, "nhplug"),
                    Account("12345678", Environment.MOCK, "another"),
                )) {
                val actions: List<suspend () -> Any> =
                    listOf(
                        { broker.portfolio(account) },
                        { broker.executions(account, LocalDate.of(2026, 9, 21)) },
                        { broker.dailyPnl(account) },
                        { broker.available(account, "005930", Side.BUY, 10000) },
                        { broker.available(account, "005930", Side.SELL, 10000) },
                    )
                for (action in actions) {
                    assertTrue(
                        runCatching { action() }.exceptionOrNull() is IllegalArgumentException
                    )
                }
            }
        } finally {
            transport.client.connectionPool.evictAll()
            transport.client.dispatcher.executorService.shutdown()
        }
    }
}
