# 요청 29 · 미완성 기능 완성 작업

요청 원문: `기능 미완성된 것은 모두 완성해줘`

## 구현과 외부 근거를 구분하는 기준

키 설정/빌드 성공/잠금 해제만으로 자동매매 완성을 선언하지 않는다. 실제 연결이 없는 제공자를 임의 데이터로 채우거나 시장주문번호와 통합주문번호를 같다고 가정하지 않는다. 공식 근거 확보와 코드 구현을 모두 끝낸 기능만 완료로 바꾼다.

## 작업 목록

| 항목 | 작업 및 상태 |
|---|---|
| 다중 계좌 시세 연결 | 구현 완료. 독립 단위 테스트 및 실제 공유 연결 3 lease 진단 통과. 장시간 동시 자동주문 E2E는 별도 미완료 |
| 종목-기업 자동 연결 | 공식 OpenDART corpCode ZIP 어댑터·24시간 메모리 캐시·수동 입력 대조 구현. 형식 실패 수정 후 실제 DART 전용 진단 통과; 중간 다운로드 시간 초과도 기록 |
| 분석 근거 자동 갱신 | 계좌별 요청 설정 암호화 저장/15분 갱신/가격 추적과 분리/세션 취소·실패 근거 폐기 구현. 작업자 단위 테스트 통과. 자동주문 포함 장시간 기기 시험 미완료 |
| NH 주문·누적 체결 대사 | 최신 공식 명세 다시 확보. cashBuy/cashSell의 mkt_orr_no와 dailyOrderExecution의 itg_orr_no 대응 및 주문별 확정 비용 근거 확인 필요 |
| 수정주가 | 최신 NH period의 권리락·액면변경·수정 비율 필드는 존재. 적용 방향/누적 기준/완결 보증 미확인. 임의 계수 해석 금지 |
| 뉴스 자동 분석 | 기존 계약 유무 사용자에게 질의. 공개 API 이용 조건 검토 중. 공개 페이지를 일반 크롤러로 읽지 않음 |
| 정기매수 휴장 이동 | 현재 주말 이동만 구현. 공식 영업일 공급 근거 필요 |
| 실제 자동주문·파킹·동시계좌 E2E | 위 대사/연구 공급자 확정 후 수행. 미확인 결과의 자동 재전송 없음 |
| Play 정식 발행·실거래 | 사업자/개인정보 URL/금융 신고/데이터 재배포·FGS 심사/서명 등 RELEASE.md 외부 게이트 유지 |

## 이번에 다시 확인한 공식 자료

- [NH API 가이드](https://www.nhplug.com/apiservice): 로그인 없이 API 목록 접근 성공.
- [NH 공식 SDK](https://github.com/PLUG-OpenAPI/nhplug-sdk): 포털에서 링크되는 공식 지원 저장소. SDK 자체에 두 주문번호 대응 근거는 찾지 못했다.
- [국내주식 정본](https://www.nhplug.com/openapi-docs/krstock/openapi.json): 2026-09-21 다운로드 SHA-256 `c49b793a0429ca7b4a48126619a2fe9bfecdaa9dc6dc6a11f03557f0f0a35772`.
- [공통 정본](https://www.nhplug.com/openapi-docs/common/openapi.json): SHA-256 `2d8325baf9feed78e87488e760b8b28110c79750a46cc82ac860761cbbf63242`.
- [OpenDART 고유번호 API](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019018): 공식 ZIP의 stock_code(6자리)와 corp_code(8자리)를 정확히 연결. 회사명 유사도 추정 없음.
- [빅카인즈 소개](https://www.bigkinds.or.kr/v2/intro/index.do), [사용 매뉴얼](https://bigkinds.or.kr/manual/%EB%B9%85%EC%B9%B4%EC%9D%B8%EC%A6%88_%EC%82%AC%EC%9A%A9%EC%9E%90%EB%A7%A4%EB%89%B4%EC%96%BC.pdf): 분석 서비스의 공개 안내와 별개로 OpenAPI 협약/사용자 제한이 있다. Play 배포 자동매매 분석 권한으로 단정하지 않는다.

## 증권사에 확인할 정확한 계약 질문

1. KRX 지정가 IOC cashBuy/cashSell 응답 mkt_orr_no를 dailyOrderExecution itg_orr_no에 연결하는 공식 필드/조회 경로는 무엇인가? 주문채번팀점·거래일·자시장/모주문을 포함해 유일성을 보증하는가?
2. IOC 부분체결 후 취소와 거절을 완결로 판단하는 조건, 정정/체결 정정·수수료/세금 확정액의 주문별 조회 경로는 무엇인가?
3. period OHLC/vol은 이미 조정되어 있는가? prtt_rate/vol_prtt_rate 적용식, 무권리락 구간의 기본값, 가격·거래량 적용 날짜와 누적 기준, 권리락/배당락 포함 범위는 무엇인가?
4. 제3자 Android 앱의 개인 자동매매에 이 데이터 표시·가공을 허용하는 이용 조건은 무엇인가?

이 문서는 문의 초안이며 외부로 전송하지 않았다. 원문/응답 값/고객 정보는 포함하지 않는다.
