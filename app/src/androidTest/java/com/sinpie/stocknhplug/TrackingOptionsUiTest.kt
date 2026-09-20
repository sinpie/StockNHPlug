package com.sinpie.stocknhplug

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.sinpie.stocknhplug.ui.*
import org.junit.*

class TrackingOptionsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun switchStartsOnCanDisableAndLocksWhileRunning() {
        var enabled by mutableStateOf(true)
        var editable by mutableStateOf(true)
        compose.setContent { StockTheme { TrackingOptions(enabled, editable) { enabled = it } } }
        val toggle = compose.onNodeWithContentDescription("WebSocket 시세 추적")
        toggle.assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { editable = false }
        toggle.assertIsNotEnabled()
        compose.runOnIdle { Assert.assertFalse(enabled) }
    }
}
