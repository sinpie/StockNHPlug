# 테스트 배포 안내

2026-09-20 추가: 실제 키 시험에서 NH 인증 Content-Type 및 일봉 서버 선택 오류를 발견해 수정했습니다. 이전 `0.1.0-preview.1` 로컬 APK에는 이 수정이 없습니다. 현재 소스로 다시 빌드한 APK와 VERIFICATION.md의 최신 결과를 기준으로 판단하세요.

실제 키 통합 진단은 `CredentialProbeTest`를 명시적으로 선택한 disposable Debug 설치에서만 수행합니다. 기본 Android 테스트는 이 진단을 건너뛰며 CI에는 실제 키를 전달하지 않습니다. 기기 일회성 RSA-OAEP SHA-256/MGF1-SHA-256 공개키를 ADB로 읽고, `4-byte big-endian RSA ciphertext length + RSA-wrapped AES-256 key + 12-byte IV + AES-GCM ciphertext/tag`를 ADB stdin으로 캐시에 전달합니다. AAD는 `StockNHPlug read-only probe v1`입니다. 완성된 암호문 파일을 원자적으로 rename한 뒤에만 테스트가 읽습니다. 호스트 원본 키 경로나 복호화 도구는 공개 저장소에 넣지 않습니다. 테스트 APK/인자를 실제 사용자의 설치에 적용하지 마세요. 정상 결과와 제한은 VERIFICATION.md에 기록했습니다.

현재 전체 자동매매가 완료된 제품이 아닙니다. 실제 NH 계좌의 그룹 주문·체결 대사, 수정주가 검증 및 뉴스 이용권한이 준비되지 않아 자동주문을 잠가 두었습니다. 배포본 준비는 공개 완료 또는 Play 출시 승인을 의미하지 않습니다. 공개 여부는 GitHub Releases와 VERIFICATION.md를 확인하세요.

## APK 선택과 설치

- `…-release.apk`: release 빌드 유형의 R8 최적화·비디버그 테스트 배포본. **StockNHPlug Preview**, applicationId `com.sinpie.stocknhplug.preview`. 일반 사용성 확인용입니다.
- `…-debug.apk`: 디버깅 가능한 **StockNHPlug Debug**, applicationId `com.sinpie.stocknhplug.debug`. 개발자 테스트용입니다.
- 두 앱은 저장 공간과 기기 Keystore가 별개여서 키·설정이 공유되지 않습니다. 정식 패키지 `com.sinpie.stocknhplug`와도 구분합니다.
- Android 8 이상, 기기 PIN/패턴/비밀번호가 필요합니다. 설치 허용은 다운로드에 사용한 앱에만 필요합니다. 접근성·연락처·SMS·전체 저장소 권한은 필요하지 않습니다.
- SHA256SUMS.txt와 APK 해시를 비교합니다. 동일 패키지 업데이트는 기존 서명과 호환되어야 합니다. 서명이 다르다고 실제 사용자의 앱 데이터를 삭제해서는 안 됩니다.

## 키 설정과 확인 순서

1. 기기 잠금을 설정하고 앱을 열어 기기 인증을 통과합니다.
2. 상단 설정 → **연결 및 보안**에서 NHPlug 앱키·secret, 필요하면 OpenDART API 키를 입력하고 저장합니다. NH 모의투자에 맞는 발급 키를 사용합니다. 시세 조회는 운영 시세 API를 사용하므로 해당 권한도 확인합니다.
3. 키는 휴대폰 내부 Keystore 기반 암호화 파일에 보관하고 공식 발급 API 인증에만 전송합니다. 키를 GitHub나 개발자에게 보내지 마세요. 저장 후 입력란은 비워집니다.
4. 홈 → 자동매매 → 시세·분석 → 자산 → 활동을 확인합니다. 키 변경 후 재연결하고 API 인증/계좌/조회는 자신의 권한으로 확인합니다. 로컬 검증은 실제 계좌 조회 성공을 의미하지 않습니다.
5. 전략·그룹·종목, 추천 조건, 정기매수, 파킹 설정을 편집하고 재실행 후 저장 여부를 확인합니다. 같은 종목이라도 그룹별 설정을 구분합니다.
6. 장중 연결 상태에서 설정/보유 종목의 시세를 수동 조회합니다. 조회만으로 주문을 만들지 않습니다. 계좌 자료가 없으면 보유·손익·체결을 샘플로 채우지 않습니다.
7. 앱 재진입 시 재인증을 확인합니다. 오류 보고에는 키·토큰·계좌번호를 포함하지 않습니다.

