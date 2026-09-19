# 검증 결과

기준일: 2026-09-19. 결과는 실행한 범위만 기록하며 버그 없음·출시 가능·전략 수익성을 의미하지 않습니다.

## 환경

Windows, Android Studio JBR, Gradle 8.13, AGP 8.12.2, Kotlin 1.9.0, Android SDK 36. Android Studio JBR의 `jlink`를 사용합니다. 로컬 SDK 경로는 커밋하지 않습니다. Gradle 배포 ZIP은 공식 SHA-256을 wrapper에 고정했습니다.

## 실행 결과

### 지속 실행 변경 (2026-09-20)

앱 자체 6시간 만료/최근 앱 목록 제거 시 정지 제거와 시스템 timeout 처리 후 `testDebugUnitTest lintDebug assembleDebug bundleRelease`가 성공했습니다. JVM 52개와 출처·계층 검사 통과. 이번 변경의 실제 서비스 장시간 실행/화면 꺼짐/최근 앱 제거/Doze/Android 15+ timeout 기기 검증은 미실행이며, 이전 Android 9개 통과를 새 수명주기 검증으로 간주하지 않습니다. NH 그룹 자동주문은 기존 검증 게이트로 잠겨 있습니다. NamuMagic 참조가 필요한 적응형 추적은 아직 미구현이며 [추적 요구사항](PRICE_TRACKING.md)에 구분했습니다.

| 검증 | 결과 | 근거 |
|---|---|---|
| Debug APK | 빌드 성공 | `app/build/outputs/apk/debug/app-debug.apk` |
| Release AAB | 빌드 성공, 업로드 서명 미설정 | `app/build/outputs/bundle/release/app-release.aab` |
| JVM unit tests | 52개 통과, 실패/오류 0 | `app/build/reports/tests/testDebugUnitTest/index.html` |
| Android Lint | 오류 0, 라이브러리/Gradle 최신 버전 알림 경고 존재 | `app/build/reports/lint-results-debug.html` |
| 출처/로그 정적 검사 | 통과 | `python scripts/audit_sources.py` |
| Android instrumentation | 9개 통과, 실패 0 | Android 11 / API 30 x86_64: Keystore 2개, UI 2개, LocalStore/설정 저장 5개 |
| 실제 NHPlug 모의 계좌 | 미실행 | 사용자 기기 키·모의계좌 필요 |
| 실제 자금 주문 | 미실행, 잠금 | 기본 구성은 모의 계좌만 제공 |
| Play 심사 | 미제출 | RELEASE.md의 게이트 미완료 |

## JVM 테스트 범위

### 전략 그룹 확장 최종 검증 (2026-09-19)

