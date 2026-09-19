# 0.1.0-preview.1 · 테스트 배포 후보 (미공개)

전체 자동매매 완료 버전이 아닙니다. NH 그룹 주문·체결 연동 검증 및 뉴스/수정주가 이용 근거가 미완료여서 자동주문 시작을 차단합니다. 정기매수·파킹도 자동 실행되지 않습니다. 수익성/무결점/Play 출시 승인을 의미하지 않습니다.

## 변경 내용

- Kotlin/Compose 투자 작업 공간: 홈, 자동매매, 시세·분석, 자산, 활동.
- 물타기·리밸런싱 전략/그룹, 추천 조건 5개 조합, 정기매수와 현금 파킹 설정.
- NamuMagic 참조 타겟 추적과 10% 경계 REST/웹소켓 정책, 저널·신선도·소유권 검사.
- NHPlug 앱키·secret 및 OpenDART 키 입력, 기기 내부 암호화 저장과 기기 인증.
- 정식 앱과 구분되는 Preview Release/Debug 설치, 서명·해시 검증.

## 설치와 검증

일반 사용성 확인은 `StockNHPlug-0.1.0-preview.1-release.apk`, 개발자 디버깅은 `…-debug.apk`를 사용합니다. Android 8 이상과 기기 PIN/패턴/비밀번호가 필요합니다. 두 앱은 키·데이터를 공유하지 않습니다.

[테스트 안내](TESTING.md)에 키 설정, 설치, 확인 순서를 정리했습니다. JVM 82개/Android 12개 통과, 린트 오류 0(기존 경고 12개), Debug/Release APK 및 AAB 빌드 성공. 최적화 Release APK의 설치·PIN 인증·주요 화면·재진입 잠금을 API 30 에뮬레이터에서 확인했습니다. 실제 NH 계좌 검증은 수행하지 않았습니다. 상세 결과는 [VERIFICATION.md](VERIFICATION.md)를 확인하세요.

배포 시 APK 2개, SHA256SUMS.txt, APK-VERIFICATION.json을 첨부합니다. Preview는 별도 테스트 서명을 사용하며 Play 정식 배포본이 아닙니다. 공개 전 이 문서의 미공개 상태와 실제 릴리즈 링크를 갱신합니다.
