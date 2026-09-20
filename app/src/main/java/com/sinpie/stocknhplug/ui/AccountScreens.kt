package com.sinpie.stocknhplug.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sinpie.stocknhplug.application.*
import com.sinpie.stocknhplug.domain.Account

/** 선택은 화면만 바꾼다. 자동운용 enable과 실행 상태는 계좌마다 따로 표시한다. */
@Composable
internal fun AccountSelector(s: AppState, select: (Account) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        s.accountRuns.forEachIndexed { index, r ->
            FilterChip(
                s.selected == r.account,
                { select(r.account) },
                label = {
                    Text(
                        "${index + 1} · ${r.account.masked}${if (r.running) " · 운용" else ""}",
                        fontSize = 12.sp,
                    )
                },
            )
        }
    }
}

@Composable
internal fun AccountManagement(s: AppState, c: TradingWorkspace) {
    Panel("계좌별 운용") {
        Text("계좌 선택은 화면만 전환합니다. 자동운용을 켠 계좌들은 시작 버튼으로 함께 실행합니다.", fontSize = 12.sp, color = Muted)
        s.accountRuns.forEachIndexed { index, r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    TextButton({ c.select(r.account) }) {
                        Text("계좌 ${index + 1} · ${r.account.masked}")
                    }
                    Text(
                        if (r.running) "운용 중"
                        else if (r.busy) "처리 중" else if (r.connected) "연결됨" else "연결 필요",
                        fontSize = 12.sp,
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("자동운용", fontSize = 11.sp)
                    Switch(
                        r.enabled,
                        { c.enable(r.account, it) },
                        enabled = !s.storageError && !s.fleetBusy,
                    )
                }
            }
        }
        OutlinedButton(
            c::discoverAccounts,
            enabled = !s.fleetRunning && !s.fleetBusy && s.hasCredentials,
        ) {
            Text("계좌 목록 갱신")
        }
        Text("켜기 상태는 저장하지만 앱 재시작 후 자동으로 주문을 시작하지 않습니다.", fontSize = 11.sp, color = Muted)
        TextButton(
            c::importLegacySettings,
            enabled = s.selected != null && !s.running && !s.busy && !s.storageError,
        ) {
            Text("기존 공용 전략 가져오기")
        }
        Text("선택 계좌에 기존 전략·파킹 설정을 복사합니다. 계좌 위험 한도는 별도로 확인하세요.", fontSize = 11.sp, color = Muted)
    }
}
