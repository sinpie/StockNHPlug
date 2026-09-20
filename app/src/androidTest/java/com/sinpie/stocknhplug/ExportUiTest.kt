package com.sinpie.stocknhplug

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.sinpie.stocknhplug.application.*
import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.ui.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.*
import org.junit.*
import org.junit.Assert.*

class ExportUiTest {
    @get:Rule val compose = createComposeRule()
    private val account = Account("fixture-1111", Environment.MOCK, "test")

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun downloadUsesSelectedYearPeriodAndDisablesWhilePending() {
        var pending by mutableStateOf(false)
        var captured: HistoryExport? = null
        val rows =
            listOf("2025-12-31", "2026-01-02").map {
                val date = LocalDate.parse(it)
                AccountDay(date, pnl = DailyPnl(date, 1000, 0, 0))
            }
        compose.setContent {
            StockTheme {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("검증용 합성 자료 · 다운로드")
                    HistoryScreen(
                        AppState(selected = account, history = rows),
                        {},
                        {},
                        {
                            captured = it
                            pending = true
                        },
                        pending,
                    )
                }
            }
        }
        compose.onNodeWithText("월별").performClick()
        compose.onNodeWithText("2026").performClick()
        compose.onNodeWithText("통계 CSV").performScrollTo().performClick()
        compose.onNodeWithText("전체 ZIP").assertIsNotEnabled()
        compose.runOnIdle {
            val out = ByteArrayOutputStream()
            captured!!.write(out)
            assertTrue(out.toString("UTF-8").contains("2026-01"))
            assertFalse(out.toString("UTF-8").contains("2025-12"))
            pending = false
        }
        compose.onNodeWithText("통계 CSV").assertIsEnabled()
        compose.onNodeWithText("검증용 합성 자료 · 다운로드").performScrollTo()
        screenshot("history-export.png")
    }

    @Test
    fun operationDetailsExplainBlockersWithoutIssuingCommands() {
        val at = Instant.parse("2026-09-21T01:00:00Z")
        compose.setContent {
            StockTheme {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("검증용 합성 자료 · 운용 점검")
                    OperationPanel(AppState(selected = account, storageError = true), at)
                }
            }
        }
        compose.onNodeWithText("점검 자세히").performClick()
        compose.onNodeWithText("보안 저장소 · 차단").assertIsDisplayed()
        compose.onNodeWithText("그룹 체결 대사 · 차단").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("오늘 기록 · 확인 필요").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("검증용 합성 자료 · 운용 점검").performScrollTo()
        screenshot("operation-review.png")
    }
}
