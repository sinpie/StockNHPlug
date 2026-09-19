package com.sinpie.stocknhplug

import com.sinpie.stocknhplug.execution.*
import com.sinpie.stocknhplug.infrastructure.nh.ResponseGuard
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test
    fun businessFailureWithHttp200FailsClosed() {
        val j = JSONObject("""{"rsp_cd":"99999","rsp_msg":"주문 실패","Output_0":{"mkt_orr_no":1}}""")
        assertTrue(runCatching { ResponseGuard.validate(j) }.isFailure)
    }

    @Test
    fun emptySuccessIsNotTheSameAsMissingStatus() {
        ResponseGuard.validate(JSONObject("""{"rsp_msg":"조회 내역이 없습니다.","Output_1":[]}"""))
        assertTrue(
            runCatching { ResponseGuard.validate(JSONObject("""{"Output_1":[]}""")) }.isFailure
        )
    }

    @Test
    fun successfulCodesAreNotHardcoded() {
        ResponseGuard.validate(
            JSONObject("""{"rsp_cd":"12345","rsp_msg":"정상 완료","Output_0":{"iem_cd":"005930"}}""")
        )
    }

    @Test
    fun documentedWebsocketPayloadParses() {
        val j =
            JSONObject(
                """{"code":"005940","time":"13:58:26","price":"31700","offer":"31750","bid":"31700","janggubun":"0"}"""
            )
        val now = Instant.parse("2026-09-21T04:58:28Z")
        val q = NhSocket.parseQuote(j, now)!!
        assertEquals(31700L, q.price)
        assertTrue(q.fresh(now))
        assertTrue(q.regular)
        assertNull(NhSocket.parseQuote(j, now.plusSeconds(30)))
    }
}
