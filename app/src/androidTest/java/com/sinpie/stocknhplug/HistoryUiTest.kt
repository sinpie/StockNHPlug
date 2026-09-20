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
import java.io.File
import java.time.*
import org.junit.*

class HistoryUiTest {
    @get:Rule val compose = createComposeRule()
    private val a = Account("fixture-1111", Environment.MOCK, "test")
    private val b = Account("fixture-2222", Environment.MOCK, "test")

    private fun day(date: String, amount: Long): AccountDay {
        val d = LocalDate.parse(date)
        val at = d.atTime(15, 0).atZone(SEOUL).toInstant()
        return AccountDay(
            d,
            Portfolio(3000000, 12000000, 150000, emptyList(), at),
            listOf(Execution("1", "005930", "검증종목", "매수", 10, 5, 5, 70000.0)),
            at,
            DailyPnl(d, amount, 100, 200),
            at,
        )
    }

    @Test
    fun periodTabsShowPersistedStatisticsAndDailyDetails() {
        val state =
            AppState(
                selected = a,
                history =
                    listOf(
                        day("2025-12-31", 10000),
                        day("2026-01-02", -2000),
                        day("2026-02-03", 5000),
                    ),
            )
        compose.setContent {
            StockTheme {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("UI 검증용 · 합성 통계")
                    HistoryScreen(state, {}, {})
                }
            }
        }
        compose.onNodeWithText("13,000원").assertIsDisplayed()
        compose.onNodeWithText("월별").performClick()
        compose.onNodeWithText("2026-02").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("연도별").performScrollTo().performClick()
        compose.onNodeWithText("3,000원").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("일별 기록")[0].performScrollTo().performClick()
        compose.onNodeWithText("2026-02-03").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("일별").performScrollTo().performClick()
        compose.onNodeWithText("UI 검증용 · 합성 통계").performScrollTo()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "history-statistics.png").outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun accountSelectionSeparatesScreenWhileOtherAccountRemainsRunning() {
        var selected by mutableStateOf(a)
        compose.setContent {
            StockTheme {
                Column {
                    AccountSelector(
                        AppState(
                            selected = selected,
                            accountRuns =
                                listOf(
                                    AccountRun(a, true, true, true, false, ""),
                                    AccountRun(b, false, false, true, false, ""),
                                ),
                        )
                    ) {
                        selected = it
                    }
                    Text(if (selected == a) "A 전략과 이력" else "B 전략과 이력")
                }
            }
        }
        compose.onNodeWithText("2 · •••• 2222").performClick()
        compose.onNodeWithText("B 전략과 이력").assertIsDisplayed()
        compose.onNodeWithText("1 · •••• 1111 · 운용").assertIsDisplayed()
    }
}
