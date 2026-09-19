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
import com.sinpie.stocknhplug.ui.*

class MainActivity:ComponentActivity() {
    private var unlocked by mutableStateOf(false)
    private val authentication=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> unlocked=result.resultCode==RESULT_OK }
    private val notifications=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if(granted) startTradingService() }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if(Build.VERSION.SDK_INT>=31) window.setHideOverlayWindows(true)
        val controller=TradingController.get(this)
        setContent {
            StockTheme {
                if(!unlocked) LockScreen(::unlock)
                else StockApp(controller,{ if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else startTradingService() },{ controller.stop(); stopService(Intent(this,TradingService::class.java)) })
            }
        }
    }
    @Suppress("DEPRECATION") private fun unlock() {
        val manager=getSystemService(KeyguardManager::class.java)
        val intent=manager.createConfirmDeviceCredentialIntent("StockNHPlug 잠금 해제","기기 인증으로 투자정보를 보호합니다.")
        if(intent!=null) authentication.launch(intent)
    }
    private fun startTradingService() {
        try { ContextCompat.startForegroundService(this,Intent(this,TradingService::class.java)) }
        catch (_:Exception) { TradingController.get(this).stop("시스템에서 백그라운드 실행을 허용하지 않았습니다.") }
    }
    override fun onStop() { unlocked=false; super.onStop() }
    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        if(event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0) return false
        if(Build.VERSION.SDK_INT>=29 && event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0) return false
        return super.dispatchTouchEvent(event)
    }
}
