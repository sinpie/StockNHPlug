package com.sinpie.stocknhplug

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
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
import kotlinx.coroutines.launch
import org.junit.*

/** 화면 전용 설정 fixture. 계좌/주문/체결 데이터는 만들지 않고 실제 인증 화면을 우회하지 않는다. */
class StrategyGroupsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun parkingEditorRejectsBadInputAndSavesExplicitSettings() {
        var state by mutableStateOf(AppState())
        compose.setContent {
            StockTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ParkingSettingsCard(state) { state = state.copy(book = it) }
                }
            }
        }
        compose.onNodeWithText("파킹 설정").performClick()
        compose.onNodeWithText("파킹 종목코드 (6자리)").performTextReplacement("005940")
        compose.onNodeWithText("남겨둘 최소 현금 (원)").performTextReplacement("잘못된 값")
        compose.onNodeWithText("파킹 저장").assertIsNotEnabled()
        compose.onNodeWithText("남겨둘 최소 현금 (원)").performTextReplacement("30000")
        compose.onNodeWithText("파킹 저장").performClick()
        compose.runOnIdle {
            Assert.assertEquals("005940", state.book.parking.symbol)
            Assert.assertEquals(30000L, state.book.parking.reserveCash)
            Assert.assertFalse(state.book.parking.enabled)
        }
    }

    @Test
    fun strategyNavigationPresetsAndPreview() {
        var state by
            mutableStateOf(
                AppState(
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
                                    name = "균형 포트폴리오",
                                    symbols =
                                        listOf(GroupSymbol("005930", 50), GroupSymbol("000660", 50)),
                                ),
                            ),
                        ),
                    groupAlgorithms = mapOf("averaging" to "물타기", "rebalance" to "리밸런싱"),
                )
            )
        compose.setContent {
            StockTheme {
                val scroll = rememberScrollState()
                val scope = rememberCoroutineScope()
                Column(
                    Modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    StrategyGroupsScreen(
                        state,
                        { state = state.copy(book = it) },
                        onNavigate = { scope.launch { scroll.scrollTo(0) } },
                        riskSettings = {},
                    )
                }
            }
        }
        compose.onNodeWithText("전략과 그룹").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "strategy-groups.png").outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("물타기 관리").performScrollTo().performClick()
        compose.onNodeWithText("장기 적립").assertIsDisplayed()
        compose.onNodeWithText("추천매수").performScrollTo().performClick()
        compose.onNodeWithText("지표 조합 5가지").assertIsDisplayed()
        compose.onNodeWithText("재무·추세").performScrollTo().performClick()
        compose.onNodeWithText("전략 설정 저장").performScrollTo().performClick()
        compose.runOnIdle {
            Assert.assertTrue(state.book.plans.first().recommendation.profitability)
            Assert.assertFalse(state.book.plans.first().recommendation.enabled)
        }
        // 탭은 가로 스크롤 안에 있다. 세로 부모를 먼저 올린 후 탭을 선택한다.
        compose.onNodeWithText("‹ 전체 전략").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("정기매수").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("정기매수 사용").performScrollTo().assertIsDisplayed()
    }
}
