# 계층과 클래스 상세

## 실제 키 조회 진단과 인증 요청

`NhEndpoints.base`는 공식 명세에서 확인한 currentPrice/period만 운영 시세 서버로 지정합니다. 나머지 경로는 선택 환경을 유지합니다. 서비스/전략이 URL을 선택하거나 오류에 따라 주문 서버를 바꾸지 않습니다.

`NhTransport.historyWindow`는 일봉의 명시적 최근 N개(1~250) 요청입니다. `NhHistoryWindow.complete`가 해당 period 경로에서 요청 개수를 모두 받았는지 판단합니다. 모두 받은 경우 더 오래된 자료의 continuation은 현재 창을 불완전하게 만들지 않습니다. 개수가 부족한 연속 페이지는 실패하며, 잔고/체결 등 다른 경로에는 이 예외를 허용하지 않습니다. `NhPriceHistoryProvider`는 최대 250개 원본 봉을 정렬해 반환하고 수정주가 표시는 하지 않습니다.

`NhAuthentication.request`는 인증 URL/빈 바이트 본문/Content-Type을 구성하는 NH infrastructure 내부 헬퍼입니다. `NhTransport.tokenLocked`가 호출하며 캐시·암호화·호출 직렬화 책임은 기존과 같습니다. 포트 및 AppContainer 조립은 변경하지 않습니다. `NhAuthenticationTest`는 MockWebServer에서 실제 전송 헤더와 빈 본문을 검사합니다.

`CredentialProbeTest`: opt-in/빈 disposable 저장소 확인 → 일회성 암호문 수신 → SecureVault → 앱 NH/OpenDART 어댑터 인증·조회 → 비식별 단계 상태 → 키/파일 정리. 생산 UI/서비스/주문 진입점을 호출하지 않으며 일반 테스트에서는 건너뜁니다. 개별 probe 상태로 결과를 판단하고 JUnit 종료를 모든 외부 API 성공으로 해석하지 않습니다.

## 빌드·테스트 배포 책임

`app/build.gradle.kts`의 `previewRelease` 속성은 release 변형의 ID/표시 이름만 테스트용으로 격리합니다. 응용·매매·실행 계층과 실거래 잠금은 그대로입니다. `scripts/package_test_apks.py.main`은 이미 빌드된 APK를 검사 → 외부 테스트 keystore로 서명 → 서명 검증 → SHA-256/검증 JSON 생성 순서로 처리합니다. 키 생성·Gradle 실행·GitHub 업로드는 수행하지 않습니다. 설치/키 입력/제한은 [TESTING.md](TESTING.md)에 정리합니다.

기준: 2026-09-19 / Kotlin / Android 26 이상, target 36.

진입점부터 함수 호출을 따라가는 설명과 흐름도는 [CODE_FLOW.md](CODE_FLOW.md)를 참조합니다.

## 전략 그룹 실행의 현재 진입점

현재 사용자 자동매매는 TradingController.startSession → HybridPriceMonitor / GroupTradingCoordinator.tick → GroupAlgorithm/추천/정기매수 → ExecutionGate → 파킹 자금 조정 → TradingEngine.submit 경로입니다. 기존 TechnicalStrategy/SignalEngine 점수는 참고 분석과 호환 계약으로 남기며 실제 기본 정책은 AveragingDownAlgorithm과 RebalancingAlgorithm입니다. 전략/그룹 모델과 함수별 상세는 [GROUP_STRATEGIES.md](GROUP_STRATEGIES.md)를 참조합니다.

GroupAlgorithmRegistry는 AppContainer에서 주입합니다. GroupExecutionSource는 확정 체결과 앱 주문의 대응 포트이며 NHPlug에서는 아직 null입니다. GroupLedger가 누적 체결을 그룹별로 재생하고 GroupCodec/EncryptedAppStorage가 설정과 대사를 암호화 보관합니다. AppState에 book/groupPositions/groupExecutionReady/groupAlgorithms를 추가했습니다. 전략 변경은 saveBook에서 검증하며 기록이 있는 그룹의 삭제/소유권 이동을 금지합니다.

