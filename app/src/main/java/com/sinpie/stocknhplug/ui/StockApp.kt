package com.sinpie.stocknhplug.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import com.sinpie.stocknhplug.application.*
import com.sinpie.stocknhplug.domain.*
import java.text.NumberFormat
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private val Teal = Color(0xFF087F70)
private val Ink = Color(0xFF172A35)
private val Muted = Color(0xFF596D78)
private val Red = Color(0xFFB73E42)
private val Blue = Color(0xFF2464BC)

private fun won(value: Long?) =
    value?.let { NumberFormat.getNumberInstance(Locale.KOREA).format(it) + "원" } ?: "—"

private fun time(at: Instant) =
    DateTimeFormatter.ofPattern("MM.dd HH:mm:ss").withZone(SEOUL).format(at)

/** 공통 색상 정의. 매매 로직이나 전역 상태를 소유하지 않는다. */
@Composable
fun StockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            lightColorScheme(
                primary = Teal,
                onPrimary = Color.White,
                background = Color(0xFFF4F7F8),
                surface = Color.White,
                onSurface = Ink,
                onBackground = Ink,
                secondary = Teal,
            ),
        content = content,
    )
}

/** 인증 전 공개 화면. 계좌/키 상태를 표시하지 않는다. */
@Composable
fun LockScreen(unlock: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(32.dp).safeDrawingPadding(),
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Outlined.Shield, null, Modifier.size(56.dp), tint = Teal)
            Spacer(Modifier.height(28.dp))
            Text(
                "내 투자,\n내 기기 안에서.",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 42.sp,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "StockNHPlug\n기기 잠금으로 키와 투자정보를 보호합니다. 휴대폰에 PIN·패턴·비밀번호를 먼저 설정하세요.",
                color = Muted,
                lineHeight = 24.sp,
            )
            Spacer(Modifier.height(32.dp))
            Button(unlock, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("기기 인증으로 시작") }
        }
    }
}

/** 인증 후 UI 진입점. StateFlow를 구독하고 사용자 이벤트만 컨트롤러/서비스에 위임한다. */
@Composable
fun StockApp(controller: TradingController, start: () -> Unit, stop: () -> Unit) {
    val state by controller.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val titles = listOf("홈", "전략", "리서치", "보유", "기록", "로그")
    val icons =
        listOf(
            Icons.Outlined.Dashboard,
            Icons.Outlined.Tune,
            Icons.Outlined.Analytics,
            Icons.Outlined.PieChart,
            Icons.Outlined.ReceiptLong,
            Icons.Outlined.Terminal,
        )
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.ShowChart, null, tint = Teal, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("StockNHPlug", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("나의 투자 자동화 워크스페이스", fontSize = 11.sp, color = Muted)
                }
                Badge("모의투자", Teal)
                IconButton({ settings = true }) { Icon(Icons.Outlined.Settings, "보안 및 연결 설정") }
            }
        },
        bottomBar = {
            Surface(shadowElevation = 6.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding()) {
                    titles.forEachIndexed { i, title ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(icons[i], title, Modifier.size(22.dp)) },
                            label = { Text(title, fontSize = 11.sp) },
                            colors =
                                NavigationBarItemDefaults.colors(indicatorColor = Color(0xFFDFF1EC)),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Teal)
            key(tab) {
                val contentScroll = rememberScrollState()
                val uiScope = rememberCoroutineScope()
                Column(
                    Modifier.weight(1f).verticalScroll(contentScroll).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Spacer(Modifier.height(0.dp))
                    when (tab) {
                        0 ->
                            Dashboard(
                                state,
                                { settings = true },
                                { controller.refresh() },
                                { confirm = true },
                                stop,
                            )
                        1 ->
                            StrategyGroupsScreen(
                                state,
                                controller::saveBook,
                                onNavigate = { uiScope.launch { contentScroll.scrollTo(0) } },
                            ) {
                                StrategyScreen(state, controller::saveSettings)
                            }
                        2 -> ResearchScreen(state, controller::analyze)
                        3 -> HoldingsScreen(state, controller::refresh)
                        4 -> HistoryScreen(state, controller::refreshPnl)
                        5 -> LogsScreen(state)
                    }
                    Surface(color = Color(0xFFEAF0F2), shape = RoundedCornerShape(12.dp)) {
                        Text(
                            state.message,
                            Modifier.padding(14.dp),
                            fontSize = 12.sp,
                            color = Muted,
                            lineHeight = 18.sp,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (state.running)
                Button(
                    stop,
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Red),
                ) {
                    Icon(Icons.Outlined.StopCircle, null)
                    Spacer(Modifier.width(8.dp))
                    Text("자동매매 즉시 정지")
                }
        }
    }
    if (settings) SettingsDialog(state, controller, { settings = false })
    if (confirm)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("모의 자동매매 시작") },
            text = {
                Text(
                    "설정된 예산과 손절·익절 조건으로 주문합니다. 보유종목 관리에 동의하면 기존 보유종목도 매도할 수 있습니다.\n\n다른 앱 사용 중에도 알림과 함께 실행되며, 통신 장애·앱 강제 종료 시 보호 주문을 보장할 수 없습니다. 미체결 주문은 정지 후에도 증권사에서 확인하세요.\n\n수정주가·뉴스 검증 전 신규 매수는 차단됩니다."
                )
            },
            confirmButton = {
                Button({
                    confirm = false
                    start()
                }) {
                    Text("확인하고 시작")
                }
            },
            dismissButton = { TextButton({ confirm = false }) { Text("취소") } },
        )
}

