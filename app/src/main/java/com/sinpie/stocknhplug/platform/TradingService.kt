package com.sinpie.stocknhplug.platform

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import com.sinpie.stocknhplug.AppContainer
import com.sinpie.stocknhplug.MainActivity
import com.sinpie.stocknhplug.R
import com.sinpie.stocknhplug.application.TradingWorkspace
import kotlinx.coroutines.*

/** User-visible background session; OS process termination never restarts order placement. */
class TradingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: TradingWorkspace
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
                .setContentText("활성 계좌 동시 감시 · 즉시 정지는 모든 계좌에 적용")
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "즉시 정지", stop).build())
                .build()
        try {
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(7, notification)
            controller.startSession()
            // 반복 시작 intent가 상태 관찰자를 누적하지 않도록 하나만 유지한다.
            if (observer?.isActive != true)
                observer =
                    scope.launch {
                        controller.state.collect { if (!it.fleetRunning && !it.running) stopSelf() }
                    }
        } catch (_: Exception) {
            controller.stop("실행 조건을 확인하세요. 자동매매를 시작하지 않았습니다.")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** 최근 앱 목록 제거는 UI만 닫는다. 사용자가 시작한 서비스와 정지 알림은 유지한다. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    /** 시스템이 실행 시간을 제한한 경우 즉시 정리한다. 타입 변경/OS 정책에도 정지를 존중한다. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        controller.stop("시스템 실행 제한으로 자동매매 정지")
        stopSelf()
    }

    /** 서비스 종료 시 엔진과 상태 관찰 코루틴을 정리한다. */
    override fun onDestroy() {
        controller.stop("자동매매 서비스 종료")
        scope.cancel()
        super.onDestroy()
    }
}