## 0. 조립·교체 계약

`AppContainer`는 Android applicationContext를 소유하는 의존성 조립 지점입니다. `MultiAccountController`와 계좌별 `TradingController`를 생성하고 Activity/Service에 동일 `TradingWorkspace`를 제공합니다. 구현 교체 방법과 제약은 [EXTENDING.md](EXTENDING.md)에 정리합니다.

- `ApplicationStorage` → `EncryptedAppStorage`: 응용 저장 포트와 Keystore/LocalStore 어댑터. 키 원문 조회는 응용/UI에 노출하지 않습니다.
- `SessionFactory` → `BrokerSession`: Broker, MarketStream, ResearchRepository를 연결마다 생성합니다. 컨트롤러가 NH/DART 객체를 직접 만들지 않습니다.
- `TradingStrategy` → `TechnicalStrategy`: 후보 평가·희망 매수 예산·청산 신호. `ExitPolicy`는 엔진에서 사용하는 좁은 청산 인터페이스이며 기본 구현은 `ThresholdExitPolicy`입니다.
- `PriceHistoryProvider`, `CorporateResearchProvider`, `NewsProvider`: 연구 데이터의 독립 교체 단위입니다. ResearchRepository는 주문 Broker나 제공자 구현을 참조하지 않습니다.
- `scripts/check_architecture.py`: CI에서 의존성 방향을 검사합니다. 현재 Gradle 모듈은 하나이며 패키지/포트 수준 분리입니다.

## 1. 응용 계층 (`application`)

### `TradingController`
앱 프로세스 내 단일 세션 조정자입니다. `StateFlow<AppState>`로 UI에 불변 스냅샷을 전달하며, REST 필드나 호가 산술은 직접 구현하지 않습니다. 기기 인증 후 사용자가 연결/분석/시작을 선택해야 동작합니다.

- `connect`: `SessionFactory`로 구현체 조립 → 정규화된 모의계좌 목록 검사 → 선택 계좌 잔고 및 체결 동기화 → 실시간 스트림 연결.
- `saveCredentials`: 키·secret·OpenDART 키를 기기 저장소에 암호화하고 기존 토큰과 연결을 무효화합니다. UI는 저장 후 입력 문자열을 비웁니다.
- `saveSettings`: `Strategy.validate` 후 설정을 암호화 저장. 실행 중 편집 금지.
- `analyze`: 관심종목별 가격·재무·공시 근거를 수집하고 지표를 계산합니다. 매수 가능 여부는 점수와 별개로 `ResearchEvidence.buyBlockers`가 결정합니다.
- `startSession`: 검증된 잔고를 기준으로 세션 시작. 약 15초 단위 잔고/체결 대사와 약 1초 단위 추적/GroupTradingCoordinator 평가를 수행합니다. 그룹 체결 대사 제공자가 없으면 시작 자체를 차단합니다. 사용자 정지 또는 안전 오류까지 수행하며 앱 자체의 시간 만료는 없습니다. 최근 앱 목록 제거로 정지하지 않지만 재부팅/프로세스 종료 후 자동 재시작하지 않습니다.
- `stop`: 매매 계층 정지 플래그를 먼저 내리고 루프를 취소합니다. 이미 접수된 주문 취소 기능은 아닙니다.
- `refreshPnl`: 증권사 최근 30일 일별 손익을 별도 조회합니다. 앱 주문 손익으로 오표시하지 않습니다.
- 오류 메시지는 통제된 문구만 로그로 전달합니다. HTTP URL/본문/예외 원문을 사용자 로그에 기록하지 않습니다.
- `task`는 실행 중 수동 조회/편집을 받지 않아 자동 loop가 동기화를 소유합니다. `select`는 잔고와 실시간 구독을 함께 다시 구성하고 `startSession`은 미확인 저널이 있으면 시작 자체를 거절합니다.

