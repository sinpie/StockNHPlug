# Google Play 출시 게이트

## 요청 28 검증 상태

2026-09-21 실제 모의계좌 3개의 조회·이력 저장/출력, 장중 시세 ACK/틱·종목 교체·명시적 재연결을 시험했다. REST 신선도 거절 및 HTTP 429를 관측했으며 전체 진단은 통과하지 않았다. [실행별 결과](MOCK_VERIFICATION.md). 이는 아래 실제 주문·누적 체결 대사·동시 자동운용·장시간 서비스 게이트 완료가 아니다. 이번 빌드 성공만으로 새 정식 릴리즈나 주문 가능한 APK를 공개하지 않는다.

## 테스트 배포와 정식 출시 구분

`-PpreviewRelease=true`로 별도 `.preview` ID의 최적화 Release APK를 만들고 전용 로컬 테스트 키로 서명할 수 있습니다. Debug APK와 함께 서명/해시를 검증하는 절차는 [TESTING.md](TESTING.md)를 따릅니다. Preview는 정식 Play 앱 서명·업로드 키를 사용하지 않으며 주문 잠금을 해제하지 않습니다. 미완료 기능을 숨긴 정식 릴리즈로 공개하지 않습니다. APK 공개 여부와 기기 실행 증거는 [VERIFICATION.md](VERIFICATION.md)에 기록합니다.

검토일 2026-09-19. 기술적 빌드 성공과 스토어 승인·금융업 허용은 별개입니다. 현재는 검증용 0.1.0이며 아래 미완료 항목이 남아 있습니다.

## 반영한 사항

