package com.sinpie.stocknhplug

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.sinpie.stocknhplug.application.AppState
import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.ui.*
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 격리 UI 호스트의 명시적인 합성 시세/저널 fixture. 실제 계좌나 주문 연결 없음. */
class WorkspaceInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun groupedTargetsSearchAndStaleQuotePresentation() {
        val at = Instant.now().minusSeconds(60)
        val state =
            AppState(
                connected = true,
                book =
                    StrategyBook(
                        StrategyBook.defaults().plans,
                        listOf(
                            StrategyGroup(
                                id = "g1",
                                strategyId = "averaging",
                                name = "장기 적립",
                                symbols = listOf(GroupSymbol("005930")),
                            ),
                            StrategyGroup(
                                id = "g2",
                                strategyId = "rebalance",
                                name = "균형 관리",
                                symbols = listOf(GroupSymbol("005930")),
                            ),
                        ),
                    ),
                quotes = mapOf("005930" to Quote("005930", 9800, 9790, 9800, at, at, true)),
                tracking =
                    listOf(
                        TargetStatus(
                            "g1|005930|BUY",
                            "005930",
                            Side.BUY,
                            10000,
                            9000,
                            9500.0,
                            true,
                            "주문 조건 충족",
                        ),
                        TargetStatus(
                            "g2|005930|SELL",
                            "005930",
                            Side.SELL,
                            11000,
                            12000,
                            11500.0,
                            false,
                            "반전 추적 중",
                        ),
                    ),
            )
        var selected = ""
        compose.setContent {
            StockTheme {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("UI 테스트 · 합성 시세")
                    MarketWorkspace(state, { _, _, _ -> }, { selected = it })
                }
            }
        }
        compose.onNodeWithText("지연").assertIsDisplayed()
        compose.onNodeWithText("주문 조건 충족").assertDoesNotExist()
        compose.onNodeWithText("장기 적립 · 매수").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("균형 관리 · 매도").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("UI 테스트 · 합성 시세").performScrollTo()
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "market-workspace.png").outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("005930 조회").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("005930", selected) }
        compose.onNodeWithText("종목코드·종목명·그룹 검색").performScrollTo().performTextReplacement("없는 그룹")
        compose.onNodeWithText("검색 결과가 없습니다.").assertIsDisplayed()
        compose.onNodeWithText("종목코드·종목명·그룹 검색").performTextReplacement("균형")
        compose.onNodeWithText("005930 조회").assertExists()
    }

    @Test
    fun activityDefaultsToSelectedAccountAndCanShowAllWithoutMixingFills() {
        val at = Instant.now()
        val account = Account("test-one", Environment.MOCK, "test")
        fun record(id: String, symbol: String, number: String) =
            OrderRecord(
                OrderIntent(
                    id,
                    Environment.MOCK,
                    number,
                    symbol,
                    Side.BUY,
                    1,
                    10000,
                    "UI 테스트",
                    at,
                    "test",
                ),
                OrderStatus.ACCEPTED,
                id,
            )
        val state =
            AppState(
                selected = account,
                orders =
                    listOf(record("a", "005930", account.number), record("b", "000660", "test-two")),
            )
        compose.setContent {
            StockTheme {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
                ) {
                    ActivityWorkspace(state)
                }
            }
        }
        compose.onNodeWithText("005930 · 매수").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("000660 · 매수").assertDoesNotExist()
        compose.onNodeWithText("전체 계좌").performScrollTo().performClick()
        compose.onNodeWithText("000660 · 매수").assertExists()
        compose.onNodeWithText("체결").performScrollTo().performClick()
        compose.onNodeWithText("조회된 체결 내역이 없습니다.").assertIsDisplayed()
    }
}
