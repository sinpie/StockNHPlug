package com.sinpie.stocknhplug.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sinpie.stocknhplug.application.AppState
import com.sinpie.stocknhplug.domain.*
import java.text.NumberFormat

private val GroupMuted = Color(0xFF596D78)

private fun money(value: Long) = NumberFormat.getIntegerInstance().format(value) + "원"

/** 전략 목록 → 전략별 그룹/추천/정기매수 → 그룹 편집. UI는 초안만 편집하고 실행은 컨트롤러에 위임한다. */
@Composable
fun StrategyGroupsScreen(
    s: AppState,
    save: (StrategyBook) -> Unit,
    onNavigate: () -> Unit = {},
    riskSettings: @Composable () -> Unit,
) {
    var selected by remember { mutableStateOf<String?>(null) }
    var editGroup by remember { mutableStateOf<StrategyGroup?>(null) }
    var adding by remember { mutableStateOf(false) }
    var risk by remember { mutableStateOf(false) }
    val editable = !s.running && !s.busy
    val plan = s.book.plans.find { it.id == selected }
    if (plan == null) {
        Text("전략과 그룹", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("같은 종목도 그룹마다 독립적으로 관리하세요.", color = GroupMuted)
        GCard {
            Text(
                "${s.book.plans.size}개 전략 · ${s.book.groups.size}개 그룹",
                fontWeight = FontWeight.Bold,
            )
            Text(
                "그룹별 체결 연결 검증 전 자동주문은 잠겨 있습니다. 설정은 지금 구성할 수 있습니다.",
                fontSize = 12.sp,
                color = GroupMuted,
            )
        }
        ParkingSettingsCard(s, save)
        s.book.plans.forEach { p ->
            val groups = s.book.groups.filter { it.strategyId == p.id }
            GCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "${s.groupAlgorithms[p.algorithmId] ?: "추가 전략"} · ${groups.size}개 그룹",
                            color = GroupMuted,
                            fontSize = 12.sp,
                        )
                    }
                    Switch(
                        p.enabled,
                        { enabled ->
                            save(
                                s.book.copy(
                                    plans =
                                        s.book.plans.map {
                                            if (it.id == p.id) it.copy(enabled = enabled) else it
                                        }
                                )
                            )
                        },
                        enabled = editable,
                    )
                }
                Text(
                    "추천매수 ${if (p.recommendation.enabled) "켜짐" else "꺼짐"}  ·  정기매수 ${if (p.schedule.enabled) "켜짐" else "꺼짐"}",
                    fontSize = 13.sp,
                )
                groups.take(3).forEach {
                    Text(
                        "${if (it.enabled) "●" else "○"} ${it.name} · ${it.symbols.size}종목",
                        fontSize = 13.sp,
                        color = GroupMuted,
                    )
                }
                OutlinedButton(
                    {
                        selected = p.id
                        onNavigate()
                    },
                    Modifier.fillMaxWidth(),
                ) {
                    Text("${p.name} 관리")
                }
            }
        }
        Button(
            { adding = true },
            Modifier.fillMaxWidth(),
            enabled = editable && s.book.plans.size < 20,
        ) {
            Text("+ 전략 추가")
        }
        TextButton({ risk = !risk }) { Text(if (risk) "계좌 공통 한도 접기" else "계좌 공통 위험 한도") }
        if (risk) riskSettings()
    } else {
        TextButton({
            selected = null
            onNavigate()
        }) {
            Text("‹ 전체 전략")
        }
        key(plan.id) {
            var section by remember { mutableIntStateOf(0) }
            var draft by remember(plan) { mutableStateOf(plan) }
            var error by remember { mutableStateOf("") }
            Text(plan.name, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text(
                "${s.groupAlgorithms[plan.algorithmId]} · 전략 설정을 각 그룹에 적용합니다.",
                color = GroupMuted,
                fontSize = 12.sp,
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("그룹", "추천매수", "정기매수").forEachIndexed { i, label ->
                    FilterChip(
                        section == i,
                        {
                            section = i
                            onNavigate()
                        },
                        label = { Text(label) },
                    )
                }
            }
            when (section) {
                0 -> {
                    val groups = s.book.groups.filter { it.strategyId == plan.id }
                    if (groups.isEmpty())
                        GCard {
                            Text("첫 그룹을 만들어 보세요", fontWeight = FontWeight.Bold)
                            Text(
                                "예: 장기 적립, 하락 분할매수, 성장주 비중 조절",
                                color = GroupMuted,
                                fontSize = 13.sp,
                            )
                        }
                    groups.forEach { group ->
                        GCard {
                            Text(group.name, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                            Text(
                                "${if (group.enabled) "설정 켜짐" else "설정 꺼짐"} · 자본 ${money(group.capital)}",
                                color = GroupMuted,
                                fontSize = 12.sp,
                            )
                            Text(
                                group.symbols
                                    .joinToString(" · ") { "${it.symbol} ${it.weightPercent}%" }
                                    .ifBlank { "종목을 추가하세요" },
                                fontSize = 13.sp,
                            )
                            Text(
                                "정기매수: ${when(group.scheduleOverride) { ScheduleOverride.INHERIT -> "전략 설정 따름"
 ScheduleOverride.OFF -> "끄기"
 ScheduleOverride.CUSTOM -> "별도 설정" }}",
                                color = GroupMuted,
                                fontSize = 12.sp,
                            )
                            val positions = s.groupPositions[group.id].orEmpty()
                            if (positions.isEmpty())
                                Text("확정 체결 내역 없음", fontSize = 12.sp, color = GroupMuted)
                            positions.forEach {
                                Text(
                                    "${it.symbol} ${it.quantity}주 · 평균 ${money(it.average)} · 실현 ${money(it.realized)}",
                                    fontSize = 12.sp,
                                )
                            }
                            Row {
                                OutlinedButton({ editGroup = group }, enabled = editable) {
                                    Text("그룹 편집")
                                }
                                TextButton(
                                    {
                                        save(
                                            s.book.copy(
                                                groups =
                                                    s.book.groups.filterNot { it.id == group.id }
                                            )
                                        )
                                    },
                                    enabled =
                                        editable && s.orders.none { it.intent.groupId == group.id },
                                ) {
                                    Text("삭제")
                                }
                            }
                        }
                    }
                    Button(
                        { editGroup = StrategyGroup(strategyId = plan.id, name = "새 그룹") },
                        Modifier.fillMaxWidth(),
                        enabled = editable && s.book.groups.size < 100,
                    ) {
                        Text("+ 그룹 추가")
                    }
                    Text("거래 기록이 있는 그룹은 삭제 대신 끄기로 보관합니다.", fontSize = 12.sp, color = GroupMuted)
                }
                1 ->
                    RecommendationEditor(
                        draft.recommendation,
                        { draft = draft.copy(recommendation = it) },
                        editable,
                    )
                2 -> {
                    GCard {
                        Text("이 설정은 ‘전략 설정 따름’인 각 그룹에 적용됩니다. 금액은 그룹별·회차별 예산입니다.", fontSize = 13.sp)
                    }
                    ScheduleEditor(draft.schedule, { draft = draft.copy(schedule = it) }, editable)
                }
            }
            if (section != 0) {
                OutlinedTextField(
                    draft.name,
                    { draft = draft.copy(name = it) },
                    label = { Text("전략 이름") },
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                Button(
                    {
                        val next =
                            s.book.copy(
                                plans = s.book.plans.map { if (it.id == draft.id) draft else it }
                            )
                        runCatching { next.validate() }
                            .onSuccess {
                                error = ""
                                save(next)
                            }
                            .onFailure { error = "입력 범위와 활성 지표를 확인하세요." }
                    },
                    Modifier.fillMaxWidth(),
                    enabled = editable,
                ) {
                    Text("전략 설정 저장")
                }
            }
        }
    }
    if (adding)
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("추가할 전략") },
            text = {
                Column {
                    s.groupAlgorithms.forEach { (id, name) ->
                        TextButton({
                            save(
                                s.book.copy(
                                    plans =
                                        s.book.plans +
                                            StrategyPlan(
                                                name =
                                                    "$name ${s.book.plans.count { it.algorithmId == id } + 1}",
                                                algorithmId = id,
                                            )
                                )
                            )
                            adding = false
                        }) {
                            Text(name)
                        }
                    }
                }
            },
            confirmButton = { TextButton({ adding = false }) { Text("닫기") } },
        )
    editGroup?.let { group ->
        GroupEditor(
            group,
            s.book.plans.single { it.id == group.strategyId }.algorithmId,
            { editGroup = null },
        ) { edited ->
            val next = s.book.copy(groups = s.book.groups.filterNot { it.id == edited.id } + edited)
            next.validate()
            save(next)
            editGroup = null
        }
    }
}

