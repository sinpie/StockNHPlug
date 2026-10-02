package com.sinpie.stocknhplug

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MockOrderTerminalTest {
    private fun row(cancelled: Int) =
        JSONObject()
            .put("iem_cd", "005930")
            .put("orr_qty", 1)
            .put("tot_cns_qty", 0)
            .put("ny_cns_qty", 0)
            .put("can_qty", cancelled)

    @Test
    fun originalAndCancellationRowsAreAccountTerminal() {
        assertTrue(MockOrderProbeTest.accountTerminal(listOf(row(0), row(1)), "005930"))
        assertTrue(MockOrderProbeTest.accountTerminal(listOf(row(1)), "005930"))
    }

    @Test
    fun pendingFilledForeignOrAmbiguousActivityCannotBeCleared() {
        for (rows in
            listOf(
                emptyList(),
                listOf(row(0)),
                listOf(row(1), row(1)),
                listOf(row(0), row(0), row(1)),
                listOf(row(1).put("tot_cns_qty", 1)),
                listOf(row(1).put("ny_cns_qty", 1)),
                listOf(row(1).put("iem_cd", "000660")),
                listOf(row(1).put("can_qty", 1.5)),
            )) {
            assertFalse(MockOrderProbeTest.accountTerminal(rows, "005930"))
        }
    }
}
