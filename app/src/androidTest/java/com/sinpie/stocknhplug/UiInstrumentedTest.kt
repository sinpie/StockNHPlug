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
        compose.onNodeWithText("투자의 흐름을 한눈에").assertIsDisplayed()
        compose.onNodeWithText("모의 자동매매 시작").assertIsNotEnabled()
        val file = File(context.getExternalFilesDir(null), "dashboard.png")
        file.outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("전략", useUnmergedTree = true).performClick()
        compose.onNodeWithText("전략과 그룹").assertIsDisplayed()
        compose.onNodeWithText("리서치", useUnmergedTree = true).performClick()
        compose.onNodeWithText("매수 전, 근거부터").assertIsDisplayed()
        compose.onNodeWithText("관심종목 분석").assertIsNotEnabled()
        compose.onNodeWithText("보유", useUnmergedTree = true).performClick()
        compose.onNodeWithText("조회된 보유종목이 없습니다.").assertIsDisplayed()
        compose.onNodeWithText("기록", useUnmergedTree = true).performClick()
        compose.onNodeWithText("앱에서 보낸 주문이 없습니다.").assertIsDisplayed()
        compose.onNodeWithText("로그", useUnmergedTree = true).performClick()
        compose.onNodeWithText("실시간 실행 로그").assertIsDisplayed()
    }
}
