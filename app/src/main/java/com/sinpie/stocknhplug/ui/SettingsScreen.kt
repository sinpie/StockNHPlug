package com.sinpie.stocknhplug.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sinpie.stocknhplug.application.*

/** 계좌/시세/보안으로 구획한다. 비밀 입력은 화면 복원·스크린샷 자료에 저장하지 않는다. */
@Composable
internal fun SettingsDialog(s: AppState, c: TradingWorkspace, close: () -> Unit) {
    val accountCommands = c.accountWorkspace(s.selected)
    var section by remember { mutableIntStateOf(0) }
    var key by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var dart by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf(false) }
    val editable = !s.running && !s.busy && !s.storageError
    val globalEditable = !s.fleetRunning && !s.fleetBusy && editable
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().imePadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("설정", Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    IconButton(close) { Icon(Icons.Outlined.Close, "설정 닫기") }
                }
                SectionTabs(listOf("계좌", "시세", "보안"), section) { section = it }
                if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                StatusBanner(s.message, s.messageError || s.storageError)
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (section) {
                        0 -> {
                            Panel("API 연결") {
                                Text(
                                    "본인 명의 NHPlug API 신청·약관 동의가 필요합니다.",
                                    fontSize = 12.sp,
                                    color = Muted,
                                )
                                Field("앱키", key, { key = it }, globalEditable, secret = true)
                                Field("시크릿", secret, { secret = it }, globalEditable, secret = true)
                                Field(
                                    "OpenDART 키 (선택)",
                                    dart,
                                    { dart = it },
                                    globalEditable,
                                    secret = true,
                                )
                                Text(
                                    "기기 내 암호화 · 입력은 저장 요청 후 비워집니다.",
                                    fontSize = 11.sp,
                                    color = Muted,
                                )
                                Button(
                                    {
                                        c.saveCredentials(key, secret, dart)
                                        key = ""
                                        secret = ""
                                        dart = ""
                                    },
                                    Modifier.fillMaxWidth(),
                                    enabled =
                                        globalEditable && key.isNotBlank() && secret.isNotBlank(),
                                ) {
                                    Text("키 저장")
                                }
                            }
                            AccountManagement(s, c)
                            Panel("연결 계좌") {
                                OutlinedButton(
                                    { accountCommands.connect() },
                                    Modifier.fillMaxWidth(),
                                    enabled = editable && s.hasCredentials,
                                ) {
                                    Text(if (s.connected) "다시 연결" else "모의계좌 연결")
                                }
                                if (s.accounts.isEmpty())
                                    Text("연결 후 계좌를 선택할 수 있습니다.", fontSize = 12.sp, color = Muted)
                                s.accounts.forEach { account ->
                                    FilterChip(
                                        s.selected == account,
                                        { c.select(account) },
                                        enabled = true,
                                        label = { Text("모의 ${account.masked}") },
                                    )
                                }
                            }
                        }
                        1 ->
                            Panel("시세 연결") {
                                TrackingOptions(
                                    s.settings.websocketEnabled,
                                    editable && s.selected != null,
                                ) {
                                    accountCommands.saveSettings(
                                        s.settings.copy(websocketEnabled = it)
                                    )
                                }
                            }
                        2 -> {
                            Panel("기기 보안") {
                                Text("Android Keystore 암호화", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "키는 인증 시 발급기관의 공식 API로만 전송합니다. 개발자 서버·광고·분석 SDK는 없습니다.",
                                    fontSize = 13.sp,
                                    color = Muted,
                                )
                                Text(
                                    "키·설정·주문·보유 이력·추적 최고/최저·로그는 기기 내부에 암호화 저장합니다. Android 백업과 기기 이전은 차단합니다.",
                                    fontSize = 13.sp,
                                    color = Muted,
                                )
                            }
                            Panel("데이터 관리") {
                                Text(
                                    "전체 삭제는 이 기기의 기록만 제거합니다. 증권사 계좌·미체결 주문은 유지됩니다.",
                                    fontSize = 13.sp,
                                    color = Muted,
                                )
                                TextButton({ delete = true }, enabled = globalEditable) {
                                    Text("전체 데이터 삭제", color = Red)
                                }
                            }
                            Text(
                                "NH투자증권 공식 앱이 아닙니다. 실거래는 출시 검증 전 잠겨 있습니다.",
                                fontSize = 12.sp,
                                color = Muted,
                            )
                        }
                    }
                }
            }
        }
    }
    if (delete)
        AlertDialog(
            onDismissRequest = { delete = false },
            title = { Text("전체 데이터를 삭제할까요?") },
            text = { Text("키와 설정, 주문·추적 이력을 삭제합니다. 미확인 주문은 먼저 증권사에서 확인하세요.") },
            confirmButton = {
                TextButton({
                    c.deleteAll()
                    delete = false
                    close()
                }) {
                    Text("삭제", color = Red)
                }
            },
            dismissButton = { TextButton({ delete = false }) { Text("취소") } },
        )
}