### `AppState`
설정, 계좌 목록, 선택 계좌, 잔고, 실시간 가격, 분석 근거, 앱 주문, 증권사 체결, 손익, 보유 스냅샷, 이벤트, 연결/실행/보안 오류 상태를 포함합니다. 키·secret은 포함하지 않습니다. 공시·리서치·실시간 가격은 메모리 전용이며 앱 재시작 시 다시 조회합니다. 잔고는 계좌/환경/일자별 마지막 조회 스냅샷을 암호화 보관합니다.

### `SignalEngine`
완료된 일봉만 사용해 SMA20/60, Wilder RSI14, Wilder ATR14, 20일 모멘텀, 상대 거래량으로 설명 가능한 0~100 점수를 계산합니다. 시세 미완성/중복일자/비유한 수치/부족한 봉 수/오래된 자료를 제외합니다. 매수 주문을 직접 보내지 않습니다.

### `TradingService` (`platform`)
상태 관찰 Job을 하나만 유지하며 반복 시작 intent가 관찰자를 누적하지 않습니다. foreground 전환 예외는 세션 정지 경로로 처리합니다.

사용자가 화면에서 시작하는 Android foreground service입니다. `specialUse` 용도는 가격 감시와 조건부 증권 주문 실행이며 정지 액션을 가진 지속 알림을 제공합니다. Play Console의 용도 선언과 심사가 필요합니다. `START_NOT_STICKY`, `stopWithTask=false`로 프로세스 복구 시 자동 주문하지 않습니다. 다른 앱 사용 중에도 세션이 유지되지만 OS 강제종료·절전·네트워크 지연에 대한 실행 보장은 없습니다.

## 2. 매매 계층 (`trading`)

### `TradingEngine`
NHPlug 전문과 독립적인 주문 상태·위험 관리 계층입니다. `Broker`, `OrderJournal`, `ExitPolicy`, 주입 가능한 시계를 받으므로 JVM에서 오류 시나리오를 검증할 수 있습니다.

- `exitReason`: 보유 평균가, 세션 내 최고가, 현재 체결가를 추적하여 손절/익절/추적손절 신호를 생성합니다. 세션 재시작 시 고점은 초기화됩니다. 유효 시세와 고점을 교체 가능한 ExitPolicy에 전달하되 주문 위험 검사는 엔진이 유지합니다.
- `submit`: Mutex로 직렬화. 실행 상태 → 계좌 환경 → 서울 시간 정규 세션 → 실시간 시세 15초 → 잔고 60초 → 세션 손실 → 호가 스프레드 1% → 미확인 주문 → 그룹/종목/방향/일자 중복 → 예산/보유수/가능수량 순서로 검사합니다.
- 매수는 매도 1호가, 매도는 매수 1호가 기준 지정가 IOC입니다. 무제한 시장가 추격이나 자동 정정은 하지 않습니다. 가격 추적은 **진입/청산 판단**이고, 접수 이후 재호가 알고리즘은 아닙니다.
- 주문 가능 수량은 증권사에서 재조회합니다. 매수는 현금 가능수량만 사용하고 1% 현금 완충을 적용합니다.
- 전송 직전 `SUBMITTING`을 암호화 파일에 원자적으로 기록합니다. 응답에 주문번호가 있으면 `ACCEPTED`; 전송/결과 기록 예외는 먼저 엔진을 정지한 뒤 `UNKNOWN` 저장을 시도합니다. 저장 자체가 실패해 `SUBMITTING`이 남아도 이후 주문을 차단합니다. HTTP 타임아웃에도 자동 재전송하지 않습니다.
- `ACCEPTED`는 체결이 아닙니다. 시장 주문번호와 통합 주문번호의 대응을 추측하지 않습니다. 체결은 증권사 조회로 별도 표시합니다. 미확인 주문을 임의로 해결 처리하는 UI는 없습니다.

