package com.sinpie.stocknhplug

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
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
import org.junit.*

/** 합성 데이터는 테스트 소스에서만 사용한다. 앱 실행 데이터로 연결하지 않는다. */
class FinancialUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun allocationReturnSortAndPreviewUseExplicitSyntheticHoldings() {
        val state =
            AppState(
                selected = Account("test0001", Environment.MOCK, "test"),
                connected = true,
                portfolio =
                    Portfolio(
                        2560000,
                        8500000,
                        210000,
                        listOf(
                            Holding("005930", "삼성전자", 50, 68000, 72000, 200000),
                            Holding("035420", "NAVER", 12, 185000, 195000, 120000),
                        ),
                        Instant.now(),
                    ),
            )
        compose.setContent {
            StockTheme {
                Scaffold(bottomBar = { WorkspaceNavigation(3) {} }) { padding ->
                    Column(
                        Modifier.padding(padding)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("UI 검증용 · 합성 계좌")
                        AssetsWorkspace(state, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("포트폴리오").assertIsDisplayed()
        compose.onNodeWithText("60.6%").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "assets-modern.png").outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("수익률").performScrollTo().performClick()
        compose.onNodeWithText("+5.88%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("+5.41%").assertExists()
    }

    @Test
    fun runningRefreshControlsAreDisabledInsteadOfSilentlyIgnoringClicks() {
        val state = AppState(connected = true, running = true)
        compose.setContent {
            StockTheme {
                Column {
                    AssetsWorkspace(
                        state,
                        { error("must not refresh") },
                        { error("must not refresh") },
                    )
                }
            }
        }
        compose.onNodeWithText("잔고 갱신").assertIsNotEnabled()
        compose.onNodeWithText("손익").performClick()
        compose.onNodeWithText("30일 손익 조회").assertIsNotEnabled()
    }
}
