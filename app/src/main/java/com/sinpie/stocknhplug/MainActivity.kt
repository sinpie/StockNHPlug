package com.sinpie.stocknhplug

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.os.*
import android.view.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.sinpie.stocknhplug.application.*
import com.sinpie.stocknhplug.platform.TradingService
import com.sinpie.stocknhplug.ui.*

/**
 * 앱 런처 진입점. 화면 인증과 Android 권한만 담당하고 주문은 TradingService로 위임한다. 키·계좌 데이터는 인증 성공 후에만 UI에 바인딩한다. onStop
 * 이후 다시 인증해야 한다.
 */
class MainActivity : ComponentActivity() {
    private var unlocked by mutableStateOf(false)
    private val authentication =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            unlocked = result.resultCode == RESULT_OK
        }
    private val notifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startTradingService()
        }

    /** 창 보안 플래그를 먼저 설정한 뒤 Compose를 연결한다. 재생성된 화면은 잠금 상태에서 시작한다. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        val controller = AppContainer.get(this).controller
        setContent {
            StockTheme {
                if (!unlocked) LockScreen(::unlock)
                else
                    StockApp(
                        controller,
                        {
                            if (
                                Build.VERSION.SDK_INT >= 33 &&
                                    ContextCompat.checkSelfPermission(
                                        this,
                                        Manifest.permission.POST_NOTIFICATIONS,
                                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                            )
                                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else startTradingService()
                        },
                        {
                            controller.stop()
                            stopService(Intent(this, TradingService::class.java))
                        },
                    )
            }
        }
    }

    /** 운영체제의 기기 자격증명 확인 화면을 연다. 기기 잠금이 없으면 인증을 우회하지 않는다. */
    @Suppress("DEPRECATION")
    private fun unlock() {
        val manager = getSystemService(KeyguardManager::class.java)
        val intent =
            manager.createConfirmDeviceCredentialIntent("StockNHPlug 잠금 해제", "기기 인증으로 투자정보를 보호합니다.")
        if (intent != null) authentication.launch(intent)
    }

    /** 사용자의 시작 확인과 알림 권한 처리 뒤 호출된다. OS가 실행을 거절하면 세션을 정지한다. */
    private fun startTradingService() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, TradingService::class.java))
        } catch (_: Exception) {
            AppContainer.get(this).controller.stop("시스템에서 백그라운드 실행을 허용하지 않았습니다.")
        }
    }

    /** 화면이 사라지면 UI만 잠근다. 이미 승인된 foreground 매매 세션의 생명주기는 서비스가 소유한다. */
    override fun onStop() {
        unlocked = false
        super.onStop()
    }

    /** 다른 창에 가려진 좌표 입력을 거절한다. 정상 터치와 접근성 동작은 기본 Activity에 위임한다. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0) return false
        if (
            Build.VERSION.SDK_INT >= 29 &&
                event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0
        )
            return false
        return super.dispatchTouchEvent(event)
    }
}
