package com.sinpie.stocknhplug

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.sinpie.stocknhplug.application.*
import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.platform.CreateHistoryDocument
import com.sinpie.stocknhplug.platform.HistoryExportModel
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.*
import org.junit.Assert.*
import org.junit.Test

/** 파일 제공자 경계에 메모리를 주입한다. 실제 계좌·키·사용자 파일을 사용하지 않는다. */
class HistoryExportAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app
        get() = instrumentation.targetContext.applicationContext as Application

    private fun request() =
        HistoryExport.capture(
            Account("fixture-1234", Environment.MOCK, "test"),
            listOf(
                AccountDay(
                    LocalDate.parse("2026-09-20"),
                    pnl = DailyPnl(LocalDate.parse("2026-09-20"), -10, 0, 0),
                )
            ),
            HistoryPeriod.DAY,
            HistoryExportFormat.CSV,
        )

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(model: HistoryExportModel) {
        val end = System.nanoTime() + 5_000_000_000L
        while (model.state.value.busy && System.nanoTime() < end) Thread.sleep(10)
        assertFalse(model.state.value.busy)
    }

    @Test
    fun cancellationAndMissingRequestNeverOpenDestination() {
        var opens = 0
        val model =
            HistoryExportModel(app) {
                opens++
                ByteArrayOutputStream()
            }
        main {
            assertTrue(model.prepare(request()))
            assertFalse(model.prepare(request()))
            model.save(null)
            assertTrue(model.state.value.message.contains("취소"))
            model.save(Uri.parse("content://fixture/export"))
            assertTrue(model.state.value.message.contains("만료"))
        }
        assertEquals(0, opens)
    }

    @Test
    fun outputIsWrittenOffMainAndCloseCompletesBeforeSuccess() {
        var closed = false
        val output =
            object : ByteArrayOutputStream() {
                override fun close() {
                    closed = true
                    super.close()
                }
            }
        val model =
            HistoryExportModel(app) {
                assertNotEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                output
            }
        main {
            model.prepare(request())
            model.save(Uri.parse("content://fixture/export"))
        }
        await(model)
        assertTrue(closed)
        assertTrue(model.state.value.message.contains("저장했습니다"))
        assertTrue(output.toString("UTF-8").contains("\"-10\""))
    }

    @Test
    fun closeFailureAndInvalidSchemeCannotReportSuccess() {
        val output =
            object : ByteArrayOutputStream() {
                override fun close() {
                    throw IOException("private-uri-details")
                }
            }
        val model = HistoryExportModel(app) { output }
        main {
            model.prepare(request())
            model.save(Uri.parse("file:///forbidden"))
        }
        assertEquals(0, output.size())
        main {
            model.prepare(request())
            model.save(Uri.parse("content://fixture/export"))
        }
        await(model)
        assertTrue(model.state.value.message.contains("저장하지 못했습니다"))
        assertFalse(model.state.value.message.contains("private-uri"))
    }

    @Test
    fun documentContractUsesSystemCreateDocumentAndExpectedMime() {
        HistoryExportFormat.values().forEach { format ->
            val intent =
                CreateHistoryDocument(format.mime).createIntent(app, "fixture.${format.extension}")
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
            assertTrue(intent.categories.contains(Intent.CATEGORY_OPENABLE))
            assertEquals(format.mime, intent.type)
            assertEquals("fixture.${format.extension}", intent.getStringExtra(Intent.EXTRA_TITLE))
            assertNotNull(intent.resolveActivity(app.packageManager))
        }
    }
}