현재 뉴스 공급자 키 입력란은 없습니다. 이용권한이 검증된 계약·어댑터가 먼저 필요합니다. OpenDART 키만으로 뉴스/수정주가 조건을 충족하지 않습니다.

## 검증 범위

| 범위 | 상태 |
|---|---|
| 화면 이동·검색·필터·전략/그룹/파킹 설정 | 자동 UI 테스트와 에뮬레이터 검증 대상 |
| 암호화 저장·저널·전략 계산·추적 경계 | JVM/Android 테스트. 실제 결과는 VERIFICATION 참조 |
| NH 인증·계좌·시세·OpenDART 조회 | 어댑터 구현, 유효한 키/권한으로 별도 통합 검증 필요 |
| 그룹 자동매매·정기매수·파킹 자동주문 | 실제 체결 대사 어댑터/근거 미완료로 시작 차단 |
| 실제 주문·체결·실현손익 정확성 | 실제 NH 계좌 시험 미실행 |
| 무중단 실행·Play 출시 | OS 제한/심사/계약 등 출시 게이트 미완료 |

## 개발자 패키징

1. `./gradlew testDebugUnitTest lintDebug assembleDebug bundleRelease assembleRelease -PpreviewRelease=true` 실행. 주문 잠금은 이 속성과 무관하게 유지됩니다.
2. 저장소 밖의 별도 테스트 keystore와 별칭을 준비합니다. 정식 Play 서명 키를 사용하지 않습니다.
3. JAVA_HOME, STOCKNHPLUG_TEST_STORE_PASSWORD, STOCKNHPLUG_TEST_KEY_PASSWORD 환경 변수를 로컬 보안 수단으로 설정합니다. 비밀번호를 명령행 인자나 로그에 넣지 않습니다.
4. `python scripts/package_test_apks.py --keystore <외부 경로> --alias <별칭> --build-tools <SDK build-tools 경로> --output <새 출력 경로>` 실행. 패키지 ID/디버그 여부/서명을 검사하고 해시·검증 JSON을 생성합니다. 기존 산출물 덮어쓰기는 거절합니다.
5. disposable 기기에 Release APK를 설치해 시작/인증을 확인하고 Android 테스트를 별도로 실행합니다. debug 계측 테스트만으로 R8 실행 전체가 검증되지는 않습니다.
6. 검증 결과와 제한을 기록한 뒤 해당 범위의 배포 조건이 충족될 때만 GitHub 프리릴리즈에 APK 2개, SHA256SUMS.txt, APK-VERIFICATION.json을 첨부합니다. keystore/비밀번호/API 데이터는 첨부하지 않습니다.

속성을 생략하면 원래 production ID의 서명되지 않은 release 산출물을 생성합니다. Preview AAB는 Play 제출용이 아닙니다. 정식 서명·버전 정책은 RELEASE.md의 별도 게이트입니다.
# 2026-09-20 추적 변경 재현 항목

- `PriceTrackingTest`, `TrackingContinuityTest`: 9.99/10/10.01% 경계, 거리별 간격, OFF 최대 10초 예약, 상위 제시가 제한, 다음 날 재확인, 다중 그룹의 독립 극값, 재연결 복원과 계좌·환경 격리.
- `HybridMonitorTest`: 초기/원거리 빈 소켓 미연결, 근접 전환·ACK, OFF REST만으로 반전 트리거, 모든 타겟 범위 밖 중단, 저장 오류가 시세 재시도로 흡수되지 않음.
- `GroupCoordinatorTest`: 미완료 정기매수의 원래 occurrence·제시가·극값을 다음 날 유지하고 전송 전 SUBMITTING 저널 확인. 같은 종목의 서로 다른 전략/그룹별 타겟 분리. 가짜 증권 포트만 사용.
- `LocalStoreInstrumentedTest`, `TrackingOptionsUiTest`: disposable Android 설치에서 실제 암호화 저장/복원·기존 설정 기본 ON 이관·OFF 유지·스위치 조작·실행 중 편집 잠금.

