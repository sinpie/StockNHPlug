# 검증 결과

기준일: 2026-09-19. 결과는 실행한 범위만 기록하며 버그 없음·출시 가능·전략 수익성을 의미하지 않습니다.

## 환경

Windows, Android Studio JBR, Gradle 8.13, AGP 8.12.2, Kotlin 1.9.0, Android SDK 36. Android Studio JBR의 `jlink`를 사용합니다. 로컬 SDK 경로는 커밋하지 않습니다. Gradle 배포 ZIP은 공식 SHA-256을 wrapper에 고정했습니다.

## 실행 결과

| 검증 | 결과 | 근거 |
|---|---|---|
| Debug APK | 빌드 성공 | `app/build/outputs/apk/debug/app-debug.apk` |
| Release AAB | 빌드 성공, 업로드 서명 미설정 | `app/build/outputs/bundle/release/app-release.aab` |
| JVM unit tests | 21개 통과, 실패/오류 0 | `app/build/reports/tests/testDebugUnitTest/index.html` |
| Android Lint | 오류 0, 라이브러리/Gradle 최신 버전 알림 경고 존재 | `app/build/reports/lint-results-debug.html` |
| 출처/로그 정적 검사 | 통과 | `python scripts/audit_sources.py` |
| Android instrumentation | 3개 통과, 실패 0 | Android 11 / API 30 x86_64 에뮬레이터: Keystore round-trip/변조 방어, 6개 탭 이동 |
| 실제 NHPlug 모의 계좌 | 미실행 | 사용자 기기 키·모의계좌 필요 |
| 실제 자금 주문 | 미실행, 잠금 | 기본 구성은 모의 계좌만 제공 |
| Play 심사 | 미제출 | RELEASE.md의 게이트 미완료 |

## JVM 테스트 범위

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

실물 기기/다른 OS별 Keystore, 생체/기기 인증, API 31+ 오버레이 차단, API 34+ foreground specialUse, 제조사 절전, 실제 장중 API/WS, 부분체결/정정/취소/미확인 주문 대사, 휴장·시스템 시계 조작, 실제 토큰 재발급, 장기 실행, 거래비용 포함 전략 성과는 별도 검증이 필요합니다. UI 테스트는 인증 우회가 없는 별도 테스트 호스트에서 실행했으며 production MainActivity의 인증 성공을 검증한 것은 아닙니다. Google Play 정책 준수의 최종 확인과 NH/DART/뉴스 제공자의 배포·데이터 권한 검토는 자동 테스트로 대체할 수 없습니다.

의존성 버전은 빌드 호환성이 확인된 조합으로 고정했습니다. 최신 버전 경고를 숨기지 않았고 최신 버전이라는 주장을 하지 않습니다. 출시 전 보안 공지와 의존성 업그레이드를 검토해야 합니다.