@Composable
private fun Badge(text: String, color: Color = Teal) {
    Surface(color = color.copy(alpha = .10f), shape = RoundedCornerShape(6.dp)) {
        Text(
            text,
            Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

@Composable
private fun Panel(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            content()
        }
    }
}

@Composable
private fun Heading(title: String, subtitle: String) {
    Column {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color = Ink) {
    Column {
        Text(label, color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(5.dp))
        Text(value, color = color, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
    }
}

@Composable
private fun Empty(icon: ImageVector, text: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = Muted, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(12.dp))
        Text(text, color = Muted, fontSize = 13.sp)
    }
}

/** 연결·잔고·시세 준비 상태를 요약한다. 미조회 금액은 실제 0원으로 표시하지 않는다. */
@Composable
private fun Dashboard(
    s: AppState,
    connect: () -> Unit,
    refresh: () -> Unit,
    start: () -> Unit,
    stop: () -> Unit,
) {
    Heading(
        "투자의 흐름을 한눈에",
        LocalDate.now(SEOUL)
            .format(DateTimeFormatter.ofPattern("yyyy년 M월 d일 · EEEE", Locale.KOREAN)),
    )
    Surface(color = Ink, shape = RoundedCornerShape(24.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("모의계좌 순자산", color = Color(0xFFBCD4D8), modifier = Modifier.weight(1f))
                Badge(s.selected?.masked ?: "연결 전", Color(0xFF91DECB))
            }
            Text(
                won(s.portfolio?.equity),
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("평가손익", color = Color(0xFFBCD4D8), fontSize = 12.sp)
                    Text(won(s.portfolio?.unrealized), color = Color(0xFF91DECB), fontSize = 20.sp)
                }
                Column {
                    Text("예수금", color = Color(0xFFBCD4D8), fontSize = 12.sp)
                    Text(won(s.portfolio?.cash), color = Color.White, fontSize = 20.sp)
                }
            }
            Text(
                s.portfolio?.let { "마지막 동기화 ${time(it.at)}" } ?: "실제 연동 데이터만 표시합니다.",
                color = Color(0xFFBCD4D8),
                fontSize = 11.sp,
            )
        }
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Bolt, null, tint = Teal)
            Spacer(Modifier.width(8.dp))
            Text("자동매매", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Badge(if (s.running) "실행 중" else "정지", if (s.running) Teal else Muted)
        }
        Text(
            "주문당 ${won(s.settings.orderBudget)} · 최대 ${s.settings.maxPositions}종목",
            color = Muted,
            fontSize = 13.sp,
        )
        Text("평일 09:05–15:15 · 현금 지정가 IOC · 하루 종목별 1회", fontSize = 11.sp, color = Muted)
        Button(
            if (s.running) stop else start,
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            enabled =
                s.connected &&
                    s.groupExecutionReady &&
                    !s.busy &&
                    !s.storageError &&
                    s.book.groups.any { g ->
                        g.enabled && s.book.plans.any { p -> p.id == g.strategyId && p.enabled }
                    },
        ) {
            Text(if (s.running) "자동매매 정지" else "모의 자동매매 시작")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(connect, Modifier.weight(1f), enabled = !s.running) { Text("계좌 연결") }
        OutlinedButton(refresh, Modifier.weight(1f), enabled = s.connected && !s.busy) {
            Text("잔고 새로고침")
        }
    }
    Panel("오늘의 투자 체크") {
        CheckLine("계좌 연결", s.connected, "NHPlug 모의투자")
        CheckLine("그룹 체결 대사", s.groupExecutionReady, "주문번호 연결 검증 전 자동주문 잠금")
        CheckLine("실시간 가격", s.quotes.values.any { it.fresh(Instant.now()) }, "오래된 시세는 주문에 사용하지 않음")
        CheckLine(
            "매수 데이터 검증",
            s.research.any { it.buyBlockers(Instant.now()).isEmpty() },
            "수정주가 · 재무 · 공시 · 뉴스",
        )
    }
    Panel("전략 점수 상위 종목") {
        if (s.candidates.isEmpty()) Empty(Icons.Outlined.Analytics, "리서치에서 관심종목을 분석하세요.")
        else
            s.candidates.take(3).forEach { c ->
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(c.symbol, fontWeight = FontWeight.Bold)
                        Text(c.reason, fontSize = 11.sp, color = Muted)
                    }
                    Badge("${c.score}점")
                }
            }
        Text("전략 점수는 기대수익률이나 투자 권유가 아닙니다.", fontSize = 11.sp, color = Muted)
    }
}