## 3. 실제 매매 계층 (`execution`)

### `NhTransport` (`infrastructure/nh`)
공식 호스트만 호출하는 OkHttp 전송기입니다. 모의 업무는 `moapi.nhplug.com:8443`, 인증은 명세대로 운영 인증 서버를 사용합니다. 토큰을 만료시각과 함께 암호화 캐시하며 401에서만 캐시를 무효화합니다. 429를 토큰 오류로 해석하지 않습니다. 호출은 250ms 이상 간격, 자동 리다이렉트 및 연결 재시도 금지, 기본 플랫폼 TLS 검증을 유지합니다. `Input_0`와 연속조회 `cts`/`cts_flag`를 처리하며 동일 키 반복/100페이지 이상/누락 키는 실패로 종료합니다.

### `ResponseGuard` (`infrastructure/nh`)
HTTP 성공과 업무 성공을 구분합니다. 오류 문구/심각도와 응답 블록을 검사하며 단일 성공코드를 가정하지 않습니다. 문서화되지 않은 빈 응답은 실패로 닫습니다. 실제 계좌에서 다양한 정상/오류 응답 fixtures를 추가 확보해야 합니다. 서버 자유문구 판정은 제한적이므로 출시 전 코드별 의미 확인이 필요합니다.

### `NhBroker`
`Broker` 구현. 계좌목록·잔고·현금매수/매도 가능수량·주문·당일체결·일별손익의 필드 매핑을 소유합니다. 잔고는 연속조회를 모두 마친 후 집계값을 읽습니다. 조회에서 동일 종목의 여러 잔고는 표시용으로 합산하되 자동매도는 현금 매도 가능수량으로 제한합니다. 신용·공매도·해외주식·파생은 주문하지 않습니다. 기본 구성의 실거래는 `BuildConfig.LIVE_TRADING_ENABLED=false`입니다.

### `NhSocket`
운영 `7070/websocket`의 공식 시세 `oc`만 사용합니다. header ACK와 body.tr_key를 확인한 종목만 전달합니다. generation으로 과거 연결을 차단하고 차등 등록/해제합니다. 단절 시 신선한 REST로 복귀하고 재연결합니다. 계좌 체결은 모의 REST로 대사합니다. 감시 합집합 앱 한도 100개, 동시 WS 앱 한도 10개이며 초과 근접 종목은 REST입니다.

## 4. 데이터·리서치 계층 (`research`)

### `SourceRegistry` / `SourcePolicy`
공식 API 개인 이용, 별도 계약 필요, 비활성 상태를 명시합니다. 조건 승인 전 `newsLicensed`/`adjusted`를 true로 승격하지 않습니다. 실제 승인 근거는 `DATA_SOURCES.md`에 유지합니다.

### `NhPriceHistoryProvider` (`marketdata`)

가격이력 전용 포트의 NHPlug 구현입니다. 기존 NhBroker의 period 조회를 옮겨 주문 API와 연구 입력을 분리했습니다. `PriceHistory`에 NHPlug 출처와 adjusted=false를 명시하며 동일 공유 전송기를 사용해 호출 간격/인증/공식 호스트 제한을 유지합니다.

### `PriceHistory` / `CorporateAction` / `AdjustedPriceEngine`
원천·기준시각·수정 여부를 보존합니다. 변환기는 검증된 권리락일과 가격/거래량 계수를 받아 그 이전 봉에만 적용하며 평가 시점 이후 알려진 이벤트를 배제합니다. 액면분할과 배당을 가격 점프로 추정하지 않습니다. NHPlug 공개 `period` 명세의 수정주가 보장 여부가 미확정이므로 현재 연결 결과는 `adjusted=false`입니다. 계약된 계수 공급자 연결은 미완료이며 변환기만 구현·테스트되어 있습니다.

