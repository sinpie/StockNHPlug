package com.sinpie.stocknhplug.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sinpie.stocknhplug.application.AppState
import com.sinpie.stocknhplug.domain.*
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.delay

/** 정보 구조의 최상위: 현황 / 설정 / 판단 근거 / 자산 / 실행 결과. 아이콘 설명은 텍스트와 중복하지 않는다. */
@Composable
fun WorkspaceNavigation(selected: Int, select: (Int) -> Unit) {
    val titles = listOf("홈", "전략", "시세", "자산", "내역")
    val icons =
        listOf(
            Icons.Outlined.Dashboard,
            Icons.Outlined.Tune,
            Icons.Outlined.Analytics,
            Icons.Outlined.PieChart,
            Icons.Outlined.History,
        )
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        titles.forEachIndexed { i, title ->
            NavigationBarItem(
                selected == i,
                { select(i) },
                icon = { Icon(icons[i], null) },
                label = { Text(title, fontSize = 11.sp) },
            )
        }
    }
}

/** 마지막 응답을 긴 목록 아래에 숨기지 않는다. 펼침 상태는 새 메시지마다 초기화한다. */
@Composable
internal fun StatusBanner(message: String, error: Boolean = false) {
    var expanded by remember(message) { mutableStateOf(false) }
    Surface(
        color = if (error) Red.copy(alpha = .06f) else MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text(
                message,
                fontSize = 12.sp,
                color = if (error) Red else Muted,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (message.length > 50)
                TextButton({ expanded = !expanded }) { Text(if (expanded) "접기" else "상태 자세히") }
        }
    }
}

/** 화면에 머무르는 동안에도 가격 만료 표시가 바뀐다. 네트워크 조회/주문은 수행하지 않는다. */
@Composable
internal fun rememberDisplayTime(): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = Instant.now()
        }
    }
    return now
}

@Composable
internal fun SectionTabs(labels: List<String>, selected: Int, select: (Int) -> Unit) {
    TabRow(
        selectedTabIndex = selected,
        containerColor = MaterialTheme.colorScheme.background,
        divider = {},
    ) {
        labels.forEachIndexed { i, label ->
            Tab(
                selected == i,
                { select(i) },
                text = {
                    Text(
                        label,
                        fontWeight = if (selected == i) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                selectedContentColor = Teal,
                unselectedContentColor = Muted,
            )
        }
    }
}

/** 시세와 전략 판단의 입력을 한 공간에 둔다. 실행 로그나 계좌 손익과 섞지 않는다. */
@Composable
fun MarketWorkspace(
    s: AppState,
    analyze: (String, Int, String) -> Unit,
    refresh: (String) -> Unit,
) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    Heading("관심종목", "현재가와 매매 타겟, 분석 근거를 확인하세요.")
    SectionTabs(listOf("현재가", "분석"), section) { section = it }
    if (section == 1) ResearchScreen(s, analyze) else PriceTrackingScreen(s, refresh)
}

/** 종목 검색과 그룹별 타겟을 제공한다. 같은 종목의 시세는 공유하지만 타겟은 합치지 않는다. */
@Composable
fun PriceTrackingScreen(s: AppState, refresh: (String) -> Unit) {
    val now = rememberDisplayTime()
    var search by rememberSaveable { mutableStateOf("") }
    var visibleCount by remember(search) { mutableIntStateOf(20) }
    val configured =
        s.book
            .ledgerGroups()
            .flatMap { it.symbols.map { row -> row.symbol } }
            .ifEmpty { s.settings.symbols }
    val symbols =
        (configured +
                s.quotes.keys +
                s.tracking.map { it.symbol } +
                s.portfolio?.holdings.orEmpty().map { it.symbol })
            .distinct()
            .sorted()
    Text(
        if (s.running) "자동 감시 중 · 15초를 넘긴 호가로 주문하지 않습니다." else "자동매매 정지 · 종목별 조회 버튼으로 시세를 확인하세요.",
        fontSize = 12.sp,
        color = Muted,
    )
    OutlinedTextField(
        search,
        { search = it.take(40) },
        label = { Text("종목코드·종목명·그룹 검색") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    val filtered =
        symbols.filter { symbol ->
            val groups = s.book.groups.filter { g -> g.symbols.any { it.symbol == symbol } }
            search.isBlank() ||
                symbol.contains(search.trim()) ||
                s.portfolio?.holdings.orEmpty().any {
                    it.symbol == symbol && it.name.contains(search.trim(), true)
                } ||
                groups.any { it.name.contains(search.trim(), true) }
        }
    if (filtered.isEmpty()) Panel { Text("검색 결과가 없습니다.") }
    filtered.take(visibleCount).forEach { symbol ->
        val name = s.portfolio?.holdings?.find { it.symbol == symbol }?.name
        val quote = s.quotes[symbol]
        val fresh = quote?.fresh(now) == true
        val targets = s.tracking.filter { it.symbol == symbol }
        val route = s.quoteRoutes.find { it.symbol == symbol }
        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(name ?: symbol, fontWeight = FontWeight.Bold)
                    if (name != null) Text(symbol, fontSize = 11.sp, color = Muted)
                }
                Badge(
                    if (quote == null) "미조회" else if (fresh) "최신" else "지연",
                    if (fresh) Teal else Muted,
                )
            }
            Text(won(quote?.price), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (quote != null) {
                QuoteBook(quote)
                Text(
                    "거래소 ${time(quote.exchangeAt)} · ${if (quote.regular) "정규장" else "장 상태 확인 필요"}",
                    fontSize = 11.sp,
                    color = Muted,
                )
            }
            if (route != null)
                Text(
                    "${if (s.running) route.mode else "감시 정지"} · 거리 ${route.distance?.let { String.format(Locale.KOREA, "%.2f%%", it) } ?: "타겟 대기"}",
                    fontSize = 12.sp,
                    color = Muted,
                )
            if (!s.running)
                OutlinedButton(
                    { refresh(symbol) },
                    enabled = s.connected && !s.busy && !s.storageError,
                ) {
                    Text("$symbol 조회")
                }
            if (targets.isEmpty()) Text("등록된 매매 타겟 없음", color = Muted, fontSize = 12.sp)
            targets.forEach { target ->
                val group = s.book.groups.find { target.key.startsWith(it.id + "|") }
                HorizontalDivider()
                Text(
                    "${group?.name ?: "전략"} · ${if (target.side == Side.BUY) "매수" else "매도"}",
                    fontWeight = FontWeight.SemiBold,
                )
                Text("전략 제시가 ${won(target.strategyPrice)}", fontSize = 12.sp)
                Text(
                    "추적 최저 ${won(target.minimum)} · 추적 최고 ${won(target.maximum)}",
                    fontSize = 12.sp,
                )
                Text(
                    "${if (target.side == Side.BUY) "추적 저점" else "추적 고점"} ${won(target.extreme)} · 실제 트리거 ${target.trigger?.let { String.format(Locale.KOREA, "%,.1f원", it) } ?: "대기"}",
                    fontSize = 12.sp,
                )
                Text(
                    if (!fresh) "시세 재확인 대기" else target.message,
                    color = if (fresh) Teal else Muted,
                    fontSize = 12.sp,
                )
            }
        }
    }
    if (filtered.size > visibleCount)
        OutlinedButton({ visibleCount += 20 }, Modifier.fillMaxWidth()) {
            Text("시세 20개 더 보기 (${visibleCount}/${filtered.size})")
        }
}

@Composable
fun AssetsWorkspace(s: AppState, refresh: () -> Unit, pnl: () -> Unit) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    Heading("자산", "${s.selected?.masked ?: "계좌 연결 전"} · 보유 현황과 계좌 손익")
    SectionTabs(listOf("잔고", "손익", "이력"), section) { section = it }
    when (section) {
        0 -> HoldingsScreen(s, refresh)
        1 -> HistoryScreen(s, pnl, 2)
        2 -> HistoryScreen(s, pnl, 3)
    }
}