@Composable
private fun CheckLine(title: String, ok: Boolean, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.Schedule,
            null,
            tint = if (ok) Teal else Muted,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 14.sp)
            Text(detail, fontSize = 11.sp, color = Muted)
        }
    }
}

/** 편집 중 문자열을 로컬 상태로 보관하고 저장 시 도메인 유효성 검사를 수행한다. */
@Composable
private fun StrategyScreen(s: AppState, save: (Strategy) -> Unit) {
    var order by remember(s.settings) { mutableStateOf(s.settings.orderBudget.toString()) }
    var daily by remember(s.settings) { mutableStateOf(s.settings.dailyBudget.toString()) }
    var count by remember(s.settings) { mutableStateOf(s.settings.maxPositions.toString()) }
    var session by remember(s.settings) { mutableStateOf(s.settings.maxSessionLoss.toString()) }
    var manage by remember(s.settings) { mutableStateOf(s.settings.manageHoldings) }
    var error by remember { mutableStateOf("") }
    Heading("계좌 공통 위험 한도", "모든 전략·그룹을 합쳐 최종 검사합니다.")
    Panel("투자 한도") {
        Field("계좌 전체 주문당 상한 (원)", order, { order = it }, !s.running, true)
        Field("계좌 전체 하루 매수 상한 (원)", daily, { daily = it }, !s.running, true)
        Field("계좌 전체 최대 보유 종목 수", count, { count = it }, !s.running, true)
        Field("세션 손실 한도 (원)", session, { session = it }, !s.running, true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("그룹 보유종목 자동매도 동의", Modifier.weight(1f), fontSize = 13.sp)
            Switch(manage, { manage = it }, enabled = !s.running)
        }
        Text(
            "앱 외부에서 매수한 수량은 그룹에 자동 배정하지 않습니다. 그룹별 익절·비중 조건은 그룹 편집에서 설정하세요.",
            fontSize = 12.sp,
            color = Muted,
        )
    }
    if (error.isNotEmpty()) Text(error, color = Red)
    Button(
        {
            runCatching {
                    val next =
                        s.settings.copy(
                            orderBudget = order.toLong(),
                            dailyBudget = daily.toLong(),
                            maxPositions = count.toInt(),
                            maxSessionLoss = session.toLong(),
                            manageHoldings = manage,
                        )
                    next.validate()
                    save(next)
                    error = ""
                }
                .onFailure { error = "예산·보유종목 수·손실 한도의 허용 범위를 확인하세요." }
        },
        Modifier.fillMaxWidth(),
        enabled = !s.running && !s.busy,
    ) {
        Text("공통 한도 저장")
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean = true,
    numeric: Boolean = false,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value,
        onChange,
        Modifier.fillMaxWidth(),
        label = { Text(label, fontSize = 12.sp) },
        enabled = enabled,
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions =
            KeyboardOptions(
                keyboardType =
                    if (secret) KeyboardType.Password
                    else if (numeric) KeyboardType.Decimal else KeyboardType.Text,
                autoCorrect = false,
            ),
        visualTransformation =
            if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

/** 자료 출처와 차단 이유를 표시한다. 지표 점수를 매수 승인과 혼동하지 않는다. */
@Composable
private fun ResearchScreen(s: AppState, analyze: (String, Int, String) -> Unit) {
    var mapping by rememberSaveable { mutableStateOf("") }
    var year by rememberSaveable { mutableStateOf((LocalDate.now().year - 1).toString()) }
    var code by rememberSaveable { mutableStateOf("11011") }
    Heading("매수 전, 근거부터", "허용된 공식 API 자료로만 확인합니다.")
    Panel("분석 데이터") {
        Text(
            "가격: NHPlug · 재무/공시: 금융감독원 OpenDART\n뉴스: 사용권한 확정 전 수집하지 않음",
            fontSize = 13.sp,
            color = Muted,
            lineHeight = 21.sp,
        )
        Field("종목:기업고유번호 (예: 005930:00126380)", mapping, { mapping = it }, !s.running)
        Field("재무 보고서 사업연도", year, { year = it }, !s.running, true)
        Field(
            "보고서: 연간 11011 / 반기 11012 / 1Q 11013 / 3Q 11014",
            code,
            { code = it },
            !s.running,
            true,
        )
        Button(
            { year.toIntOrNull()?.let { analyze(mapping, it, code) } },
            Modifier.fillMaxWidth(),
            enabled =
                s.connected &&
                    !s.busy &&
                    !s.running &&
                    year.toIntOrNull() != null &&
                    code in listOf("11011", "11012", "11013", "11014"),
        ) {
            Text("관심종목 분석")
        }
    }
    if (s.research.isEmpty())
        Panel { Empty(Icons.Outlined.FactCheck, "분석 결과와 매수 차단 사유가 여기에 표시됩니다.") }
    s.research.forEach { r ->
        Panel(r.symbol) {
            s.candidates
                .find { it.symbol == r.symbol }
                ?.let {
                    Text("참고 기술점수 ${it.score}점 · ${it.reason}", color = Teal, fontSize = 13.sp)
                }
            r.buyBlockers(Instant.now()).forEach { Text("• $it", color = Red, fontSize = 12.sp) }
            r.financials?.let { f ->
                Text("${f.year} / ${f.reportCode} · 연결재무", fontSize = 12.sp, color = Muted)
                Text(
                    "매출 ${f.revenue ?: "미제공"}\n영업이익률 ${f.operatingMargin ?: "미제공"}% · 부채비율 ${f.debtRatio ?: "미제공"}%",
                    fontSize = 13.sp,
                )
            }
            if (r.disclosures.isNotEmpty()) {
                HorizontalDivider()
                Text("최근 공시 · 금융감독원 DART", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                r.disclosures.take(5).forEach { d ->
                    Text(
                        "${d.date}  ${d.title}",
                        fontSize = 12.sp,
                        color = if (d.risk) Red else Muted,
                    )
                }
            }
            Text("확인 ${time(r.checkedAt)} · 과거 실적은 수익을 보장하지 않습니다.", fontSize = 11.sp, color = Muted)
        }
    }
}

/** 현재 잔고 스냅샷과 별도 실시간 가격을 구분해 표시한다. */
@Composable
private fun HoldingsScreen(s: AppState, refresh: () -> Unit) {
    Heading("보유종목", "매입가와 현재가, 평가손익을 함께 확인하세요.")
    OutlinedButton(refresh, enabled = s.connected && !s.busy) {
        Icon(Icons.Outlined.Refresh, null)
        Text(" 잔고 새로고침")
    }
    if (s.portfolio?.holdings.isNullOrEmpty())
        Panel { Empty(Icons.Outlined.PieChart, "조회된 보유종목이 없습니다.") }
    s.portfolio?.holdings?.forEach { h ->
        Panel {
            Row {
                Column(Modifier.weight(1f)) {
                    Text(h.name, fontWeight = FontWeight.Bold)
                    Text("${h.symbol} · ${h.quantity}주", color = Muted, fontSize = 12.sp)
                }
                Text(
                    won(h.pnl),
                    color = if (h.pnl >= 0) Red else Blue,
                    fontWeight = FontWeight.Bold,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("평균 매입가", won(h.average))
                Stat("잔고 기준 현재가", won(h.price))
            }
            s.quotes[h.symbol]?.let { q ->
                Text("실시간 ${won(q.price)} · ${time(q.exchangeAt)}", color = Teal, fontSize = 12.sp)
            }
        }
    }
}

/** 앱 주문, 계좌 전체 체결, 손익, 보유 이력을 각각 분리한다. */
@Composable
private fun HistoryScreen(s: AppState, pnl: () -> Unit) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    Heading("기록은 투명하게", "주문 접수와 실제 체결, 손익을 구분합니다.")
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf("앱 주문", "증권사 체결", "손익", "보유 이력").forEachIndexed { i, t ->
            FilterChip(section == i, { section = i }, label = { Text(t) })
        }
    }
    when (section) {
        0 -> {
            if (s.orders.isEmpty()) Panel { Empty(Icons.Outlined.ReceiptLong, "앱에서 보낸 주문이 없습니다.") }
            s.orders.reversed().forEach { r ->
                Panel {
                    Row {
                        Text(
                            "${r.intent.symbol} · ${if(r.intent.side==Side.BUY) "매수" else "매도"}",
                            Modifier.weight(1f),
                            fontWeight = FontWeight.Bold,
                        )
                        Badge(
                            when (r.status) {
                                OrderStatus.ACCEPTED -> "접수"
                                OrderStatus.UNKNOWN -> "미확인"
                                OrderStatus.SUBMITTING -> "전송 중"
                                OrderStatus.REJECTED -> "거절"
                            },
                            if (r.status == OrderStatus.UNKNOWN) Red else Teal,
                        )
                    }
                    Text("${r.intent.quantity}주 × ${won(r.intent.limitPrice)}", fontSize = 16.sp)
                    Text(r.intent.reason, fontSize = 12.sp, color = Muted)
                    if (r.intent.groupId.isNotBlank())
                        Text(
                            "${s.book.plans.find { it.id == r.intent.strategyId }?.name ?: r.intent.strategyId} / ${s.book.groups.find { it.id == r.intent.groupId }?.name ?: r.intent.groupId}",
                            fontSize = 12.sp,
                            color = Teal,
                        )
                    Text(
                        "${r.intent.brokerId} · •••• ${r.intent.account.takeLast(4)}",
                        fontSize = 11.sp,
                        color = Muted,
                    )
                    Text(time(r.intent.at), fontSize = 11.sp, color = Muted)
                    if (r.status == OrderStatus.UNKNOWN || r.status == OrderStatus.SUBMITTING)
                        Text("증권사에서 확인 전 재주문 금지", color = Red, fontSize = 12.sp)
                }
            }
        }
        1 -> {
            Text("당일 계좌 전체 체결입니다. 앱 외부 주문도 포함됩니다.", fontSize = 12.sp, color = Muted)
            if (s.executions.isEmpty()) Panel { Empty(Icons.Outlined.DoneAll, "조회된 체결 내역이 없습니다.") }
            s.executions.forEach { e ->
                Panel(e.name) {
                    Text("${e.side} · 체결 ${e.filled}/${e.ordered}주 · 미체결 ${e.remaining}주")
                    Text(
                        "평균체결가 ${NumberFormat.getNumberInstance().format(e.average)}원",
                        fontSize = 13.sp,
                    )
                    Text("통합주문번호 ${e.number}", fontSize = 11.sp, color = Muted)
                }
            }
        }
        2 -> {
            OutlinedButton(pnl, enabled = s.connected && !s.busy) { Text("최근 30일 손익 조회") }
            Panel("증권사 일별 손익") {
                Text(
                    won(s.pnl.takeIf { it.isNotEmpty() }?.sumOf { it.amount }),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "증권사 보고 금액 · 계좌 전체 기준\n매수수수료와 매도세금은 별도 표시하며 중복 차감하지 않습니다.",
                    fontSize = 12.sp,
                    color = Muted,
                )
                if (s.pnl.size >= 2) PnlChart(s.pnl.map { it.amount.toFloat() })
                if (s.pnl.isEmpty()) Empty(Icons.Outlined.ShowChart, "손익을 조회하면 일별 추이를 표시합니다.")
            }
            s.pnl.reversed().forEach { d ->
                Panel(d.date.toString()) {
                    Text(won(d.amount), color = if (d.amount >= 0) Red else Blue, fontSize = 20.sp)
                    Text(
                        "매수수수료 ${won(d.buyFee)} · 매도세금 ${won(d.sellTax)}",
                        fontSize = 12.sp,
                        color = Muted,
                    )
                }
            }
        }
        3 -> {
            Text("계좌별 하루 마지막 조회 잔고 · 기기 내 암호화 보관", fontSize = 12.sp, color = Muted)
            if (s.snapshots.isEmpty()) Panel { Empty(Icons.Outlined.History, "저장된 보유 이력이 없습니다.") }
            s.snapshots
                .reversed()
                .filter {
                    s.selected == null ||
                        (it.account == s.selected.number &&
                            it.brokerId == s.selected.brokerId &&
                            it.environment == s.selected.environment)
                }
                .forEach { item ->
                    Panel(time(item.portfolio.at)) {
                        Text(
                            "${item.brokerId} · ${if (item.environment == Environment.MOCK) "모의" else "운영"} •••• ${item.account.takeLast(4)} · 순자산 ${won(item.portfolio.equity)}",
                            fontSize = 12.sp,
                            color = Muted,
                        )
                        item.portfolio.holdings.forEach { h ->
                            Text("${h.name} ${h.quantity}주 · 평가손익 ${won(h.pnl)}", fontSize = 13.sp)
                        }
                    }
                }
        }
    }
}

@Composable
private fun PnlChart(values: List<Float>) {
    Canvas(Modifier.fillMaxWidth().height(100.dp)) {
        val min = values.minOrNull() ?: 0f
        val max = values.maxOrNull() ?: 1f
        val range = (max - min).coerceAtLeast(1f)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = size.height - (v - min) / range * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path,
            Teal,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()),
        )
    }
}