### `DartClient` (`research/provider`)
공식 `list.json`과 `fnlttSinglAcntAll.json`을 호출합니다. 사용자 개인 인증키 사용, 8자리 기업 고유번호, 보고서 사업연도/코드, CFS 구분을 명시합니다. 연결재무 미제공 시 별도재무를 같은 데이터처럼 대체하지 않습니다. 표준 XBRL 계정 ID로 매출·영업이익·자본·부채를 읽고 영업이익률·부채비율을 계산합니다. 값 누락을 0으로 바꾸지 않습니다. 공시는 페이지를 끝까지 조회하고 수신번호로 중복 제거합니다. 제목 위험어는 검토 플래그이며 사실 판정이나 감성 예측이 아닙니다.

### `NewsProvider` / `UnlicensedNewsProvider`
합법적인 뉴스 공급원을 교체할 수 있는 포트입니다. 현재 구현은 네트워크 수집을 하지 않으며 이용권한 미확정 상태를 유지합니다. 빈 뉴스 목록을 무위험으로 간주하지 않습니다. 자동매매 분석/저장 권리를 확보한 공급자와 계약한 후 어댑터를 추가해야 합니다.

### `ResearchRepository` / `ResearchEvidence`
가격·재무·공시·뉴스를 동일 종목의 검증 근거로 묶습니다. `buyBlockers`는 수정주가·재무 누락·비양수 자본/영업이익·위험 공시·뉴스 권리·30분 신선도를 검사합니다. 현재 허용되지 않은 출처 때문에 신규 자동매수가 차단되는 것은 의도된 동작입니다. 사용자가 입력하는 종목-기업고유번호 매핑은 출시 전에 공식 기업 마스터 자동 검증으로 강화해야 합니다.

## 5. 도메인과 로컬 보안 (`domain`, `data`)

- `Strategy`: 관심종목, 예산, 보유수, 손절/익절/추적, 세션 손실 한도, 최소 점수, 보유관리 동의. 입력 유효성은 UI와 주문 직전 양쪽에서 검사합니다.
- `Quote`: 수신시각·거래소 시각·정규장 구분. 미래 시각과 15초 이상 지연을 거절합니다.
- `Portfolio`, `Holding`, `DailyPnl`, `Execution`: 잔고 평가, 일별 계좌 손익, 체결을 서로 구분합니다.
- `Account`: 증권사 ID/환경/계좌번호를 정규화합니다. NH 계좌 타입 코드는 어댑터 내부에만 둡니다.
- `OrderIntent`, `OrderRecord`, `OrderStatus`: 앱 주문 저널. 앱 UUID와 증권사 시장주문번호를 별개로 저장합니다. brokerId를 저장해 같은 계좌번호의 다른 증권사 주문을 섞지 않습니다.
- `SecureVault`: Android Keystore AES-256-GCM, 무작위 96비트 IV, 파일명 AAD, 버전 1 바이너리, AtomicFile. 키·토큰·설정·저널·로그 모두 앱 전용 noBackupFilesDir에 암호화됩니다.
- `LocalStore`: 원자적 주문 예약·상태 변경·설정 보관. 앱 이벤트는 500개 순환 저장, 저널은 사용자 삭제 전 유지. 보유 스냅샷은 증권사/계좌/환경/일자별 최신 값으로 교체하며 사용자 삭제 전까지 암호화 보관합니다. 암호문 손상은 거래 잠금으로 처리합니다.

## 6. 화면 (`ui`, `MainActivity`)

`MainActivity`는 기기 잠금 인증, FLAG_SECURE, 오버레이 숨김, 가려진 터치 차단, 알림 권한 요청을 담당합니다. 백그라운드 이동 후 재진입하면 다시 인증합니다. `StockApp`은 5개 작업 공간과 설정 대화상자로 기능을 분리합니다. `StockTheme`, `Panel`, `Badge`, `Field`는 일관된 색상·여백·입력 형태를 제공합니다. 숫자가 없으면 0이나 예시 데이터를 만들지 않고 `—`/빈 상태를 표시합니다.

