package com.sinpie.stocknhplug

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.sinpie.stocknhplug.application.AppState
import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.ui.*
import java.io.File
import org.junit.*

class SettingsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun settingsSeparatesAccountQuotesAndSecurityWithoutPersistingInput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = AppContainer.get(context).controller
        compose.setContent { StockTheme { SettingsDialog(AppState(), controller) {} } }
        compose.onNodeWithText("앱키").assertIsDisplayed()
        compose.onNodeWithText("모의계좌 연결").assertIsNotEnabled()
        compose.onNodeWithText("시세").performClick()
        compose.onNodeWithContentDescription("WebSocket 시세 추적").assertIsOn()
        File(context.getExternalFilesDir(null), "settings-modern.png").outputStream().use {
            compose
                .onNode(isDialog())
                .captureToImage()
                .asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("보안").performClick()
        compose.onNodeWithText("Android Keystore 암호화").assertIsDisplayed()
        compose.onNodeWithText("계좌").performClick()
        compose.onNodeWithText("앱키").assertIsDisplayed()
    }

    @Test
    fun failedParkingSaveKeepsDraftAndClosesOnlyAfterAcknowledgement() {
        var state by mutableStateOf(AppState())
        var pending: StrategyBook? = null
        compose.setContent {
            StockTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ParkingSettingsCard(state) {
                        pending = it
                        state = state.copy(busy = true)
                    }
                }
            }
        }
        compose.onNodeWithText("파킹 설정").performClick()
        compose.onNodeWithText("파킹 종목코드 (6자리)").performTextReplacement("005940")
        compose.onNodeWithText("남겨둘 최소 현금 (원)").performTextReplacement("30000")
        compose.onNodeWithText("파킹 저장").performClick()
        compose.runOnIdle {
            state = state.copy(busy = false, messageError = true, message = "테스트 저장 실패")
        }
        compose.onNodeWithText("테스트 저장 실패").assertIsDisplayed()
        compose.onNodeWithText("남겨둘 최소 현금 (원)").assertTextContains("30000")
        compose.runOnIdle { state = state.copy(book = pending!!, messageError = false) }
        compose.onNodeWithText("파킹 저장").assertDoesNotExist()
    }
}