- Kotlin, Android targetSdk 36. [공식 target API 안내](https://developer.android.com/google/play/requirements/target-sdk)는 2026-08-31부터 신규/업데이트에 API 36을 요구합니다.
- 광고/분석 SDK 없음, 최소 권한, 암호화 키, 백업 제외, UI 개인정보 안내, 데이터 삭제 기능.
- 수익 보장/허위 수익/실제 계좌인 것처럼 보이는 샘플 데이터 없음. 앱은 NH투자증권 공식 앱이라고 주장하지 않습니다.
- 사용자 시작·지속 알림·즉시 정지 가능한 foreground service. `specialUse` 설명을 manifest에 선언했고 부팅 자동시작을 구현하지 않았습니다.
- 실거래 주문 컴파일 설정 기본 잠금. 키나 서명키를 저장소/CI에 포함하지 않습니다.

## 제출 전 필수 업무

- [ ] 운영자 명칭, 지원 연락처, 공개 HTTPS 개인정보 정책 URL 확정. `PRIVACY.md` 초안의 운영자 빈 항목 해소 후 앱 내에도 연결.
- [ ] 금융 기능 신고에서 실제 제공 기능(주식 거래/포트폴리오 관리 등)을 정확하게 신고. [Financial features declaration](https://support.google.com/googleplay/android-developer/answer/13849271).
- [ ] 배포 국가의 금융·투자자문·자동매매 관련 규정과 앱 운영 형태 확인. [Google Play 금융 서비스 정책](https://support.google.com/googleplay/android-developer/answer/9876821). 개인 자기계좌 도구라는 설명만으로 법적 면제를 추정하지 않음.
- [ ] 주식 거래 등 금융 서비스를 제공하는 앱은 조직 개발자 계정 요건에 해당하므로 조직 인증·사업자 증빙을 준비. [계정 유형 공식 안내](https://support.google.com/googleplay/android-developer/answer/13634885).
- [ ] NHPlug의 제3자 배포 앱 사용 및 시세 표시/가공 범위, 자동매매 동작에 대한 공식 확인. 실제 계정 약관·사용신청 완료.
- [ ] 뉴스 분석권과 수정주가 기준 확보. `DATA_SOURCES.md`의 미확정 항목 해소. 권리 확인 없이 `newsLicensed`/`adjusted` 플래그를 바꾸지 않음.
- [ ] Data safety에서 실제 기기 밖으로 전송되는 인증·금융 데이터와 제공자 역할을 정확하게 신고. 개발자 서버가 없다는 이유만으로 무조건 ‘수집 없음’으로 신고하지 않음. [Data safety 안내](https://support.google.com/googleplay/android-developer/answer/10787469), [User data 정책](https://support.google.com/googleplay/android-developer/answer/10144311).
- [ ] Foreground service 용도, 사용자 시작 흐름, 알림 정지 시연 영상, `specialUse` 사용 정당성 제출. 심사 결과에 따라 백그라운드 실행 방식을 재검토. 이를 다른 유형으로 허위 선언하지 않음. [서비스 유형](https://developer.android.com/develop/background-work/services/fgs/service-types).
- [ ] Play App Signing과 업로드 키 관리, 프로덕션 applicationId 확정, 서명된 AAB 생성. 현재 로컬 release bundle은 업로드용 서명이 없는 검증 산출물.
- [ ] 콘텐츠 등급, 금융 기능, 앱 액세스 심사용 모의계정 안내, 지원 URL, 스크린샷, 스토어 설명, 사용자 테스트 요건 충족.
- [ ] 실제 기기 + 모의증권 계좌 E2E: 토큰 재사용, 다중 계좌, 정상·거절·429·401, 부분체결, IOC 미체결, 통신 단절, 재시작, 암호문 손상, 시간 변경, 휴장, 강제종료, 백그라운드, 알림 권한 거절 검증.
- [ ] 포트폴리오/체결/P&L 필드의 실제 응답 검증. 시장주문번호·통합주문번호 정합성 확보 후 주문별 체결 상태 자동 대사 구현.
- [ ] 외부 계좌 활동/입출금 처리, point-in-time 데이터 검증, 비용 포함 표본 외 백테스트, 투자전략 품질 검토.
- [ ] 독립 보안 검토 및 기기 암호화 instrumentation tests 수행.

## 실거래 활성화 절차

이 체크리스트의 증거를 `VERIFICATION.md`와 연결하고 실제 API 응답 fixture를 비식별화해서 테스트합니다. 담당자가 계정·데이터 라이선스·정책 항목을 확인한 뒤 live 환경 선택 UI, 추가 기기 인증/실거래 확인, 주문 대사를 구현해야 합니다. 현재 `NhBroker`의 운영 전송 코드는 존재하지만 기본 UI는 모의만 연결하고 실제 자금 주문을 실행하지 않습니다. 실제 주문 테스트는 별도의 명시적 사용자 지시와 한도가 있어야 합니다.

## 그룹 자동매매 추가 게이트

- [ ] mkt_orr_no ↔ itg_orr_no 공식 대응 및 장 종료/재접속 후 누락 없는 체결 대사 명세 확보.
- [ ] GroupExecutionSource 실제 NHPlug 모의계좌 구현 검증: 동일 종목 다중 그룹, 부분체결/IOC 취소, 중복/역순 메시지, 수수료 확정, 외부 매도, 재시작.
- [ ] 그룹 원장과 실제 잔고/자금 보존 및 기업행위·체결 정정 처리.
- [ ] 제조사별 장시간 세션·정기매수 시각·휴장/월말/프로세스 종료 검증.

게이트 완료 전 그룹 자동주문을 활성화하지 않습니다. 현재 예약은 foreground 세션 내 평가이며 앱 종료 상태의 예약 실행 서비스가 아닙니다.

## 지속 실행 추가 검증
- [ ] specialUse 지속 매매 기능의 Play Console 선언·동영상·심사 근거 확보
- [ ] 최근 앱 목록 제거/화면 잠금/다른 앱 사용 시 실제 모의 세션 유지
- [ ] Doze·제조사 절전·사용자 강제 정지·시스템 timeout 처리 확인
- [ ] 장시간 실행의 배터리·열·토큰 만료·거래일 전환 확인

Android의 dataSync 6시간 제한을 회피하기 위한 타입 변경이 아닙니다. 기존 specialUse의 사용자 요청 매매 기능에 대해 심사 적합성을 별도 확인해야 합니다. [FGS 유형](https://developer.android.com/develop/background-work/services/fgs/service-types), [Play 요구사항](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en), [Doze 제한](https://developer.android.com/training/monitoring-device-state/doze-standby)을 2026-09-19 확인했습니다. Doze는 네트워크/CPU 실행을 지연할 수 있으므로 휴대폰만으로 무중단 주문을 보장하지 않습니다.

## 파킹 출시 게이트
- [ ] 공식 주문/체결 대응 및 파킹 부분체결·미체결·현금 반영 시점 검증
- [ ] 주문가능 금액/결제일/수수료·세금 차이 검증
- [ ] 파킹 상품별 공식 데이터 적격성 검증 (ETF 포함)
- [ ] NamuMagic 참조 Kotlin 추적 구현의 실제 NH 통합 검증과 배포 권리 최종 검토 (구현 상세: PRICE_TRACKING.md)
- [ ] 장시간 매수↔매도 순환/일 한도/서비스 재시작 저널 검증

## 추적 출시 추가 게이트
NamuMagic 참조 및 Kotlin 구현은 완료했으나 NH 실제 호가/ACK/상품 구분, 단절·재연결·장경계·장시간 기기 테스트는 미검증입니다. 원본의 주문번호 대응 검토를 이 앱의 체결 대사 통과로 간주하지 않습니다. GroupExecutionSource 구현 및 모의계좌 증거 전 시작 잠금 유지.