## 파킹 현금 정책 (2026-09-20)
`StrategyBook.parking` → `ParkingSettingsCard` 편집 → `TradingController.saveBook` 암호화 저장 순서입니다. `ParkingPolicy`는 사용자 전략그룹 밖의 예약 소유권을 가지며 `ledgerGroups()`로 체결 대사·수량 검증에 포함합니다. `GroupTradingCoordinator`는 적격 전략 매수의 자금 부족 시 `ParkingPlanner`의 매도 제안을 실행하고 즉시 반환합니다. 다음 회차의 종료 체결·잔고 확인 전 주식 매수는 없습니다. 남는 현금 매수는 모든 전략 판단 뒤 수행합니다. [클래스 및 자금 흐름](PARKING.md)을 참고하세요.

## 적응형 추적 클래스 (2026-09-20)
`AppContainer`가 `NamuExecutionGate`와 세션의 `NhCurrentPriceProvider`를 주입합니다. `ExecutionGate`는 매매 판단 포트, `CurrentPriceProvider`는 시장 데이터 포트입니다. `MarketRules`와 `PriceSnapshot`은 검증 메타데이터/출처를 소유합니다. `HybridQuotePolicy`가 거리/단조 시각을 계산하고 `HybridPriceMonitor`가 전송을 조정합니다. UI 상태는 `AppState.tracking/quoteRoutes`, 시세·분석 화면의 타겟은 읽기 전용입니다. 정지 시 가상 추적 상태를 비우며 실제 주문 저널과 분리합니다. 함수별 입출력·수식·흐름은 [PRICE_TRACKING.md](PRICE_TRACKING.md)에 상세히 기록했습니다.

## 사용성/전송 전 재검사 (2026-09-20)
`ui/Workspace.kt`에 WorkspaceNavigation, MarketWorkspace, AssetsWorkspace, ActivityWorkspace, PriceTrackingScreen, StatusBanner를 분리했습니다. UI는 상태와 콜백만 받으며 `StockApp`이 컨트롤러를 연결합니다. `TradingController.refreshPrice`는 설정/보유 종목과 거래시간을 확인한 뒤 `HybridPriceMonitor.refresh`로 한 종목을 조회합니다. 서비스나 주문 시작을 호출하지 않습니다.
`PriceSnapshot.valid`는 체결가와 양방향 호가의 당일 상하한·신선도를 함께 검증합니다. monitor/gate/policy는 이전 거래소 시각 메시지로 되돌아가지 않습니다. `TradingEngine.validateDispatch`는 가능수량 조회 후와 영속 예약 후에 장중 여부·시세·잔고·정지를 재검사합니다. Broker 호출 전 검사 실패는 REJECTED/정지, Broker 호출 이후 불명확한 결과는 UNKNOWN/정지입니다.

`OrderIntent.expiresAt`는 생성 후 5초, 원래 수신/거래소 시각+15초, 잔고 시각+60초 중 가장 이른 시각입니다. `dispatchable`은 미래 주문·정규시간·정확한 만료 경계를 검사합니다. NhBroker의 실제 HTTP dispatchGuard에서도 이 원래 기한을 사용해 인증/호출 대기로 수명이 늘어나지 않습니다. LocalStore는 암호화 저널에 expiresAt을 저장하며 이전 파일은 at+5초로 읽습니다. 이전/미확인 저널 재전송 기능은 추가하지 않습니다.
# 2026-09-20 추적 이력 저장 및 독립 타겟

`TargetRequest`는 전략 ID·그룹 ID·발생 회차를 명시한다. `GroupTradingCoordinator`는 날짜 대신 전략/그룹/종목/방향/회차/주문 회수로 키를 구성하고 미완료 키를 재사용한다. 동일 종목의 가격 수신은 공유하되 `NamuExecutionGate.Entry`의 극값·최고/최저·기한은 타겟별로 분리한다.

