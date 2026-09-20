# 검증 결과

## 2026-09-20 · 요청 25 계좌 분리·기간 통계

- `testDebugUnitTest lintDebug assembleDebug bundleRelease connectedDebugAndroidTest`: 성공. JVM 112개 실패/오류 0, Android API 30 XML 25개 중 24개 통과·실제 키 opt-in 1개 의도적 제외. 콘솔의 중복 종료 개수 대신 XML 고유 testcase를 확인했다.
- 계좌별 설정과 암호문 AAD 격리, 같은 날짜 누적 체결 교체, 계좌/연도가 다른 같은 주문번호, 과거 손익 유지, 미확인 주문 이전, UI 기간/상세/계좌 전환을 검사했다. 여러 계좌 동시 시작·부분 시작 금지·요청 중 전역 키/삭제 차단은 가짜 runtime 및 JVM 회귀로 확인했다. 실제 증권사 다중 주문 검증이 아니다.
- 초기 Kotlin Long/Int 동등 비교 컴파일 오류를 수정했다. 계층·출처·문서 링크 검사 통과. 린트 오류 0/버전 경고 14. 실제 키·주문은 사용하지 않았으며 다중 NH 소켓/장중 주문·장시간 백그라운드는 여전히 미검증이다.
- 계좌 고정 UI 명령 회귀를 추가한 최종 JVM 113개 통과. 통계·계좌 선택 UI는 기본/130% 글꼴 각각 2개 통과했다. [기본 통계 화면](images/history-statistics.png), [130% 통계 화면](images/history-statistics-130.png)의 금액·탭·줄바꿈을 육안 확인했다. 합성 fixture 표식을 포함한 UI 시험 호스트이며 production 화면 보호를 해제하지 않았다. 시험 설치 제거·기존 글꼴/adb 권한 복원 성공.
- 최종 계좌 명령 고정 보완 이후 전체 Gradle 명령 재실행 성공: JVM 113개/Android 24개 통과, Debug APK·Release AAB·lintDebug·출처/계층/문서 검사 통과. 기존 실거래·그룹 체결 대사·연구 게이트를 유지했다.

## 2026-09-20 · 요청 24 UX·비동기 인터페이스