@Composable
fun ActivityWorkspace(s: AppState) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    var allAccounts by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    Heading("거래 내역", "주문 접수와 실제 체결을 구분해 확인하세요.")
    SectionTabs(listOf("주문", "체결", "로그"), section) { section = it }
    if (section == 0) {
        if (s.selected != null)
            FilterChip(allAccounts, { allAccounts = !allAccounts }, label = { Text("전체 계좌") })
        val account = s.selected
        val rows =
            if (allAccounts || account == null) s.orders
            else
                s.orders.filter {
                    it.intent.account == account.number &&
                        it.intent.brokerId == account.brokerId &&
                        it.intent.environment == account.environment
                }
        Text(
            if (allAccounts || account == null) "기기에 기록된 전체 계좌 주문"
            else "선택 계좌 ${account.masked}의 주문",
            color = Muted,
            fontSize = 12.sp,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("전체", "확인 필요", "접수", "거절").forEachIndexed { i, label ->
                FilterChip(filter == i, { filter = i }, label = { Text(label) })
            }
        }
        OutlinedTextField(
            query,
            { query = it.take(40) },
            Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("주문 종목 검색") },
        )
        val filtered =
            rows.filter { row ->
                (query.isBlank() || row.intent.symbol.contains(query.trim())) &&
                    when (filter) {
                        1 -> row.status in setOf(OrderStatus.UNKNOWN, OrderStatus.SUBMITTING)
                        2 -> row.status == OrderStatus.ACCEPTED
                        3 -> row.status == OrderStatus.REJECTED
                        else -> true
                    }
            }
        Text("${filtered.size}건 · 접수는 체결 완료가 아닙니다.", fontSize = 11.sp, color = Muted)
        if (rows.isNotEmpty() && filtered.isEmpty()) Panel { Text("조건에 맞는 주문이 없습니다.") }
        else HistoryScreen(s.copy(orders = filtered), {}, 0)
    } else if (section == 1) HistoryScreen(s, {}, 1) else LogsScreen(s)
}