새 도메인 포트 `TrackingStore`와 값 `TrackingRecord`가 추적 이력 저장을 담당한다. `EncryptedTrackingStore`는 이를 `SecureVault` 위에 구현하고, `AppContainer`에서만 gate에 주입한다. `activate(account, environment)`는 현재 범위의 이력을 불러오며 `clear()`는 메모리를 해제한다. `retain()`은 감시 해제된 이력을 비활성 보존한다. 시세 캐시·ready·이전 트리거는 복원하지 않는다. 상세 정책은 [가격 추적](PRICE_TRACKING.md).
# 2026-09-20 화면 파생 지표와 비동기 소유권

`AccountAnalytics`는 읽기 전용 `HoldingMetric`, 호가 스프레드와 비율을 계산한다. UI는 이 응용 함수를 사용하고 매매 레이어 계산은 변경하지 않는다. `FinancialPanels.kt`가 비중/호가/실제 종가 차트, `SettingsScreen.kt`가 계좌·시세·보안 편집을 소유한다.

`TradingController`는 actionJob, generation, connectionId로 요청·콜백 수명을 소유한다. AppState의 operation/progress/messageError/pnlLoadedAt은 비밀값 없이 조회 상태를 표현한다. dispatcher와 IO dispatcher 주입은 실제 지연·취소 순서를 테스트하기 위한 구성 지점이다. `HybridPriceMonitor.clear`는 generation을 변경해 이전 REST 결과 반영을 막는다. [상세 흐름](ASYNC_REVIEW.md).


## 요청 25: 계좌 런타임과 이력

`AppContainer.controller`는 이제 `TradingWorkspace` 포트의 `MultiAccountController`다. root `TradingController(discoveryOnly=true)`는 계좌 목록/공유 키만 처리한다. `AccountRuntimeFactory`는 계좌별 `TradingController(boundAccount=...)`와 전용 vault/게이트를 생성한다. MainActivity/TradingService는 포트를 공유하고 서비스는 fleetRunning을 관찰한다. `AccountRun`은 표시용 enable/연결/실행/처리 상태다. 계좌 선택은 기존 런타임을 재연결하지 않는다.

`AccountDirectory`/`EncryptedAccountDirectory`는 활성 대상 목록, `AccountHistoryStore`/`EncryptedAccountHistory`는 `AccountDay` 날짜별 관측 기록, `HistoryAnalytics`는 UI용 순수 집계를 소유한다. `AccountMigration`은 구 공용 저장소에서 식별 가능한 기록만 계좌별 복사한다. `SecureVault(context, account)`는 namespace를 AAD와 디렉터리에 적용한다. `EncryptedAppStorage`도 주문·스냅샷의 계좌 일치를 검사한다. 구체적인 함수 흐름·계산·이전 규칙은 [ACCOUNT_HISTORY.md](ACCOUNT_HISTORY.md).

## 요청 26: 내보내기와 운용 점검

`CreateHistoryDocument`는 platform의 ActivityResult 계약이다. MIME/파일명을 유지하고 CATEGORY_OPENABLE을 추가하여 스트림 파일 생성을 요청한다. MainActivity의 CSV/ZIP launcher 모두 이 계약을 사용한다.

`HistoryExport`는 application의 순수 스냅샷/CSV·ZIP 직렬화 클래스다. `HistoryAnalytics`로 집계하며 Android URI나 실행 API를 참조하지 않는다. `HistoryExportModel`은 platform의 ViewModel로 파일 선택 중 메모리 요청과 IO 저장 상태를 소유한다. MainActivity가 CreateDocument 결과를 전달하고 StockApp/AssetsWorkspace/HistoryScreen은 내보내기 콜백만 전달한다. `OperationReview`는 계좌 필터링·당일 원장 예약액·상태 점검을 계산하며 `OperationPanel`은 이를 표시한다. 주문 정책의 소유권은 변경하지 않는다. 클래스별 흐름/파일 명세는 [EXPORT_AND_OPERATIONS.md](EXPORT_AND_OPERATIONS.md).
