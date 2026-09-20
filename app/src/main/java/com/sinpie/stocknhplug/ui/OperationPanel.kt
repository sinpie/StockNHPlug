package com.sinpie.stocknhplug.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.sp
import com.sinpie.stocknhplug.application.*
import java.time.Instant

/** 점검 결과를 한곳에 모은다. 펼침은 UI 상태이며 주문/복구 명령을 발생시키지 않는다. */
@Composable
internal fun OperationPanel(s: AppState, now: Instant) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val report = remember(s, now) { OperationReview.inspect(s, now) }
    Panel("운용 점검") {
        Text(
            "차단 ${report.items.count { it.level == ReviewLevel.BLOCKED }} · 확인 ${report.items.count { it.level == ReviewLevel.ATTENTION }}"
        )
        Text(
            "오늘 매수 예약 ${amount(report.committedBuy)} · 한도 잔여 ${amount(report.remainingBuy)}",
            fontSize = 12.sp,
        )
        Text("선택 계좌 기준 · 점검 결과는 주문 허가가 아닙니다.", fontSize = 11.sp, color = Muted)
        TextButton({ expanded = !expanded }) { Text(if (expanded) "점검 접기" else "점검 자세히") }
        if (expanded)
            report.items.forEach { item ->
                HorizontalDivider()
                val label =
                    when (item.level) {
                        ReviewLevel.READY -> "확인됨"
                        ReviewLevel.ATTENTION -> "확인 필요"
                        ReviewLevel.BLOCKED -> "차단"
                    }
                Text(
                    "${item.title} · $label",
                    color = if (item.level == ReviewLevel.BLOCKED) Red else Ink,
                )
                Text(item.detail, fontSize = 12.sp, color = Muted)
            }
        Text("정지는 새 자동주문을 중단합니다. 접수·미체결 주문의 취소는 증권사에서 확인하세요.", fontSize = 11.sp, color = Muted)
    }
}