@Composable
private fun GCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun Toggle(
    label: String,
    value: Boolean,
    enabled: Boolean = true,
    change: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Switch(value, change, enabled = enabled)
    }
}

/** 불완전하거나 정수 필드의 소수 입력은 범위 밖 값으로 전달해 저장 검증이 거절하게 한다. */
@Composable
private fun NumberField(
    label: String,
    value: Number,
    enabled: Boolean = true,
    change: (Double) -> Unit,
) {
    var text by remember { mutableStateOf(value.toString()) }
    val integer = value is Int || value is Long
    fun parsed(input: String) =
        input.toDoubleOrNull()?.takeIf {
            it.isFinite() && (!integer || it == kotlin.math.floor(it))
        }
    OutlinedTextField(
        text,
        {
            text = it
            change(parsed(it) ?: Double.NEGATIVE_INFINITY)
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = parsed(text) == null,
    )
}

@Composable
private fun RecommendationEditor(
    rule: RecommendationRule,
    change: (RecommendationRule) -> Unit,
    editable: Boolean,
) {
    var revision by remember { mutableIntStateOf(0) }
    GCard {
        Toggle("추천종목 자동매수", rule.enabled, editable) { change(rule.copy(enabled = it)) }
        Text("켜진 지표를 모두 만족하는 그룹 내 종목을 매수 후보로 삼습니다.", fontSize = 12.sp, color = GroupMuted)
        Text("지표 조합 5가지", fontWeight = FontWeight.Bold)
        RecommendationPresets.names.forEach { (id, name) ->
            FilterChip(
                rule.preset == id,
                {
                    change(RecommendationPresets.rule(id).copy(enabled = rule.enabled))
                    revision++
                },
                enabled = editable,
                label = { Text(name) },
            )
        }
    }
    key(revision) {
        GCard {
            Toggle("이동평균 추세", rule.trend, editable) { change(rule.copy(trend = it)) }
            NumberField("단기 이동평균 일수 (2~100)", rule.fastDays, editable && rule.trend) {
                change(rule.copy(fastDays = it.toInt()))
            }
            NumberField("장기 이동평균 일수 (단기 초과~250)", rule.slowDays, editable && rule.trend) {
                change(rule.copy(slowDays = it.toInt()))
            }
            Toggle("RSI 범위", rule.rsi, editable) { change(rule.copy(rsi = it)) }
            NumberField("RSI 최소 (0~100)", rule.rsiMin, editable && rule.rsi) {
                change(rule.copy(rsiMin = it))
            }
            NumberField("RSI 최대 (최소~100)", rule.rsiMax, editable && rule.rsi) {
                change(rule.copy(rsiMax = it))
            }
            Toggle("20일 모멘텀", rule.momentum, editable) { change(rule.copy(momentum = it)) }
            NumberField("최소 상승률 %", rule.momentumMin, editable && rule.momentum) {
                change(rule.copy(momentumMin = it))
            }
            Toggle("거래량 배수", rule.volume, editable) { change(rule.copy(volume = it)) }
            NumberField("20일 평균 대비 최소 배수", rule.volumeMin, editable && rule.volume) {
                change(rule.copy(volumeMin = it))
            }
            Toggle("ATR 변동성", rule.volatility, editable) { change(rule.copy(volatility = it)) }
            NumberField("최대 ATR %", rule.atrMax, editable && rule.volatility) {
                change(rule.copy(atrMax = it))
            }
            Toggle("영업이익률", rule.profitability, editable) { change(rule.copy(profitability = it)) }
            NumberField("최소 영업이익률 %", rule.marginMin, editable && rule.profitability) {
                change(rule.copy(marginMin = it))
            }
            Toggle("부채비율", rule.leverage, editable) { change(rule.copy(leverage = it)) }
            NumberField("최대 부채비율 %", rule.debtMax, editable && rule.leverage) {
                change(rule.copy(debtMax = it))
            }
        }
    }
    Text(
        "조합은 설정 예시이며 수익을 보장하지 않습니다. 수정주가·재무·공시·뉴스 이용권한 검증은 끌 수 없습니다.",
        fontSize = 12.sp,
        color = GroupMuted,
    )
}

@Composable
private fun ScheduleEditor(
    schedule: PurchaseSchedule,
    change: (PurchaseSchedule) -> Unit,
    editable: Boolean,
) {
    GCard {
        Toggle("정기매수 사용", schedule.enabled, editable) { change(schedule.copy(enabled = it)) }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                    ScheduleFrequency.WEEKDAYS to "매 평일",
                    ScheduleFrequency.WEEKLY to "매주",
                    ScheduleFrequency.MONTHLY to "매월",
                )
                .forEach { (f, label) ->
                    FilterChip(
                        schedule.frequency == f,
                        { change(schedule.copy(frequency = f, day = 1)) },
                        label = { Text(label) },
                        enabled = editable,
                    )
                }
        }
        key(schedule.frequency) {
            if (schedule.frequency != ScheduleFrequency.WEEKDAYS)
                NumberField(
                    if (schedule.frequency == ScheduleFrequency.WEEKLY) "요일 (월=1~금=5)"
                    else "매수일 (1~31)",
                    schedule.day,
                    editable,
                ) {
                    change(schedule.copy(day = it.toInt()))
                }
        }
        NumberField("서울 기준 시 (9~14)", schedule.hour, editable) {
            change(schedule.copy(hour = it.toInt()))
        }
        NumberField("분 (0~59, 9시는 5분부터)", schedule.minute, editable) {
            change(schedule.copy(minute = it.toInt()))
        }
        NumberField("그룹 회차 예산 (원)", schedule.budget, editable) {
            change(schedule.copy(budget = it.toLong()))
        }
        Text(
            "실행 중인 세션에서 해당 날짜·시간 이후 한 번만 실행합니다. 앱 종료/휴장으로 놓친 회차는 소급 매수하지 않습니다. 월말 주말은 다음 평일로 이동합니다.",
            fontSize = 12.sp,
            color = GroupMuted,
        )
    }
}