Android 테스트는 실사용 설치에서 실행하지 않는다. 실제 실행 결과와 건수는 [VERIFICATION.md](VERIFICATION.md)를 확인한다. 10초는 예약 간격 검증이며 OS/통신 지연까지 포함한 수신 보장 테스트가 아니다.
# 요청 24 회귀 검증

- `ControllerAsyncTest`: 지연 dispatcher에서 연속 탭 하나만 입장, 시작 전 취소, 비협력 제공자의 정지 후 계좌/잔고 응답, 이전 세션 콜백, 부분 조회 실패, timeout, 빈 손익/미조회 구분 및 키 교체 후 계좌 메타데이터 초기화.
- `AccountAnalyticsTest`: 보고 손익 기반 수익률, 주식 내 비중, 원가 0/분모 0 미제공, 큰 금액 오버플로 회피, 역전/미제공 호가 스프레드 차단.
- `HybridMonitorTest`: clear 이후 REST 응답으로 시세·추적 상태가 되살아나지 않음.
- `FinancialUiTest`, `SettingsUiTest`: 비중/수익률 정렬, 자동운용 중 조회 비활성, 계좌/시세/보안 탭, 저장 실패 초안 유지/성공 확인 후 닫힘.
- `UiInstrumentedTest`, `WorkspaceInstrumentedTest`: 새 메뉴명, 페이지 복귀 시 선택 탭, 계좌/주문 필터, 타겟 분리와 지연 시세 표시.

화면 자료는 연결 전 UI 또는 명시적인 합성 계좌/시세만 사용한다. 기본/130% 글꼴 확대 검증은 별도 UI 실행으로 수행하며 실제 결과만 VERIFICATION.md에 기록한다.


## 요청 25 검증 시나리오

`HistoryAnalyticsTest`: 달/연도 경계, 미조회/0 구분, 큰 금액 합계, 입금과 손익 분리. `MultiAccountTest`: 여러 enable 동시 시작, 사전검증 실패의 부분 시작 금지, 선택 후 지연 데이터 격리, enable 저장 실패, 타 계좌 요청 중 키/삭제 차단. `ControllerAsyncTest`: boundAccount 외 소유권 전환/접근 거절. `AccountHistoryInstrumentedTest`: 계좌별 Keystore 파일/AAD, 반복 누적 체결 교체, 날짜/계좌별 같은 주문번호, 과거 손익 보존, 미확인 주문 이전. `HistoryUiTest`: 일/월/연 통계·상세·계좌 선택. 실제 수행 결과는 VERIFICATION.md이며 테스트 정의 자체는 통과 증거가 아니다.
# 요청 26 테스트 경계

`HistoryExportTest`: CSV 한글/BOM/음수/빈칸, ZIP 항목, 수식 주입 문자열·앞자리 0, 전체 계좌/주문번호 제외, 스냅샷 복사, 누적 체결·큰 금액, 출력 실패·취소·빈/중복 날짜. `OperationReviewTest`: 계좌/날짜/거절별 예약액, 미확인 주문, 60초 경계/미래 시각/누락/저장·대사 잠금. MultiAccountTest는 UI를 거치지 않는 공유 저장 오류 시작 차단도 검사한다.

`HistoryExportAndroidTest`: 메모리 출력 스트림을 주입하여 Android IO dispatcher/close 성공·실패, 선택 취소/프로세스 사망에 해당하는 요청 없음, 중복 준비, content URI 제한 및 시스템 문서 생성 Intent 계약을 검사한다. 이 테스트는 실제 DocumentsUI 저장 클릭, 클라우드 제공자의 파일 저장, 프로세스 강제 종료/회전 전체 시나리오의 증거가 아니다. `ExportUiTest`는 선택 연도/기간 CSV 요청과 중복 비활성, 운용 점검 펼침을 합성 자료로 확인한다. 기본/130% 글꼴 캡처는 실제 계좌 자료가 아니다. 실행 결과는 VERIFICATION.md에 기록한다.
# 조합 테스트 1,000개

[설계·실행법](COMBINATION_TESTING.md) · [C0001~C1000 전체 입력·기대 결과](COMBINATION_CASES.md).
`./gradlew testDebugUnitTest`에 자동 포함된다. 별도로 `python scripts/generate_combination_cases.py --check`와 `python scripts/check_combination_results.py`를 실행하여 문서/리소스 일치 및 모든 ID의 실제 실행을 확인한다. 이 1,000개는 JVM 합성 케이스이며 실제 기기·증권사 테스트가 아니다.
