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
import com.sinpie.stocknhplug.application.TradingWorkspace
import com.sinpie.stocknhplug.domain.*
import com.sinpie.stocknhplug.ui.*
import java.io.File
import org.junit.*
import org.junit.Assert.assertEquals

class SettingsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun settingsSeparatesAccountQuotesAndSecurityWithoutPersistingInput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = AppContainer.get(context).controller
        compose.setContent { StockTheme { SettingsDialog(AppState(), controller) {} } }
        compose.onNodeWithText("앱키").assertIsDisplayed()
        capture("settings-account")
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
        capture("settings-security")
        compose.onNodeWithText("계좌").performClick()
        compose.onNodeWithText("앱키").assertIsDisplayed()
    }

    @Test
    fun storageRecoveryRequiresIdleFleetAndKeepsResultVisible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var state by mutableStateOf(AppState(storageError = true))
        var deletions = 0
        var closes = 0
        val controller =
            object : TradingWorkspace by AppContainer.get(context).controller {
                override fun deleteAll() {
                    deletions++
                    state = state.copy(message = "테스트 삭제 실패", messageError = true)
                }
            }
        compose.setContent { StockTheme { SettingsDialog(state, controller) { closes++ } } }
        compose.onNodeWithText("보안").performClick()
        compose.onNodeWithText("전체 데이터 삭제").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { state = state.copy(fleetBusy = true) }
        compose.onNodeWithText("삭제").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(fleetBusy = false) }
        compose.onNodeWithText("삭제").performClick()
        compose.onNodeWithText("테스트 삭제 실패").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, deletions)
            assertEquals(0, closes)
        }
    }

    @Test
    fun parkingEditorHonorsStorageLockAndDiscardsDraftOnAccountSwitch() {
        var state by
            mutableStateOf(AppState(selected = Account("1111", Environment.MOCK, "nhplug")))
        compose.setContent { StockTheme { ParkingSettingsCard(state) {} } }
        compose.onNodeWithText("파킹 설정").performClick()
        compose.onNodeWithText("파킹 종목코드 (6자리)").performTextReplacement("005940")
        compose.onNodeWithText("파킹 주문 간격 (초, 60~3600)").performScrollTo().performClick()
        compose.onNodeWithText("파킹 주문 간격 (초, 60~3600)").performTextReplacement("120")
        compose.onNodeWithText("파킹 주문 간격 (초, 60~3600)").performScrollTo()
        // Android IME resizing is outside Compose's animation clock; wait for visible input.
        compose.waitUntil(5_000) { compose.onNodeWithText("파킹 주문 간격 (초, 60~3600)").isDisplayed() }
        capture("settings-parking-input")
        compose.onNodeWithText("파킹 저장").assertIsDisplayed().assertIsEnabled()
        // Native capture includes IME/window placement, which Dialog-only PixelCopy omits.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        File(instrumentation.targetContext.getExternalFilesDir(null), "settings-parking-device.png")
            .outputStream()
            .use {
                checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        compose.onNodeWithText("유휴 현금 자동 파킹").performScrollTo()
        capture("settings-parking")
        compose.runOnIdle { state = state.copy(storageError = true) }
        compose.onNodeWithText("파킹 저장").assertIsNotEnabled()
        compose.runOnIdle {
            state = state.copy(selected = Account("2222", Environment.MOCK, "nhplug"))
        }
        compose.onNodeWithText("파킹 저장").assertDoesNotExist()
        compose.onNodeWithText("파킹 설정").assertIsNotEnabled()
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            compose
                .onNode(isDialog())
                .captureToImage()
                .asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
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
