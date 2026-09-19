# 보안 설계

## 보호 대상 및 위협

인증키/secret/토큰, 계좌번호, 주문 내역과 로그가 대상입니다. 악성 오버레이, 화면 캡처, 외부 앱의 intent 호출, 백업/기기 이전 유출, HTTP 로그 노출, 중간자 공격, 중복 주문과 암호문 손상을 방어합니다. 루팅된 OS·악성 키보드·기기 인증을 통과한 공격자·물리적 완전 탈취에 대한 절대 보호는 보장하지 않습니다.

## 구현

- Android Keystore AES/GCM/NoPadding. 키는 Keystore에서 생성하며 추출하지 않습니다. 매 암호화에 새 IV, 파일명 AAD, 인증 태그 적용. 기기별 하드웨어 지원을 따르며 StrongBox 사용을 강제하거나 보장하지 않습니다.
- `noBackupFilesDir` 안에 AtomicFile로 쓰기 완료 후 교체. credentials, token, settings, journal, events, snapshot 암호화. 백업과 Android 12+ 기기 이전 명시 차단.
- 키의 인증 바인딩은 매 호출마다 요구하지 않습니다. 사용자가 승인한 백그라운드 세션이 필요하기 때문입니다. 앱 화면 진입은 기기 PIN/패턴/비밀번호 확인을 강제하고 재진입 시 다시 잠급니다.
- 키 입력은 비밀번호 변환, 자동 교정 비활성, 화면 상태 복원 저장을 사용하지 않음. 저장 후 입력창 비움. JVM의 불변 문자열과 입력기 메모리 완전 삭제는 보장하지 않습니다.
- `FLAG_SECURE`: 스크린샷·일반 화면 녹화·최근 앱 미리보기 보호. Android 12+ `HIDE_OVERLAY_WINDOWS`와 가려진/부분 가려진 터치 거절 적용.
- 외부 노출 컴포넌트는 실행용 MainActivity 하나. 주문 intent, exported service/receiver/provider, WebView, JavaScript bridge, accessibility 서비스, 임의 호스트 설정 없음.
- 인터넷, 알림, foreground service/specialUse, 오버레이 숨김 이외 위험 권한 없음. 연락처·SMS·위치·저장소·다른 앱 목록 권한 없음.
- 공식 HTTPS/WSS만 사용. 플랫폼 인증서/호스트 검증 유지. `trust-all`, 사용자 CA 우회, 인증서 오류 무시 없음. 리다이렉트 비활성. 서버 인증서 중간 체인 오류도 실패로 처리.
- 키는 발급자의 공식 인증 요청에만 포함합니다. “폰 내부에만 저장”은 서버 인증 전송까지 금지한다는 의미가 아니며 앱 내에서 이를 고지합니다.
- 원시 HTTP 본문·URL·토큰·키·계좌번호를 logcat이나 사용자 로그에 기록하지 않음. 로깅 interceptor와 원격 crash/analytics SDK 없음.
- 암호문 손상 시 빈 저장소로 시작하지 않고 거래를 잠급니다. 삭제는 명시적인 확인 후 전체 키와 데이터를 제거합니다. 기기 삭제는 브로커 토큰/주문 취소가 아니므로 공식 포털에서 별도 폐기할 수 있습니다.

## 주문 안전성

사전 영속 저널, 직렬 주문, 하루 종목/방향 중복 방지, 계좌/모의 구분, 오래된 시세·잔고 차단, 현금 가능수량, 한도, 지정가 IOC, 미확인 주문 이후 전체 거래 차단. 정지 플래그는 호출 직전 확인하나 네트워크로 이미 전송된 주문을 되돌리지는 못합니다. 공휴일 달력 자체는 미구현이며 서울 평일 시간과 신선한 실시간 정규장 거래소 메시지를 함께 요구합니다.

## 출시 전 보안 검증

- 실기기 Keystore 암호화/변조/강제종료/쓰기 도중 종료/업데이트 후 복구 검증.
- PIN/생체/키보드/대형 글꼴/분할화면/화면 캡처/오버레이/알림 정지 동작 검증.
- 알림 권한 거절·취소·OS 서비스 제한·네트워크 단절·잠금 상태 세션 검증.
- 실제 테스트 키로 API 통합 테스트하되 키/계좌/로그를 CI artifact로 업로드하지 않음.
- 서명키는 소스와 분리하고 Play App Signing 이용. API 키와 앱 서명키를 혼동하지 않음.
- [Android 보안 활동 안내](https://developer.android.com/security/fraud-prevention/activities), [OWASP 오버레이 위협](https://mas.owasp.org/MASTG/knowledge/android/MASVS-PLATFORM/MASTG-KNOW-0022/) 기준 재검토.
