# 진행 중 작업과 재개 지점

기록일: 2026-09-20. 요청 원문은 [USER_REQUESTS.md](USER_REQUESTS.md)를 먼저 읽는다. 이 파일은 구현 완료 보고가 아니다.

## 이번 요청 반영 상태

- 요청 27 구현·로컬 검증 완료: 원문 기록 후 독립 조합 테스트 1,000개(C0001~C1000) 추가. ROUTE/ORDER/LEDGER/EXPORT 각 200개, SCHEDULE/ALGORITHM 각 100개. 결정적 TSV·전체 Markdown 목록·설계·생성 일치 검사·XML 실행 ID 검사와 CI 연결 완료. 새 1,000개와 기존 125개를 합친 JVM 1,125개 통과, 새 ID 1,000개가 각각 1회 실행되고 실패/오류/skip 0임을 검사. lint/Debug APK/Release AAB 및 계층/출처/문서 검사 통과. 프로덕션 코드·실매매 잠금 변경 없음. 기기/API/실제 키 테스트는 이번 요청에서 실행하지 않음. Git push 및 CI 증거 기록 대기.

- 요청 26 구현·로컬 검증 완료: 선택 계좌/기간 CSV·ZIP, SAF OPENABLE 계약, 고정 스냅샷/IO 저장 상태, 홈 운용 점검/일 매수 예약액, 공유 저장 오류 서비스 시작 차단. JVM 125개 및 Android 30개 통과(opt-in 1개 제외), 기본/130% UI 각각 2개 통과, 실제 런처 cold start 확인. 초기 Android 계약 실패 수정과 최종 빌드·lint·감사·캡처 결과를 VERIFICATION.md에 기록. EXPORT_AND_OPERATIONS.md와 관련 문서 갱신. 실제 주문/키/외부 문서 제공자의 전체 저장 경로는 미검증. 구현 `8e619c8` main push 및 [GitHub CI 35511701687](https://github.com/sinpie/StockNHPlug/actions/runs/35511701687) success 확인 완료. 요청 26 완료, 기존 실매매·제공자·출시 게이트는 유지한다.

- 요청 25 구현·검증 완료: 계좌별 runtime/vault/전략/이력 격리, 여러 enable 계좌 동시 시작, 일별 현금·거래·보고 손익 보존, 일/월/연 통계. 최종 JVM 113개, Android 24개 통과(opt-in 1개 제외), 기본/130% 통계 UI 각각 2개 통과. 계좌 고정 명령/암호문 교환 거절/기존 미확인 원장/누적 체결 교체 검증 완료. Debug APK/Release AAB/린트/계층/출처/문서 검사 성공. ACCOUNT_HISTORY.md와 구조/보안/개인정보/실제 검증 문서 및 캡처 갱신. 구현 `61860cd` main push 및 [GitHub CI 35509909704](https://github.com/sinpie/StockNHPlug/actions/runs/35509909704) success 확인. 실제 주문 잠금과 미완료 출시 게이트 유지.

- 요청 24 구현·검증 완료: 메뉴/탭/설정 개편, 전문 조회 지표, 타이밍·취소·세대 검사, 편집 저장 확인. ASYNC_REVIEW.md에 계산·비동기 계약 기록. JVM 105개, Android 19개 통과(opt-in 1개 제외), 기본/130% UI 각각 7개 통과. 실패 원인·수정·화면 육안 확인은 VERIFICATION.md 기록. Debug APK/Release AAB·린트·계층/출처/문서 검사 성공. 구현 `9ee728a` main push 및 [GitHub CI 35508100911](https://github.com/sinpie/StockNHPlug/actions/runs/35508100911) success 확인. 원문 기록 완료. 앱 전체 출시 게이트는 계속 유지한다.

- 요청 20: 거리 10% 이상 REST / 10% 미만 WebSocket, 멀수록 긴 REST 간격, WebSocket 기본 ON 및 OFF 시 최대 10초 조회 예약 구현. 빈 소켓 미연결 및 OFF REST 반전 테스트 통과.
- 요청 21: 실제 트리거와 별개로 상위 전략 제시가가 당일 상하한가 밖이면 해당 타겟의 당일 추적·주문 중단. 같은 종목에 유효한 다른 타겟이 있으면 그 타겟은 계속 추적. 경계·다음 날 재확인 테스트 통과.
- 요청 22: 전략·그룹·종목·방향·회차별 타겟 키와 이력을 분리. 날짜 변경 시 미완료 키·극값 유지, 기한만 당일 갱신. TrackingStore/EncryptedTrackingStore 및 activate 연결, 계좌·증권사·환경별 암호화 복원 구현. 정기매수 원래 occurrence 유지, 저장 오류 안전 정지, UI 최고/최저 표시. JVM 및 실제 Android 저장/복원 테스트 통과.
- 요청 23: 사용자 원문 누적 기록과 재개 규칙을 USER_REQUESTS.md 및 AGENTS.md에 반영.

## 완료한 검증 및 저장소 반영

1. 로컬 최종 Gradle 성공: JVM 96개 통과, Android XML 16개 중 15개 통과·실제 키 opt-in 테스트 1개 의도적 제외. 실패 0. lint/Debug APK/Release AAB 성공. VERIFICATION.md 기록 완료.
2. 계층 검사·소스 감사·문서 링크 검사 통과. 구현 커밋 `cef1cf2`를 main에 push했고 [GitHub CI 35506158166](https://github.com/sinpie/StockNHPlug/actions/runs/35506158166) success 확인.
3. 이번 요청 20~23의 구현 및 검증 완료. 다음 사용자 요청도 실행 전에 USER_REQUESTS.md에 원문을 추가한다. 앱 전체의 미완료 출시 게이트는 아래 기준과 RELEASE.md/VERIFICATION.md에 남아 있다.

## 안전 및 검증 기준

- 실매매 주문을 테스트로 발송하지 않는다. 기존 실거래 차단을 유지한다.
- 이번 변경 이전 검증 결과를 이번 변경의 통과 증거로 재사용하지 않는다.
- REST 최대 10초는 요청 예약 간격이다. OS 중단, 네트워크 지연, API 속도 제한까지 포함한 시세 수신 보장은 할 수 없다.
- GitHub 기존 preview 산출물이 현재 코드를 포함한다고 가정하지 않는다. 출시 제한은 RELEASE.md와 VERIFICATION.md를 확인한다.
