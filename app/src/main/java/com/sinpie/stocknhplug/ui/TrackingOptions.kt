package com.sinpie.stocknhplug.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Global channel preference; saving requires a stopped session and applies on reconnect. */
@Composable
internal fun TrackingOptions(
    websocketEnabled: Boolean,
    editable: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("시세 추적 · 배터리", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("WebSocket 시세 추적", Modifier.weight(1f))
            Switch(
                websocketEnabled,
                onChange,
                enabled = editable,
                modifier = Modifier.semantics { contentDescription = "WebSocket 시세 추적" },
            )
        }
        Text(
            if (websocketEnabled) "기본 켜짐 · 타겟과 10% 미만이면 WebSocket, 10% 이상이면 거리별 REST 조회를 사용합니다."
            else "꺼짐 · WebSocket을 연결하지 않고 REST로 2~10초 간격의 조회를 예약합니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "자동매매 정지 후 변경하고 계좌를 다시 연결하세요. 통신·종목 수·API 제한으로 조회가 늦어질 수 있습니다.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