@Composable
private fun GroupEditor(
    initial: StrategyGroup,
    algorithm: String,
    close: () -> Unit,
    save: (StrategyGroup) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf("") }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().imePadding().fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(close) { Text("취소") }
                    Text(
                        "그룹 편집",
                        Modifier.weight(1f),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Button({
                        runCatching { save(draft) }.onFailure { error = "종목·비중 합계·예산·조건값을 확인하세요." }
                    }) {
                        Text("저장")
                    }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                    GCard {
                        OutlinedTextField(
                            draft.name,
                            { draft = draft.copy(name = it) },
                            label = { Text("그룹 이름") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Toggle("그룹 사용", draft.enabled) { draft = draft.copy(enabled = it) }
                        Text("전략도 켜져 있어야 실행 대상이 됩니다.", fontSize = 12.sp, color = GroupMuted)
                    }
                    GCard {
                        Text(
                            "그룹 종목 · 비중 합계 ${draft.symbols.sumOf { it.weightPercent }}%",
                            fontWeight = FontWeight.Bold,
                        )
                        draft.symbols.forEachIndexed { index, item ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    item.symbol,
                                    { v ->
                                        draft =
                                            draft.copy(
                                                symbols =
                                                    draft.symbols.mapIndexed { i, x ->
                                                        if (i == index) x.copy(symbol = v) else x
                                                    }
                                            )
                                    },
                                    Modifier.weight(1f),
                                    label = { Text("6자리 종목코드") },
                                    singleLine = true,
                                )
                                OutlinedTextField(
                                    item.weightPercent.toString(),
                                    { v ->
                                        draft =
                                            draft.copy(
                                                symbols =
                                                    draft.symbols.mapIndexed { i, x ->
                                                        if (i == index)
                                                            x.copy(
                                                                weightPercent = v.toIntOrNull() ?: 0
                                                            )
                                                        else x
                                                    }
                                            )
                                    },
                                    Modifier.width(80.dp),
                                    label = { Text("비중 %") },
                                    singleLine = true,
                                )
                            }
                            TextButton({
                                draft =
                                    draft.copy(
                                        symbols = draft.symbols.filterIndexed { i, _ -> i != index }
                                    )
                            }) {
                                Text("종목 제거")
                            }
                        }
                        OutlinedButton(
                            {
                                draft =
                                    draft.copy(
                                        symbols =
                                            draft.symbols +
                                                GroupSymbol(
                                                    "",
                                                    (100 - draft.symbols.sumOf { it.weightPercent })
                                                        .coerceAtLeast(1),
                                                )
                                    )
                            },
                            enabled = draft.symbols.size < 10,
                        ) {
                            Text("+ 종목 추가")
                        }
                        Text(
                            "다른 그룹과 같은 종목을 넣을 수 있습니다. 비중 합계는 100% 이하이며 나머지는 현금입니다.",
                            fontSize = 12.sp,
                            color = GroupMuted,
                        )
                    }
                    GCard {
                        Text("그룹 자금 한도", fontWeight = FontWeight.Bold)
                        NumberField("그룹 자본 (원)", draft.capital) {
                            draft = draft.copy(capital = it.toLong())
                        }
                        NumberField("한 번에 매수/매도할 예산 (원)", draft.orderBudget) {
                            draft = draft.copy(orderBudget = it.toLong())
                        }
                        NumberField("하루 매수 한도 (원)", draft.dailyBudget) {
                            draft = draft.copy(dailyBudget = it.toLong())
                        }
                        if (algorithm == "averaging") {
                            NumberField("그룹 평균가 대비 추가매수 하락률 %", draft.dropPercent) {
                                draft = draft.copy(dropPercent = it)
                            }
                            NumberField("최대 추가매수 횟수 (0~20)", draft.maxAdditionalBuys) {
                                draft = draft.copy(maxAdditionalBuys = it.toInt())
                            }
                            NumberField("그룹 목표 수익률 %", draft.takeProfitPercent) {
                                draft = draft.copy(takeProfitPercent = it)
                            }
                        } else if (algorithm == "rebalance")
                            NumberField("목표 비중 허용 오차 %p", draft.rebalanceBand) {
                                draft = draft.copy(rebalanceBand = it)
                            }
                    }
                    GCard {
                        Text("그룹 정기매수", fontWeight = FontWeight.Bold)
                        listOf(
                                ScheduleOverride.INHERIT to "전략 설정 따르기",
                                ScheduleOverride.OFF to "이 그룹은 끄기",
                                ScheduleOverride.CUSTOM to "별도 설정",
                            )
                            .forEach { (mode, label) ->
                                FilterChip(
                                    draft.scheduleOverride == mode,
                                    { draft = draft.copy(scheduleOverride = mode) },
                                    label = { Text(label) },
                                )
                            }
                    }
                    if (draft.scheduleOverride == ScheduleOverride.CUSTOM)
                        ScheduleEditor(draft.schedule, { draft = draft.copy(schedule = it) }, true)
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}