/** 저장된 통제 메시지를 최신순으로 표시한다. 오류 필터는 원천 저널을 수정하지 않는다. */
@Composable
private fun LogsScreen(s: AppState) {
    var filter by rememberSaveable { mutableStateOf(false) }
    Heading("실시간 실행 로그", "오류와 자동매매 판단을 시간순으로 확인하세요.")
    FilterChip(filter, { filter = !filter }, label = { Text("오류만 보기") })
    Panel {
        val events = s.events.filter { !filter || it.level == "ERROR" }
        if (events.isEmpty()) Empty(Icons.Outlined.Terminal, "표시할 로그가 없습니다.")
        events.take(100).forEach { e ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${time(e.at)}  ${e.level}", fontSize = 10.sp, color = Muted)
                Text(e.message, fontSize = 13.sp, color = if (e.level == "ERROR") Red else Ink)
                HorizontalDivider(Modifier.padding(top = 8.dp), color = Color(0xFFEDF1F3))
            }
        }
    }
}

/** 키 입력은 rememberSaveable을 쓰지 않고 저장 요청 후 비운다. 삭제는 별도 확인을 요구한다. */
@Composable
private fun SettingsDialog(s: AppState, c: TradingController, close: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var dart by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("연결 및 보안") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "키는 Android Keystore로 암호화해 기기 내부에만 저장합니다. 인증할 때만 NHPlug·OpenDART 공식 서버로 전송합니다.",
                    fontSize = 12.sp,
                    color = Muted,
                )
                Text(
                    "본인 명의 API 사용신청과 약관 동의가 필요합니다. 이 앱은 NH투자증권의 공식 앱이 아닙니다.",
                    fontSize = 12.sp,
                    color = Muted,
                )
                Field("NHPlug 앱키", key, { key = it }, !s.running, secret = true)
                Field("NHPlug secret", secret, { secret = it }, !s.running, secret = true)
                Field("OpenDART 인증키 (선택)", dart, { dart = it }, !s.running, secret = true)
                Button(
                    {
                        c.saveCredentials(key, secret, dart)
                        key = ""
                        secret = ""
                        dart = ""
                    },
                    Modifier.fillMaxWidth(),
                    enabled = !s.running && !s.busy && key.isNotBlank() && secret.isNotBlank(),
                ) {
                    Text("암호화 저장")
                }
                OutlinedButton(
                    { c.connect() },
                    Modifier.fillMaxWidth(),
                    enabled = s.hasCredentials && !s.running && !s.busy,
                ) {
                    Text("NHPlug 모의계좌 연결")
                }
                s.accounts.forEach { a ->
                    FilterChip(
                        s.selected == a,
                        { c.select(a) },
                        enabled = !s.running,
                        label = { Text("모의 ${a.masked}") },
                    )
                }
                HorizontalDivider()
                Text("개인정보 처리 안내", fontWeight = FontWeight.Bold)
                Text(
                    "개발자 서버·광고·분석 SDK가 없습니다. 인증키, 주문, 로그, 일별 보유 이력(최근 365개)은 암호화 저장합니다. 실시간 가격과 리서치는 메모리에서 표시합니다. Android 백업과 기기 이전을 차단합니다. 삭제는 이 기기의 정보만 지우며 증권사 주문을 취소하지 않습니다.",
                    fontSize = 12.sp,
                    color = Muted,
                    lineHeight = 19.sp,
                )
                Text("실거래는 출시 검증 전 잠겨 있습니다. 모의투자도 수익을 보장하지 않습니다.", fontSize = 12.sp, color = Red)
                TextButton({ delete = true }, enabled = !s.running && !s.busy) {
                    Text("키 및 기기 내 전체 기록 삭제", color = Red)
                }
            }
        },
        confirmButton = { TextButton(close) { Text("닫기") } },
    )
    if (delete)
        AlertDialog(
            onDismissRequest = { delete = false },
            title = { Text("기기 내 데이터를 삭제할까요?") },
            text = {
                Text("키, 설정, 주문 기록, 로그를 삭제합니다. 미확인 주문이 있다면 먼저 증권사에서 확인하세요. 증권사 계좌와 주문은 삭제되지 않습니다.")
            },
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
