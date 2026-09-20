package com.sinpie.stocknhplug.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sinpie.stocknhplug.application.AppState
import com.sinpie.stocknhplug.domain.*
import java.text.NumberFormat

/** 파킹 설정은 계좌 공통 현금 정책이다. 입력 초안은 저장 전까지 주문/구독에 영향을 주지 않는다. */
@Composable
fun ParkingSettingsCard(state: AppState, save: (StrategyBook) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ParkingPolicy?>(null) }
    LaunchedEffect(state.book.parking, pending) {
        if (pending != null && state.book.parking == pending) {
            editing = false
            pending = null
        }
    }
    val policy = state.book.parking
    val number = NumberFormat.getIntegerInstance()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("현금 관리", fontWeight = FontWeight.Bold)
            Text(if (policy.enabled) "켜짐 · ${policy.symbol}" else "꺼짐 · 파킹종목을 설정하세요")
            Text(
                "최소 현금 ${number.format(policy.reserveCash)}원 · 최대 파킹 ${number.format(policy.maxValue)}원"
            )
            val holding =
                state.groupPositions[ParkingPolicy.GROUP_ID]?.find { it.symbol == policy.symbol }
            Text(
                if (holding == null) "확정된 파킹 보유 기록 없음"
                else
                    "앱 파킹 ${holding.quantity}주 · 평균 ${number.format(holding.average)}원 · 실현손익 ${number.format(holding.realized)}원"
            )
            Text("주식 매수 자금이 부족하면 파킹을 먼저 매도하고, 체결과 주문가능 현금을 확인한 뒤 다시 판단합니다.")
            OutlinedButton({ editing = true }, enabled = !state.running && !state.busy) {
                Text("파킹 설정")
            }
        }
    }
    if (editing)
        ParkingEditor(state, { editing = false }) { next ->
            pending = next
            save(state.book.copy(parking = next))
        }
}

@Composable
private fun ParkingEditor(state: AppState, close: () -> Unit, save: (ParkingPolicy) -> Unit) {
    var draft by remember(state.book.parking) { mutableStateOf(state.book.parking) }
    val symbolLocked = state.orders.any { it.intent.groupId == ParkingPolicy.GROUP_ID }
    val error = runCatching { state.book.copy(parking = draft).validate() }.exceptionOrNull()
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(close) { Text("취소") }
                    Text("파킹 설정", fontWeight = FontWeight.Bold)
                    Button(
                        { save(draft) },
                        enabled = error == null && !state.running && !state.busy,
                    ) {
                        Text("파킹 저장")
                    }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                else
                    Column(
                        Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (state.messageError)
                            Text(state.message, color = MaterialTheme.colorScheme.error)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("유휴 현금 자동 파킹")
                            Switch(draft.enabled, { draft = draft.copy(enabled = it) })
                        }
                        OutlinedTextField(
                            draft.symbol,
                            { draft = draft.copy(symbol = it.trim()) },
                            label = { Text("파킹 종목코드 (6자리)") },
                            enabled = !symbolLocked,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                        if (symbolLocked)
                            Text("거래 기록이 있는 파킹종목은 변경할 수 없습니다. 끄면 기존 보유량을 자동 매도하지 않습니다.")
                        ParkingNumber("남겨둘 최소 현금 (원)", draft.reserveCash) {
                            draft = draft.copy(reserveCash = it)
                        }
                        ParkingNumber("최대 파킹 평가금액 (원)", draft.maxValue) {
                            draft = draft.copy(maxValue = it)
                        }
                        ParkingNumber("파킹 1회 주문 한도 (원)", draft.orderBudget) {
                            draft = draft.copy(orderBudget = it)
                        }
                        ParkingNumber("파킹 하루 매수+매도 한도 (원)", draft.dailyTurnover) {
                            draft = draft.copy(dailyTurnover = it)
                        }
                        ParkingNumber("최소 파킹 주문금액 (원)", draft.minimumTrade) {
                            draft = draft.copy(minimumTrade = it)
                        }
                        ParkingNumber("파킹 주문 간격 (초, 60~3600)", draft.cooldownSeconds.toLong()) {
                            draft =
                                draft.copy(
                                    cooldownSeconds =
                                        if (it in 0..Int.MAX_VALUE.toLong()) it.toInt() else -1
                                )
                        }
                        Text(
                            "파킹은 현금과 달리 가격이 변합니다. 수수료 여유 1%와 정수 주식 단위 때문에 잔여 현금이 생깁니다. 공통 자동매도 동의·계좌 한도·데이터 검증을 적용하며 외부에서 산 보유분은 사용하지 않습니다."
                        )
                        Text(
                            "현재 NHPlug 체결 대사 검증 전 실제 자동주문은 잠겨 있습니다. 나무매직과 동일한 가격 추적은 아직 적용되지 않았습니다."
                        )
                        if (error != null)
                            Text(
                                "종목코드·금액 범위·전략그룹과 종목 중복 여부를 확인하세요.",
                                color = MaterialTheme.colorScheme.error,
                            )
                    }
            }
        }
    }
}

/** 빈칸/소수/범위 초과 입력은 -1로 유효성 오류를 전달해 이전 값으로 몰래 저장하지 않는다. */
@Composable
private fun ParkingNumber(label: String, value: Long, update: (Long) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        text,
        {
            text = it
            update(it.toLongOrNull() ?: -1)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}
