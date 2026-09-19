# StockNHPlug

Kotlin / Jetpack Compose 기반 NHPlug 국내주식 자동매매 앱입니다. 한국어 UI, 기기 내 암호화 저장, 모의투자 연결, 가격 추적·보유종목 관리, 주문·손익·로그 조회를 제공합니다.

**현재 버전은 출시 전 검증용입니다. 실거래는 코드에서 잠겨 있습니다.** 모의 계좌와 API 키가 있어야 증권사 연동을 검증할 수 있습니다. 뉴스 이용계약 및 수정주가 기준이 확정되지 않아 **추천종목 신규 자동매수는 차단**됩니다. 해당 기능을 완료했다고 표시하지 않습니다. 보유종목 자동매도는 별도 동의와 실시간 시세·위험 조건을 통과해야 합니다.

## 실행

1. JDK 17 이상, Android SDK 36을 설치합니다.
2. `local.properties`에 로컬 `sdk.dir`을 지정합니다. 이 파일은 커밋하지 않습니다.
3. `./gradlew testDebugUnitTest lintDebug assembleDebug` 실행. Windows에서는 `gradlew.bat`을 사용합니다.
4. APK: `app/build/outputs/apk/debug/app-debug.apk`.
5. 기기 PIN/패턴/비밀번호를 설정하고 앱에서 기기 인증을 완료합니다.
6. 설정에서 본인 NHPlug 앱키·secret, 선택적으로 본인 OpenDART 인증키를 입력합니다. 대화나 GitHub에 키를 보내지 마세요.
7. 모의계좌 연결 → 전략 저장 → 리서치 조회 → 보유종목 관리 동의 → 모의 자동매매 시작 순서입니다. 키 발급/모의투자 신청은 [NHPlug 공식 안내](https://www.nhplug.com/intro)를 따릅니다.

## 구성

```mermaid
flowchart TB
 ROOT[AppContainer / 포트 구현 조립] --> APP[응용: TradingController]
 UI[Compose UI / 기기 인증] --> APP
 APP --> STRATEGY[TradingStrategy / 교체 가능한 전략]
 APP --> RESEARCH[ResearchRepository / 데이터 사용권한·품질 검증]
 RESEARCH --> DART[OpenDART 공식 API]
 RESEARCH --> PRICE[일봉 / 수정주가 변환 / 뉴스 Provider]
 APP --> TRADE[매매: TradingEngine / 가격 추적·위험 한도]
 TRADE --> PORT[Broker / OrderJournal 인터페이스]
 PORT --> EXEC[실제 매매: NhBroker / NhSocket]
 EXEC --> NH[NHPlug REST / WebSocket]
 PORT --> LOCAL[LocalStore / SecureVault / Android Keystore]
```

## 문서

- [API·데이터·전략 교체 안내](docs/EXTENDING.md)
- [계층 및 클래스 상세](docs/ARCHITECTURE.md)
- [진입점·함수 흐름도·코드 수정 안내](docs/CODE_FLOW.md)
- [데이터 출처·이용조건·보관 방침](docs/DATA_SOURCES.md)
- [전략·가격 추적·연구 근거](docs/STRATEGY.md)
- [보안 설계·위협 모델](docs/SECURITY.md)
- [UI와 사용자 흐름](docs/UX.md)
- [Google Play 출시 게이트](docs/RELEASE.md)
- [개인정보 처리방침 초안](docs/PRIVACY.md)
- [검증 결과](docs/VERIFICATION.md)
- [개발 이력](docs/CHANGELOG.md)

NH투자증권 공식 앱이 아닙니다. 투자 수익·손절 실행·무결점·Google Play 승인을 보장하지 않습니다. 프로그램 정지는 이미 증권사에 접수된 주문을 취소하지 않습니다.

## 화면

Android 11 에뮬레이터의 UI 테스트 호스트에서 캡처한 연결 전 화면입니다. 실제 키나 계좌 데이터는 없습니다. 앱 본체는 화면 캡처를 차단합니다.

<img src="docs/images/dashboard.png" width="360" alt="StockNHPlug 연결 전 대시보드" />
