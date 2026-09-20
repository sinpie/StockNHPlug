package com.sinpie.stocknhplug

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.sinpie.stocknhplug.ui.*
import java.io.File
import org.junit.*

/** UI-only host: never bypasses authentication in MainActivity or contacts a broker. */
class UiInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun navigationEmptyStatesAndPreview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = AppContainer.get(context).controller
        compose.setContent { StockTheme { StockApp(controller, {}, {}) } }
        compose.onNodeWithText("계좌 현황").assertIsDisplayed()
        compose.onNodeWithText("모의 자동매매 시작").assertIsNotEnabled()
        val file = File(context.getExternalFilesDir(null), "dashboard.png")
        file.outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNode(hasText("전략") and hasClickAction()).performClick()
        compose.onNodeWithText("전략 관리").assertIsDisplayed()
        compose.onNode(hasText("시세") and hasClickAction()).performClick()
        compose.onNodeWithText("관심종목").assertIsDisplayed()
        compose.onNodeWithText("분석").performClick()
        compose.onNodeWithText("분석 시작").assertIsNotEnabled()
        compose.onNode(hasText("자산") and hasClickAction()).performClick()
        compose.onNodeWithText("조회된 보유종목이 없습니다.").assertIsDisplayed()
        compose.onNodeWithText("손익").performClick()
        compose.onNodeWithText("30일 손익 조회").assertIsNotEnabled()
        compose.onNode(hasText("내역") and hasClickAction()).performClick()
        // 큰 글꼴에서는 필터 아래 안내가 첫 viewport 밖에 있다. 실제 스크롤 접근성을 확인한다.
        compose.onNodeWithText("앱에서 보낸 주문이 없습니다.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("로그").performClick()
        compose.onNodeWithText("오류만 보기").assertIsDisplayed()
        compose.onNode(hasText("시세") and hasClickAction()).performClick()
        compose.onNodeWithText("분석 설정").assertIsDisplayed()
    }
}
