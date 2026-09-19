package com.sinpie.stocknhplug.platform

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import com.sinpie.stocknhplug.AppContainer
import com.sinpie.stocknhplug.MainActivity
import com.sinpie.stocknhplug.R
import com.sinpie.stocknhplug.application.TradingController
import kotlinx.coroutines.*

/** User-visible background session; OS process termination never restarts order placement. */
class TradingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: TradingController
    private var observer: Job? = null

    /** UI와 동일한 프로세스 컨트롤러를 연결한다. 새로운 별도 매매 엔진을 생성하지 않는다. */
    override fun onCreate() {
        super.onCreate()
        controller = AppContainer.get(this).controller
    }

    override fun onBind(intent: Intent?) = null

    /** 백그라운드 실행 진입점. 정지 intent를 우선 처리하고 지속 알림을 만든 뒤 세션을 시작한다. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            controller.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("trading", "자동매매 실행", NotificationManager.IMPORTANCE_LOW)
        )
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, TradingService::class.java).setAction("STOP"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            Notification.Builder(this, "trading")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("모의 자동매매 실행 중")
                .setContentText("가격 추적 및 위험 한도 감시 · 최대 6시간")
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "즉시 정지", stop).build())
                .build()
        try {
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(7, notification)
            if (!controller.state.value.running) controller.startSession()
            // 반복 시작 intent가 상태 관찰자를 누적하지 않도록 하나만 유지한다.
            if (observer?.isActive != true)
                observer = scope.launch { controller.state.collect { if (!it.running) stopSelf() } }
        } catch (_: Exception) {
            controller.stop("실행 조건을 확인하세요. 자동매매를 시작하지 않았습니다.")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** 최근 앱 목록에서 제거하면 실행을 정지한다. 주문 자동 복구는 하지 않는다. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        controller.stop("앱 종료로 자동매매 정지")
        stopSelf()
    }

    /** 서비스 종료 시 엔진과 상태 관찰 코루틴을 정리한다. */
    override fun onDestroy() {
        controller.stop("자동매매 서비스 종료")
        scope.cancel()
        super.onDestroy()
    }
}