구현 커밋 `a78d2cc2e9ff11d67448be1dfccbe91595bb1dcd`의 [GitHub Actions 35450060400](https://github.com/sinpie/StockNHPlug/actions/runs/35450060400)이 성공했습니다. Ubuntu/JDK 17에서 단위 테스트·린트·Debug APK·Release AAB·출처 검사·계층 의존성 검사를 통과했습니다. 아래 Android 9개는 로컬 API 30 결과이며 CI 기기 테스트 결과가 아닙니다. Actions 버전/Node 런타임 폐기 예정 경고는 남아 있습니다. 이 결과를 추가한 문서 전용 커밋은 CI를 재실행하지 않습니다.

`testDebugUnitTest lintDebug assembleDebug bundleRelease connectedDebugAndroidTest` 최종 실행 성공. JVM 52개, API 30 Android 9개 통과, 린트 오류 0/기존 버전 경고 12개입니다. 추가 검증은 동일 종목 그룹별 원가·수량·손익 격리, 누적 부분체결 중복 방지, 후퇴/알 수 없는 체결/초과 매도 거절, 정기매수 회차·서울 시각·월말/주말 처리, 5개 프리셋과 설정 직렬화, 일정 상속/끄기, 물타기/리밸런싱 계산, 다른 그룹 동일 종목 주문, 그룹 보유량 매도 상한, 대사 제공자 미연결 잠금, 정기매수의 데이터 게이트 유지입니다.

Android에서는 그룹 설정의 암호화 복원, 전략 관리/지표 조합/정기매수 화면 이동을 추가 확인했습니다. 초기에 API 31 전용 BigInteger 변환으로 린트가 실패해 범위 검증 후 호환 변환으로 수정했습니다. UI 테스트의 중첩 가로/세로 스크롤 대상 오류를 진단해 실제 세로 부모를 먼저 스크롤하도록 수정했고 앱의 전략/탭 전환 시 상단 이동도 추가했습니다. 실패를 통과로 표시하지 않고 수정 후 전체 테스트를 재실행했습니다.

그룹 주문번호-체결번호 대사, 실제 모의계좌 동시 그룹 매매, 장기 일정 실행은 미검증입니다. NHPlug GroupExecutionSource 미구현으로 실제 그룹 자동주문은 잠겨 있습니다. 테스트에서는 명시적인 합성 자료와 가짜 Broker만 사용하며 이 결과가 실제 체결 검증을 대체하지 않습니다.

- 결과 저널 저장 실패 후에도 정지, 실행 중 세션 손실 기준 재설정 거절.
- 15초/60초를 초과하는 1나노초 경계 및 미래 잔고 차단.
- 다른 종목과 비정규장 가격의 청산 신호 차단.

- 주문 사전 영속 예약 확인, 동시에 들어오는 10개 신호의 중복 주문 방지.
- 통신 타임아웃 후 UNKNOWN 전환·정지·다음 주문 차단.
- 거래소 시각 지연/미래 시세, 운영계좌를 모의 환경에서 사용, 주말 주문 차단.
- 세션 손실 한도, 대기 매수의 보유 한도 반영, 기존 보유 매도 동의.
- 손절/익절 판단, RSI 평탄/상승 데이터, 부족·중복·NaN 데이터.
- 당일 미완성 봉 제외, 기업행위 수정계수의 기준일/공개시점 처리.
- 수정주가·뉴스 미확정 상태는 매수 가능으로 승격되지 않음.
- HTTP 200 업무실패, 빈 정상 조회/상태 누락 구분, 단일 성공코드 가정 금지.
- 공식 WebSocket 예제 파싱 및 지연 거절.

## 미검증 또는 제한된 범위

2026-09-19 재검증: `testDebugUnitTest lintDebug assembleDebug bundleRelease connectedDebugAndroidTest`를 로컬 실행했습니다. 폐기 가능한 API 30 에뮬레이터에서 미확인 주문 복원/중복 예약 거절, 잘못된 저널 배열 실패, 동일 날짜 계좌/환경별 스냅샷 구분을 확인했습니다. LocalStore 테스트는 별도 임시 디렉터리를 사용하며 사용자 파일이나 공유 Keystore 키를 삭제하지 않습니다. 린트는 오류 0, 의존성 버전 관련 경고 12개입니다. 컴파일러의 기존 아이콘 deprecated 및 중복 초기화 알림도 남아 있습니다.

CI 최초 실행 `35446104910`은 Android SDK에서 제거된 `tools` 패키지를 요구해 컴파일 전 실패했습니다. setup-android 패키지를 `platform-tools`로 명시해 수정했습니다. 수정 커밋 fbdc973의 [원격 재실행 35447302547](https://github.com/sinpie/StockNHPlug/actions/runs/35447302547)은 성공했습니다.

API/전략 교체 구조 변경 후 JVM 테스트 32개를 통과했습니다. 교체한 가격 제공자의 출처 보존/종목 오류/통신 실패, 교체한 청산 정책의 공통 위험 검사, 다른 증권사 계좌 차단/미확인 주문 이름공간 분리를 포함합니다. 의존성 경계 검사와 출처 정적 검사도 통과했습니다. 새 API의 실제 통합 테스트를 수행했다는 의미는 아닙니다.

최종 로컬 명령 `testDebugUnitTest lintDebug assembleDebug bundleRelease connectedDebugAndroidTest`는 성공했습니다. Android 테스트 7개 모두 통과했으며, 새 brokerId의 암호화 저장/복원과 기존 NHPlug 저널의 이름공간 호환을 추가 확인했습니다. 변경된 AppContainer를 사용하는 UI 테스트도 통과했습니다.

API/전략 교체 구현 커밋 `7571725f172df4d8725d719d1a2ce3a49b5d5c6b`의 [GitHub Actions 35448094156](https://github.com/sinpie/StockNHPlug/actions/runs/35448094156)도 성공했습니다. Ubuntu/JDK 17에서 단위 테스트·린트·Debug APK·Release AAB·출처 검사·계층 의존성 검사가 통과했습니다. Android 기기 테스트 7개는 이 CI가 아니라 위 로컬 API 30 에뮬레이터에서 실행한 결과입니다. 이후 검증 기록만 추가한 문서 커밋은 CI를 재실행하지 않습니다.

실물 기기/다른 OS별 Keystore, 생체/기기 인증, API 31+ 오버레이 차단, API 34+ foreground specialUse, 제조사 절전, 실제 장중 API/WS, 부분체결/정정/취소/미확인 주문 대사, 휴장·시스템 시계 조작, 실제 토큰 재발급, 장기 실행, 거래비용 포함 전략 성과는 별도 검증이 필요합니다. UI 테스트는 인증 우회가 없는 별도 테스트 호스트에서 실행했으며 production MainActivity의 인증 성공을 검증한 것은 아닙니다. Google Play 정책 준수의 최종 확인과 NH/DART/뉴스 제공자의 배포·데이터 권한 검토는 자동 테스트로 대체할 수 없습니다.

의존성 버전은 빌드 호환성이 확인된 조합으로 고정했습니다. 최신 버전 경고를 숨기지 않았고 최신 버전이라는 주장을 하지 않습니다. 출시 전 보안 공지와 의존성 업그레이드를 검토해야 합니다.