- 구현 `9ee728a`의 [GitHub Actions 35508100911](https://github.com/sinpie/StockNHPlug/actions/runs/35508100911) 성공: Ubuntu/JDK 17에서 JVM·린트·Debug APK·Release AAB·출처·계층 검사 및 산출물 업로드 통과. Node 20 actions의 Node 24 강제 전환, setup-java v4 폐기 예정, ubuntu-latest 전환 안내는 남아 있다. Android 기기 시험은 아래 로컬 결과이며 CI에서 실행한 것으로 표시하지 않는다.

- `testDebugUnitTest lintDebug assembleDebug bundleRelease connectedDebugAndroidTest`: 성공. JVM 105개 실패/오류 0. Android 11/API 30 XML 20개 중 19개 통과, 실제 키 opt-in 진단 1개 의도적 제외. 새 키/주문/취소 API 호출 없음.
- 린트 오류 0, 버전 관련 경고 14개. Debug APK와 Release AAB 빌드 성공. production AAB 빌드를 Play 발행/서명 검증 완료로 간주하지 않는다.
- 추가 회귀: 연속 탭, 시작 전 취소, 취소를 무시하는 계좌/잔고 응답, 이전 WebSocket 콜백, clear 뒤 REST 응답, 부분 조회 실패의 이전 스냅샷 보존, 60초 timeout, 정상 빈 손익·키 교체 초기화. 평가액/수익률/비중/스프레드의 0분모·큰 값도 검사했다.
- Android에서 탭 이동, 계좌 필터, 저장 실패 시 파킹 초안 보존/성공 후 닫힘, 설정 탭, WebSocket 기본 ON, 자동운용 중 수동 조회 비활성을 검증했다. 처음 버튼 앞 공백 때문에 이름 매칭 1건 실패했고 제품 라벨을 수정한 뒤 전체 시험이 통과했다.
- 확대 글꼴 추가 시험 중 내역의 빈 안내가 첫 화면 밖에 있어 1건 실패했다. 안내로 실제 스크롤한 뒤 표시를 확인하도록 시험을 보완했다. 재빌드 성공 후 기본/130% 글꼴 UI 시나리오 각각 7개 통과. 홈·시세·자산·설정 캡처를 갱신하고 기본 홈/자산/설정 및 확대 시세/자산/설정의 줄바꿈·탭·금액 배치를 육안 확인했다. 전체 기기/200% 확대 검증은 아니다.
- 캡처는 별도 UI 시험 호스트이고 자산/시세 fixture에는 합성 데이터 표식을 넣었다. production 캡처 차단을 해제하지 않았다. 첫 캡처 전달의 Android 저장소 권한 오류를 disposable 에뮬레이터에서 해결했다. 최종 unroot 전송 종료 오류 후 UID 2000·글꼴 1.0·시험 APK 제거를 직접 재확인했고 기존 preview 설치는 유지했다.
- 계층 검사·출처 감사·Markdown 링크·diff 공백 검사 통과. 실제 장중 가격 추적, 장시간 백그라운드/Doze, 실제 주문·체결 대사, Google Play 출시 게이트는 이번 UI 시험으로 검증하지 않았다.

## 2026-09-20 · 사용자 제공 키의 Android 통합 시험

구현 커밋 `e6a5ec5`의 [GitHub Actions 35499203697](https://github.com/sinpie/StockNHPlug/actions/runs/35499203697) 성공: Ubuntu/JDK 17의 JVM·린트·Debug APK·Release AAB·출처·계층 검사 통과. CI에는 실제 키를 전달하지 않았습니다. 기존 Actions Node 20/setup-java v4 폐기 예정 경고는 남아 있습니다.

실제 키를 사용하라는 명시적 요청에 따라 API 30 disposable Debug 설치에서 앱의 SecureVault/NhTransport/NhBroker/NhSocket/NhPriceHistoryProvider/DartClient를 호출했습니다. 생산 UI 전체 조작 시험 또는 주문 시험이 아닙니다. 원본 설정은 읽기만 했고 복호화 값/토큰/계좌/시세 응답을 파일이나 출력에 남기지 않았습니다. 호스트에서 메모리 복호화 → 기기 일회성 공개키로 암호문 전달 → 기기 Keystore 암호화 저장 → 읽기 전용 시험 → 시험 저장소/키 정리 → 새로 설치한 테스트 APK 제거를 수행했습니다. 서버 토큰 폐기는 수행하지 않았습니다.

| 최종 단계 | 실제 결과 |
|---|---|
| NHPlug·OpenDART 키 복호화 | 성공, 값 출력 없음 |
| 기기 내 암호화 저장/읽기 | 성공 |
| NHPlug 토큰 인증 | 성공 |
| 모의계좌 목록·잔고·당일 체결 조회·최근 손익 조회 | 응답 처리 성공. 실제 주문/체결 생성·그룹 대사를 검증한 것은 아님 |
| 현재가 REST 응답 | 종목 일치 응답 수신 성공 |
| 현재가의 매매용 가격/호가/신선도 검증 | 거절됨. 휴장일 시험이며 장중 시세 추적 성공으로 간주하지 않음 |
| 일봉 요청 창 | 성공, 비어 있지 않은 원본 이력 확인. adjusted=false 유지 |
| 시세 WebSocket | 연결 및 요청한 1종목 구독 ACK 성공. 장중 틱 수신/재접속/장시간 실행 미검증 |
| OpenDART 공시·2025 연간 연결재무 | 조회 성공, 재무 보고서 존재 확인. 외부 수치 대조/추천 수익성 검증은 아님 |
| 키/토큰 정리 | 성공, disposable 앱 제거 |

발견·수정한 결함:

1. NH 토큰 요청: OkHttp의 빈 문자열 본문이 `charset=utf-8`을 추가해 HTTP 403 발생. PC 비교에서 charset 포함 403/미포함 200 재현. 빈 바이트 본문으로 수정한 뒤 Android 인증 성공.
2. 일봉 period: 공식 명세의 운영 전용 API를 모의 서버에 보내던 경로 오류 수정. 주문/계좌는 모의 경로 유지.
3. 일봉 250개를 모두 받아도 더 오래된 자료의 continuation 때문에 불완전 조회로 거절. 일봉 요청 창 완결만 별도로 허용하고 계좌/체결 완결 검사는 유지.

초기 진단 도구의 공개키 파일 생성 대기/출력 파이프 처리와 JUnit void 반환형 문제를 수정한 뒤 실제 키 전달을 진행했습니다. 실패 시도들을 API 성공으로 포함하지 않습니다. 외부 API 결과는 개별 probe 상태로 기록하며 JUnit 프로세스 종료만으로 성공 처리하지 않습니다.

`testDebugUnitTest lintDebug assembleDebug bundleRelease assembleDebugAndroidTest`: 성공. JVM 85개 실패 0, 린트 오류 0/기존 버전 경고 12개. 출처·계층·문서 검사 결과는 최종 확인에 추가합니다. 실제 주문, 그룹 체결 대사 어댑터, 수정주가/뉴스 이용권한, Play 출시 게이트는 여전히 미완료입니다.

최종 일반 Android 회귀 시험: XML 기준 13개 중 12개 통과, 실제 키 opt-in 진단 1개 의도적 건너뜀, 실패/오류 0. Gradle 콘솔의 종료 개수 표시와 별개로 XML의 고유 testcase를 확인했습니다. 실제 키 진단은 위 표의 별도 실행 결과입니다. 출처 허용 목록·계층 경계·Markdown 링크·git diff 공백 검사 통과.

## 2026-09-20 · APK 테스트 배포 준비

- 구현 커밋 `344ed2daa38e059a8a67872d0a45fc7231c8c169`의 [GitHub Actions 35476929222](https://github.com/sinpie/StockNHPlug/actions/runs/35476929222): 성공. Ubuntu/JDK 17에서 JVM·린트·Debug APK·Release AAB·출처·계층 검사 통과. Actions Node 20/setup-java v4 폐기 예정 경고가 남아 있으며 실패로 숨기거나 보안 검토 완료로 간주하지 않습니다.
- 패키징 도구에 기본 production ID APK를 넣는 거절 시험 통과: 서명/출력 디렉터리 생성 전에 중단. Preview Release의 `zipalign -c -P 16 4` 검사 통과.

- `testDebugUnitTest lintDebug assembleDebug bundleRelease assembleRelease -PpreviewRelease=true`: 성공. JVM 82개 실패 0, 린트 오류 0/기존 버전 관련 경고 12개. Release는 R8 최적화이며 주문 잠금 유지.
- 속성을 생략한 `bundleRelease assembleRelease`도 별도 성공했습니다. 기본 production ID 산출물은 서명되지 않았고 배포하지 않습니다.
- API 30 disposable 에뮬레이터에서 `connectedDebugAndroidTest -PpreviewRelease=true`: 12개, 실패/건너뜀 0.
- 서명 후 apksigner 검증: Preview Release v2/v3, Debug v2 성공. aapt로 각각 `.preview`/비디버그와 `.debug`/디버그를 확인했습니다.
- 실제 서명된 Preview Release 설치 성공. MainActivity cold start, 시스템 PIN 인증 후 홈 진입, 자동매매/시세·분석/자산/활동/연결 및 보안 화면, Home 이동 후 재진입 잠금 확인. 실제 API 키를 입력하거나 주문하지 않았습니다. 초기 테스트는 Home 전환 완료 전 즉시 앱을 다시 열어 잠금 단언에 실패했고, launcher 전환을 확인한 뒤 다시 실행해 통과했습니다. 이 결과는 onStop 이후 재잠금 검증입니다.
- Release SHA-256: `0feb91bdf5d16f3745f0ff23e199349cd9f0bf681beb00f0519c0363045cd8db` (1,513,436 bytes).
- Debug SHA-256: `45b34b136715c836d45b935d005ee74e28798baa8c2341e8c6e33923f0cf1eba` (15,635,833 bytes).
- Preview 서명 인증서 SHA-256: `ca2957b33c5915bf98a3f947cfa2fa52c8868720db1838615447fb8d9ee3ad6b`. 전용 테스트 private key는 저장소 밖 Windows 사용자 전용 ACL 디렉터리에 있고 비밀번호는 사용자 DPAPI로 보호합니다. 키/비밀번호는 저장소·APK·GitHub에 포함하지 않습니다.
- 출처 허용 목록·계층 경계·Markdown 링크 검사 통과. 실제 NH 계좌·장시간 실행·뉴스 계약·수정주가·그룹 체결 대사 및 Play 심사는 미검증입니다.
- 로컬 산출물: `artifacts/v0.1.0-preview.1/`. 현재 공개 릴리즈는 보류합니다. 전체 기능 완료 후 발행이라는 요청 조건이 아직 충족되지 않아, 자동주문 잠금을 유지한 제한적 프리릴리즈 공개 범위에 대한 사용자 답변을 기다립니다.

기준일: 2026-09-19. 결과는 실행한 범위만 기록하며 버그 없음·출시 가능·전략 수익성을 의미하지 않습니다.

## 환경

Windows, Android Studio JBR, Gradle 8.13, AGP 8.12.2, Kotlin 1.9.0, Android SDK 36. Android Studio JBR의 `jlink`를 사용합니다. 로컬 SDK 경로는 커밋하지 않습니다. Gradle 배포 ZIP은 공식 SHA-256을 wrapper에 고정했습니다.

## 실행 결과

### 사용성·시세·매매 재검토 (2026-09-20)

구현 커밋 `2fb91dccf27f2cbe10388b398897e5602d4c2023`의 [GitHub Actions 35456914050](https://github.com/sinpie/StockNHPlug/actions/runs/35456914050)이 성공했습니다. Ubuntu/JDK 17에서 단위 테스트·Lint·APK/AAB·출처·계층 검사를 통과했습니다. 기기/글꼴 테스트는 아래 로컬 결과입니다. Actions 버전/런타임 폐기 예정 경고는 남아 있습니다. 증거만 추가한 문서 커밋은 CI를 재실행하지 않습니다.

JVM 회귀 범위를 82개로 확장했습니다. 장 마감 직전 가능수량 조회 지연, 잔고만 먼저 만료되는 경우, 저널 저장 지연, 원래 시세의 만료 시각 보존과 5초+1ns 차단, 현재가 정상/호가 가격범위 밖, 수신시각 최신/거래소 시각 역행을 검증합니다. Broker 호출 전 차단은 REJECTED, 호출 후 불명확 결과는 UNKNOWN/정지임을 구분합니다. 주문 만료 시각의 암호화 저장·이전 저널 읽기 호환성도 기기 테스트에 포함했습니다.

기기 디버깅 경과:
- 첫 시도는 에뮬레이터 부팅 중 패키지 서비스가 없어 APK 설치 단계에서 중단됐습니다(실행 테스트 0개). 부팅 완료와 package 서비스 존재를 확인한 뒤 재실행했습니다.
- 다음 실행은 12개 중 1개가 같은 `자동매매` 문구 두 개를 선택해 실패했습니다. 클릭 가능한 내비게이션 노드로 선택자를 수정한 뒤 12개 모두 통과했습니다.
- 100% 글꼴 UI 3개 추가 실행은 통과했습니다. 130% 글꼴에서는 화면 아래 그룹을 스크롤 없이 확인하려던 assertion이 실패했습니다. 스크롤 후 그룹을 확인하도록 보완했습니다. 이 실패를 앱 기능 통과로 표시하지 않습니다.

UI fixture의 시세·주문은 명시적인 합성 자료이며 실제 브로커를 호출하지 않습니다. production 인증/FLAG_SECURE를 우회하지 않는 별도 테스트 호스트입니다. 실제 NH 모의계좌·장시간 서비스·실물 기기·Play 심사는 수행하지 않았고 기존 출시 게이트는 유지합니다.

최종 재실행: `testDebugUnitTest lintDebug assembleDebug bundleRelease` 성공, JVM 82개 통과, Lint 오류 0/기존 버전 경고 12개. 별도 `connectedDebugAndroidTest`는 API 30에서 12개 모두 통과했습니다. 이후 화면 3개 테스트를 100% 및 130% 글꼴에서 각각 재실행해 모두 통과했습니다(고유 테스트 수에 중복 합산하지 않음). 홈·시세 화면 캡처를 육안 확인했고 130%에서 하단 항목은 스크롤로 접근함을 확인했습니다. 글꼴 크기는 테스트 후 100%로 복구하고 테스트 에뮬레이터를 종료했습니다. 200% 글꼴·다른 기기/OS는 미검증입니다.

### NamuMagic 가격 추적 (2026-09-20)

구현 커밋 `f5581ca79021123bb61c04b44ac0dce249ae8ee8`의 [GitHub Actions 35455094529](https://github.com/sinpie/StockNHPlug/actions/runs/35455094529) 성공. Ubuntu/JDK 17에서 단위 테스트·Lint·APK/AAB·출처·계층 검사가 통과했습니다. Android 10개는 아래 로컬 기기 실행 결과이며 CI 기기 테스트가 아닙니다. Actions 런타임/버전 폐기 예정 경고는 남아 있습니다. 이 증거만 추가한 문서 커밋은 CI를 재실행하지 않습니다.

가격 추적 JVM 회귀 및 통합 테스트를 추가했습니다. 실제 NamuExecutionGate를 사용해 가상 매수 등록/저점만으로 파킹을 팔지 않고 반전 후 매도, 종료 체결 및 현금 갱신 후에만 주식을 매수함을 검증했습니다. ACK 전/당일 메타데이터 전 WS 거절, 단절 시 REST 복귀와 극값 보존, 10% 경계·현재가 분모, 오래된/동일 캐시의 기한 불변, 가격범위 중단, 틱 경계, 매수/매도 반전, 만료 시 불리한/낡은 가격 차단을 포함합니다.

JVM 76개 통과(실패/오류 0). 실제 NH 현재가 응답·WS ACK·상품 종류·원본 전략 성과·장시간 기기 운용은 미검증이며 합성 테스트를 실제 주문 검증으로 표시하지 않습니다. 출시/그룹 체결 대사 잠금은 유지합니다. 아래 과거 결과는 각 변경 당시 기록입니다.

최종 `testDebugUnitTest lintDebug assembleDebug bundleRelease` 성공, 린트 오류 0/기존 의존성 버전 경고 12개. 빌드 후 별도 실행한 `connectedDebugAndroidTest`도 폐기 가능한 API 30 에뮬레이터에서 10개 모두 통과했습니다. 기존 Keystore/암호화 저장/전략·파킹 UI 회귀이며 새 로그 추적 카드의 실제 장중 화면 검증은 포함하지 않습니다. 출처·계층·Markdown 링크 검사와 `git diff --check` 통과. 실제 사용자 데이터 삭제나 증권사 주문은 수행하지 않았습니다.

### 파킹 설정·자금 순환 (2026-09-20)

구현 커밋 `ccb5be31dd91f5982adb35e2c89e026f37f1da51`의 [GitHub Actions 35451470436](https://github.com/sinpie/StockNHPlug/actions/runs/35451470436)이 성공했습니다. 원격 Ubuntu/JDK 17에서 단위 테스트·린트·APK/AAB·출처·계층 검사가 통과했습니다. Android 10개는 아래 로컬 기기 테스트 결과입니다. Actions 런타임/버전 폐기 예정 경고는 남아 있습니다. 이 기록만 추가한 문서 커밋은 CI를 재실행하지 않습니다.

`testDebugUnitTest lintDebug assembleDebug bundleRelease` 성공: JVM 63개 통과, 린트 오류 0/기존 버전 경고 12개, APK/AAB 빌드 성공. `connectedDebugAndroidTest`를 별도 실행해 API 30 테스트 10개가 모두 통과했습니다. 파킹 설정의 입력 오류/저장, 암호화 복원, 최소 현금/상한/회전/간격, 외부 보유 미사용, 매도 접수·체결만으로 주식 주문을 진행하지 않음, 실제 조회 현금 갱신 후 재평가, 오래된 전략 시세 시 유휴 파킹 금지, 날짜를 넘긴 회차 재전송 거절을 포함합니다. 출처·계층 검사와 Markdown 링크 검사도 통과했습니다.

첫 통합 실행에서는 UI 테스트가 `keyDispatchingTimedOut`로 중단되어 6개 중 1개 실패로 기록됐습니다. 같은 코드로 빌드 완료 후 기기 테스트를 단독 재실행했을 때 10개 모두 통과했습니다. 병행 실행 로그에 렌더링 지연이 있었으나 환경 부하를 유일한 원인으로 확정하지 않습니다. 실물 기기 성능·장시간 서비스·실제 증권사 파킹/결제 검증은 미실행입니다.

이전 백그라운드 변경 커밋 `876f731`의 [원격 CI 35450495185](https://github.com/sinpie/StockNHPlug/actions/runs/35450495185)도 성공했습니다. 이는 현재 파킹 변경의 원격 결과와는 구분합니다.

### 지속 실행 변경 (2026-09-20)

앱 자체 6시간 만료/최근 앱 목록 제거 시 정지 제거와 시스템 timeout 처리 후 `testDebugUnitTest lintDebug assembleDebug bundleRelease`가 성공했습니다. JVM 52개와 출처·계층 검사 통과. 이번 변경의 실제 서비스 장시간 실행/화면 꺼짐/최근 앱 제거/Doze/Android 15+ timeout 기기 검증은 미실행이며, 이전 Android 9개 통과를 새 수명주기 검증으로 간주하지 않습니다. NH 그룹 자동주문은 기존 검증 게이트로 잠겨 있습니다. NamuMagic 참조가 필요한 적응형 추적은 아직 미구현이며 [추적 요구사항](PRICE_TRACKING.md)에 구분했습니다.

| 검증 | 결과 | 근거 |
|---|---|---|
| Debug APK | 빌드 성공 | `app/build/outputs/apk/debug/app-debug.apk` |
| Release AAB | 빌드 성공, 업로드 서명 미설정 | `app/build/outputs/bundle/release/app-release.aab` |
| JVM unit tests | 63개 통과, 실패/오류 0 | `app/build/reports/tests/testDebugUnitTest/index.html` |
| Android Lint | 오류 0, 라이브러리/Gradle 최신 버전 알림 경고 존재 | `app/build/reports/lint-results-debug.html` |
| 출처/로그 정적 검사 | 통과 | `python scripts/audit_sources.py` |
| Android instrumentation | 10개 통과, 실패 0 | Android 11 / API 30 x86_64: Keystore 2개, UI 3개, LocalStore/설정 저장 5개 |
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
# 2026-09-20 다중 타겟·날짜 이월·시세 전송 검증

실행: `python scripts/local_build.py testDebugUnitTest lintDebug assembleDebug bundleRelease connectedDebugAndroidTest` → **BUILD SUCCESSFUL**. JDK/SDK는 로컬 빌드 설정 사용. 이번 시험에서 실제 주문·취소·키 인증 요청을 발송하지 않았다.

| 검증 | 결과 |
|---|---|
| JVM 단위/통합 | **96개 통과**, 실패/오류 0 |
| Android API 30 일회용 Debug 설치 | XML 기준 **16개 중 15개 통과, 1개 제외**, 실패/오류 0. `CredentialProbeTest`는 opt-in 미지정으로 제외 |
| lintDebug | 오류 0, 기존 의존성/도구 버전 경고 12개 |
| Debug APK / Release AAB | assembleDebug / bundleRelease 성공. APK 릴리즈 게시 또는 Play 출시 승인 아님 |
| 계층 검사 / 소스 감사 / Markdown 로컬 링크 / diff 공백 | 통과 |

새 회귀 검증: 정확히 10%는 REST, 직전은 WS; 거리별 2/3/5/15/30/60/180초 예약; OFF 발견용·실패 포함 최대 10초; 빈 WS 미연결·ACK·장애 시 REST 복귀; 실제 반전 트리거; 상위 제시가 범위 밖이면 이력 갱신·추적·주문 차단; 다른 그룹의 유효 타겟은 계속; 새 날짜의 범위 재조회. REST 10초는 예약 간격 시험이며 실제 OS/네트워크 지연의 상한을 보장하지 않는다.

연속성 검증: 동일 종목의 다른 전략/그룹별 키·제시가·극값 분리, 미완료 정기매수의 다음 날 원래 회차/제시가 유지, 전날 기한으로 즉시 주문하지 않음, 재생성 후 이력 복원·ready 미복원, 계좌·환경 격리, 비활성 이력 보존. 실제 Android Keystore 암호문 저장과 설정 기본 ON 이관/OFF 유지, UI 스위치 및 편집 잠금도 확인했다. 저장 오류를 REST 백오프로 흡수하지 않는 테스트를 추가했다.

주문 경로는 **가짜 Broker 포트**로 검사했고 dispatch 직전 SUBMITTING 저널 존재 및 접수 후 재전송 차단을 확인했다. 실제 NH 체결·그룹 대사, 실기기 장시간 백그라운드/Doze, Play 공개 사용허가 및 뉴스/수정주가 출시 게이트는 여전히 미완료다. 이번 결과를 실매매 또는 NH 주문 통합 검증 통과로 해석하지 않는다.

원격 재검증: 구현 커밋 `cef1cf2761151cd6d83e2b0350720a2646ee8c26`의 [GitHub CI 35506158166](https://github.com/sinpie/StockNHPlug/actions/runs/35506158166) **success** 확인. Ubuntu에서 단위 테스트·Lint·Debug APK·Release AAB·소스 감사·계층 검사를 실행했다. Android 기기 검증 결과는 위 로컬 API 30 시험이며 CI가 실행한 것으로 표시하지 않는다.
