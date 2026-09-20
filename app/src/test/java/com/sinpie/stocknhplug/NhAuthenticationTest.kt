package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.infrastructure.nh.NhAuthentication
import com.sinpie.stocknhplug.infrastructure.nh.NhEndpoints
import com.sinpie.stocknhplug.infrastructure.nh.NhHistoryWindow
import com.sinpie.stocknhplug.domain.Environment
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class NhAuthenticationTest {
    @Test
    fun completeHistoryWindowDoesNotAcceptPartialAccountOrShortHistoryPages() {
        assertTrue(NhHistoryWindow.complete("/krstock/quote/v1/period", 250, 250))
        assertFalse(NhHistoryWindow.complete("/krstock/quote/v1/period", 250, 249))
        assertFalse(NhHistoryWindow.complete("/krstock/quote/v1/period", null, 250))
        assertFalse(NhHistoryWindow.complete("/krstock/quote/v1/period", 0, 250))
        assertFalse(NhHistoryWindow.complete("/krstock/inquiry/v1/balance", 250, 250))
        assertFalse(NhHistoryWindow.complete("/krstock/inquiry/v1/dailyOrderExecution", 250, 250))
    }
    @Test
    fun liveOnlyHistoryDoesNotRerouteMockOrdersOrAccountData() {
        for (path in listOf("/krstock/quote/v1/period", "/krstock/quote/v1/currentPrice")) {
            assertEquals("https://api.nhplug.com:8443", NhEndpoints.base(Environment.MOCK, path))
        }
        for (path in listOf("/krstock/order/v1/cashBuy", "/krstock/order/v1/cashSell",
            "/n2/acctinfo", "/krstock/inquiry/v1/balance", "/krstock/quote/v1/unknown")) {
            assertEquals("https://moapi.nhplug.com:8443", NhEndpoints.base(Environment.MOCK, path))
        }
    }
    @Test
    fun tokenWireRequestDoesNotAddCharsetOrBody() {
        val request = NhAuthentication.request("TEST+KEY", "TEST&SECRET")
        assertEquals("api.nhplug.com", request.url.host)
        assertEquals("TEST+KEY", request.url.queryParameter("appkey"))
        assertEquals("TEST&SECRET", request.url.queryParameter("appsecretkey"))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val client = OkHttpClient()
            try {
                client.newCall(request.newBuilder().url(server.url("/oauth2/token")).build())
                    .execute().use { assertEquals(200, it.code) }
                val sent = server.takeRequest()
                assertEquals("POST", sent.method)
                assertEquals("application/x-www-form-urlencoded", sent.getHeader("Content-Type"))
                assertEquals(0L, sent.bodySize)
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }
}
