# 이슈 #25 행사 명단 비교와 AI 안내 초안

- 작업 ID / 날짜 / 상태: issue25 · 2026-10-14 · 구현·모의 검증 완료 / 이슈 전체 평가 incomplete
- 요청: [이슈 #25](https://github.com/WookJaes/alf.io/issues/25) 구현·관련 검증·커밋까지. 푸시·외부 게시 제외. 영향이 큰 선택은 사용자 결정.
- 실행 모드: 현재 Codex 순차 구현·자체 QA·Review. 하위 에이전트 미사용. 자동 E2E를 사람이 직접 수행한 QA로 표시하지 않는다.
- 초기 Git: fix/29-billing-document-access, HEAD 5450d74274fb8775facd33c672f0885e21cac00b, 변경 없음. GitHub API로 원격 main ca30157eb541ff9484c9ebf1f94302083f7f341a 확인. 로컬 main 동일. feat/25-attendee-assistant를 main에서 생성.
- 사용자 확정: OpenAI API·gpt-5.4-mini-2026-03-17, DB 암호화 보관·24시간, 검증된 표현을 AI가 선택·조합. API 키 없음으로 모의 서버 검증 후 커밋을 명시적으로 선택.
- 영향 사전 안내: 신규 비교 결과·호출 한도 테이블 및 서버 암호화 키 설정 필요. 기존 예약·티켓·결제 테이블 수정 없음. 새 SDK·라이브러리 의존성 없음. 기존 API 유지.
- 환경: macOS 27.0.1/aarch64, Corretto 25.0.4.1, Gradle 9.7.1, Docker 29.5.2, PostgreSQL16.13, Chrome/chromedriver155.0.8059.79, Selenium4.43.0. 저장소 루트 실행. 격리 dev/disable-jobs 앱·DB와 loopback 모의 OpenAI, 합성 데이터만 사용.
- 명세·사용법: [명단 비교 안내](../../attendee-reconciliation.md), [평가 입력·절차와 미측정 항목](../../attendee-assistant-evaluation.md).
- 최종 검증 대상: 기준 HEAD ca30157eb5 + 이번 제품 diff, 제품 해시 1812064d4893dffed544d13271247d9595b63072727e0d37adec5e10363a1073. 검증 이후 제품 코드 변경 없음. 결과 요약 기록만 갱신하며 커밋 후 새 HEAD에서 재실행했다고 표시하지 않는다.
- 최종 실행 ID/선언: issue25-commit / ai/local-state/issue25-config/plan.json. regression/frontend/e2e/structure/qa/review 필수, QA·Review 자체 수행. 로컬 상태·XML·원본 오류 진단·화면 캡처는 Git 제외 경로에 보관한다.
- gate/resume: 모든 선언 항목 통과, 각각 exit0. 실제 모델·사람 수동 대비 평가는 사용자 선택에 따른 미검증으로 완료 선언에서 구분했다. 이슈 전체 완료 조건 충족으로 확대하지 않는다.

## 구현

| 완료 기준 | 변경·확인 내용 | 실제 검증 |
|---|---|---|
| 행사 권한 | 모든 API의 기존 ownership 검사, DB event/user/id/expiry 조건 | HTTP403·실제 owner/admin/outsider 로그인·권한회수403 |
| CSV 입력 안내 | UTF8/BOM·필수열·열수·표준UUID·용량/행/필드 제한, 안전한 코드·레코드번호 | 파서13건, 화면 필수열 안내·같은 파일 재선택 |
| UUID 판정·근거 | 정상·한쪽누락·중복·불일치, 복수유형·모든레코드 보존, 동일예약 티켓 독립 | comparator3건·실제 정상1/확인필요3·중복레코드3/4 |
| AI 근거·실패 | 익명참조/유형만 전송, strict schema와 정확한 집합 검증, 검증문구 조합 | 모의 HTTP·500/timeout/F-999, 비교GET200 유지 |
| 초안 수정·복사 | 선택1~20개·설명·근거확인·수정·복사, 발송/예약변경 코드 없음 | Chrome의 실제 수정·clipboard write/read 일치 |
| 개인정보·보관 | 원본CSV/파일명/추가열 미저장, AES256-GCM nonce/AAD, DB24시간·삭제·정리 | cipher2건·DB4건·암호문/집계 원문 미포함·86400초 |
| 한도·동시성 | 결과10개/사용자행사·전체100MiB·개별10MiB, DB쿼터 사용자10/전체100회 매시 | 이력상한·거절rollback·동시4요청 중1lease |
| 관리자 화면 | 기존 Angular 메뉴/라우트와 Lit, 이력·필터·페이지·근거·초안 | 실제 화면·기본필터표시·새로고침재조회·삭제404 |
| 기존 동작 | 내보내기·예약·결제 처리/기존 API 유지 | 기존 export8건·예약목록200·기존 E2E3건 |
| 수동 대비 평가 | 합성 정답표와 측정 절차, 미측정값·실제모델 부재 명시 | **미완료**: 사람시간/수정횟수/실제모델품질/청구비용 미측정 |

## 최종 자동 검증

하네스의 `run issue25-commit <항목>`으로 다음 실제 명령을 실행했다. 실행 영수증은 전체 새 XML 파일·실제 testcase·시각·코드/환경/조건 해시·체크섬을 연결한다.

```sh
./gradlew test --tests 'alfio.manager.attendee.*Test' --tests alfio.controller.api.admin.AttendeeReconciliationApiControllerTest --tests 'alfio.controller.api.admin.EventApiControllerIntegrationTest.export*' --tests alfio.controller.api.admin.EventApiControllerIntegrationTest.testGivenListOfAttendeesWithFieldsUploadThenSameFieldsAvailableOnCsvDownload --console=plain --no-daemon --no-build-cache -Pverbose -Dpgsql.version=16.13 -I ai/local-state/issue25-config/reports.gradle -Dharness.reports=ai/local-state/issue25-commit
./gradlew e2eValidation --console=plain --no-daemon --no-build-cache -Pverbose -I ai/local-state/issue25-config/reports.gradle -Dharness.reports=ai/local-state/issue25-commit -Dattendee.e2e.artifacts=ai/local-state/issue25-commit-artifacts
frontend/admin/node_modules/.bin/tsc --project frontend/admin/tsconfig.json
python3 -B ai/tests/check.py
```

- regression: 2026-10-14 12:01:20–12:01:36 KST, exit0, `:test` EXECUTED, XML9개 전체 **실제47건/실패0/오류0/스킵0**. controller3·기존export8·settings2·comparator3·parser13·manager4·AI8·cipher2·DB4.
- e2e: 2026-10-14 12:02:19–12:03:19 KST, exit0, `:e2eValidation` EXECUTED, XML4개 전체 **실제4건/실패0/오류0/스킵0**. 기존 NormalFlow/ValidationBrowser/BillingDocumentDownload 각1·신규AttendeeReconciliation1. 이전 XML을 복사하거나 이전 결과를 새 실행으로 표시하지 않았다.
- frontend: 2026-10-14 12:04:49–12:04:50 KST, 타입 검사 실제 exit0. 관리자 소스 변경 후 release E2E 준비에서 `frontendAdminBuild`의 tsc/Vite가 실제 실행됐다. 최종 commit E2E는 변경 없는 동일 관리자 번들을 재사용했으며 JAR과 dist의 일치를 확인했다. 기존 public frontend 캐시를 새 빌드 통과로 표시하지 않는다.
- structure: 2026-10-14 12:04:51–12:04:51 KST, 실제 exit0. 최종 공백 검사 exit0.
- 합계 **최종51건**. 준비·실패·이전 성공 실행을 합산한 수치가 아니다. 전체 제품 테스트·원격CI를 실행했다고 주장하지 않는다.
- 실제 E2E JAR의 신규 controller/client 클래스는 현재 컴파일 클래스와 바이트 동일. 관리자 manifest·main-B0ANGvRw.js는 현재 dist와 바이트 동일, 번들SHA256 f16d6052ff1977e3a33d20b9ce4d6c5f1c06fa3f4d597d17931921c683c91b99.
- 최종 화면 합성 캡처 ai/local-state/issue25-commit-artifacts/comparison.png, SHA256 47ed4cd06cd5d1722e34d091fdf349520f7d2a92c13ec5d7f97809ca18f174f6. 레이아웃·오류안내·집계·이력선택·필터 일치를 열어 확인했다.

## 실제 화면·DB 결과

실제 소유자 로그인→기존 행사 메뉴→CSV 업로드에서 정상1·확인필요3, 유형별1개를 확인했다. 동일 예약의 여러 티켓과 중복+불일치를 독립적으로 표시했다. AI 입력에서 이메일·티켓UUID를 제외하고 검증된 유형에만 초안을 연결했다. 수정한 합성 초안이 실제 클립보드에서 같은 값으로 읽혔다.

모의 AI500·시간초과·틀린 참조에 안내를 표시하고 기존 결과GET200을 유지했다. 새로고침 후 DB이력 재조회, 직접 삭제404, 재비교, 기존 예약목록200, 같은 CSV 수정·재선택 시 필수열 오류와 기존 결과200을 확인했다. 실제 admin은 같은 행사라도 다른 생성자의 결과404, 다른 조직 owner는 실제 로그인·관리자목록200 뒤 대상403, 생성자 소속권한 회수 뒤 기존결과403이다.

실제 DB 통합에서는 암호화 왕복·원문미포함·사용자/행사분리·만료접근차단/정리·이력10개상한·직접삭제·행사삭제cascade·호출한도거절시lease rollback·동시4요청 중1개만 획득을 확인했다. 새 마이그레이션이 실제 앱·통합DB에서 적용됐고 앱의 data migration failure 검사도 통과했다. 운영 DB를 변경한 결과가 아니다.

## 실패·조치·재검증

- 기본 sandbox의 .git/index.lock와 Gradle cache lock 쓰기 거부 → 승인된 범위의 escalation으로 브랜치 생성·검증. Docker 소켓 없음 → 설치된 Docker 시작 후29.5.2 확인. 새 의존성 설치 없음.
- 초기 컴파일: Jackson iterator의 var가 Object로 추론, Lit 클래스 private remove가 HTMLElement.remove와 충돌 → 명시적 MappingIterator 타입·메서드명 변경. 컴파일/타입 재검증 성공.
- issue25-attempt1: 실제44건 중11실패. CsvSource가 줄바꿈 있는 입력을 여러 테스트로 해석한 테스트 준비 문제 → MethodSource로 교체. 다음 신규29건 실패0. 기존 export8건 실패0 확인. DB4건 별도 실제실행 성공.
- E2E attempt1: frontendAdminBuild의 소스 입력 누락으로 이전 번들 사용, 신규 컴포넌트 기다림 실패 → 관리자 src/config를 빌드 입력에 추가. 새 관리자 번들 실제 생성.
- attempt2: 초안수정·복사 뒤 고정 nav가 generate 클릭을 가림 → 화면 중앙으로 스크롤 후 실제 click하는 테스트 보완.
- attempt3: 기존 예약목록 검증 URL의 reservation prefix 누락 → 기존 API 경로로 수정. 제품 조회 정책 변경 없음.
- 초기 final: 같은 파일을 수정·재선택할 때 이전 File 상태로 전송 실패 → file input 값 초기화와 선택파일 표시, 재선택/연결 안내. 검증 기록은 실행중 병행 갱신으로 일부 항목 누락이 있어 최종 release에서 모든 상태 변경을 순차 실행했다.
- verified: 회귀47·E2E4 성공 후 화면 QA에서 기본 select가 실제 ATTENTION 조건과 달리 ALL을 표시함을 발견 → option.selected 명시 및 E2E 표시 assertion 추가. 최종 release에서47+4·타입·구조를 새 실행/새 XML로 재검증 성공.
- Chrome/CDP155에 대해 Selenium이153을 대체하는 경고가 있었지만 실제 로그인·메뉴·복사·조회 검증은 통과했다. 다른 OS 재검증으로 확대하지 않는다.

## 자체 QA·Review·남은 범위

최종 diff·신규 제품/테스트·XML13개/51건·실제 화면 캡처·완료 조건을 자체 대조했다. 행사/생성자 권한, UUID기준, 암호화/AAD, CSV·AI 본문/키의 로그 미노출, DB 한도와 실패rollback, 참조/유형 검증, Lit 텍스트 출력, 요청 취소/version, 기존 처리 불변을 확인했다. 최종 차단 발견0. begin→실제 확인→근거record→gate/resume를 수행했다. 별도 에이전트·사람의 독립 리뷰가 아니다.

미검증: 실제 OpenAI 모델품질·계정접근·실제 토큰/청구액·사람 수동대비 시간/정확도/수정횟수. 사용자 요청에 따라 모의 검증 후 커밋하며, 해당 평가 조건까지 해결했다고 주장하지 않는다. 실제 API호출 비용은0이고 모의 응답의 합성 토큰을 실제 사용량으로 환산하지 않았다. 전체 제품테스트·원격CI·다른OS/모바일·실제 결제/외부메일은 영향범위 밖 또는 외부 실행 제외로 미실행.

사용하려면 서버 암호화 키를 먼저 설정해야 하며 AI 사용은 API키와 ai-enabled 설정이 추가로 필요하다. 실제 사용·평가 절차는 연결 문서에 남겼다. 요청 범위 파일만 Conventional Commit으로 저장하고 푸시·PR·이슈 변경은 하지 않는다. 최종 커밋 식별자는 Git log와 완료 보고로 확인한다.

- 커밋 전 스테이징 공백 검사에서 신규 AI 테스트의 공백1곳을 정리했다. 테스트 의미 변경은 없으나 보수적인 대상 해시 규칙에 따라 새 issue25-commit 실행에서47+4·타입·구조와 자체 QA/Review를 다시 확인했다.
