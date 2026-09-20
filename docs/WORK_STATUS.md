# 진행 중 작업과 재개 지점

기록일: 2026-09-20. 요청 원문은 [USER_REQUESTS.md](USER_REQUESTS.md)를 먼저 읽는다. 이 파일은 구현 완료 보고가 아니다.

## 진행 중

- 요청 20: 거리 10% 이상 REST / 10% 미만 WebSocket, 멀수록 긴 REST 간격, WebSocket 기본 ON 및 OFF 시 최대 10초 조회 예약 구현. 빈 소켓 미연결 및 OFF REST 반전 테스트 통과.
- 요청 21: 실제 트리거와 별개로 상위 전략 제시가가 당일 상하한가 밖이면 해당 타겟의 당일 추적·주문 중단. 같은 종목에 유효한 다른 타겟이 있으면 그 타겟은 계속 추적. 경계·다음 날 재확인 테스트 통과.
- 요청 22: 전략·그룹·종목·방향·회차별 타겟 키와 이력을 분리. 날짜 변경 시 미완료 키·극값 유지, 기한만 당일 갱신. TrackingStore/EncryptedTrackingStore 및 activate 연결, 계좌·증권사·환경별 암호화 복원 구현. 정기매수 원래 occurrence 유지, 저장 오류 안전 정지, UI 최고/최저 표시. JVM 및 실제 Android 저장/복원 테스트 통과.
- 요청 23: 사용자 원문 누적 기록과 재개 규칙을 USER_REQUESTS.md 및 AGENTS.md에 반영.

## 다음 작업

1. 로컬 최종 Gradle 성공: JVM 96개 통과, Android XML 16개 중 15개 통과·실제 키 opt-in 테스트 1개 의도적 제외. 실패 0. lint/Debug APK/Release AAB 성공. VERIFICATION.md 기록 완료.
2. 계층 검사·소스 감사·문서 링크 검사 통과. 최종 diff 확인 후 커밋·push 및 GitHub CI 확인 단계.

## 안전 및 검증 기준

- 실매매 주문을 테스트로 발송하지 않는다. 기존 실거래 차단을 유지한다.
- 이번 변경 이전 검증 결과를 이번 변경의 통과 증거로 재사용하지 않는다.
- REST 최대 10초는 요청 예약 간격이다. OS 중단, 네트워크 지연, API 속도 제한까지 포함한 시세 수신 보장은 할 수 없다.
- GitHub 기존 preview 산출물이 현재 코드를 포함한다고 가정하지 않는다. 출시 제한은 RELEASE.md와 VERIFICATION.md를 확인한다.
