# 작업 기록 — 이슈 #27 청구 문서 Excel

- 이전 실행 기록(착수 시 이미 존재한 MD·로컬 영수증, 이번 신규 실행과 구분). 이전 구현 시간을 이번 시간에 합산하지 않음.
- 작업 ID / 날짜 / 상태: `issue27-final-20261013` / 2026-10-13 / completed (검증 당시 변경 내용 기준).
- 요청·완료 기준: [이슈 #27](https://github.com/WookJaes/alf.io/issues/27). STATUS·CONFIRMATION_DATE 끝에 추가, 기존 컬럼·문서 조회·행사 권한 보존, 상태·확정일 없음·취소·행사 시간대 경계·실제 다운로드 검증 및 MD·Notion 기록.
- 실행 모드·실제 담당: 현재 Codex 1인스턴스에서 구현·검증·자체 QA·Review 순차 수행. 고유 역할은 Dev·QA·Review 3종이며 에이전트 3개라는 뜻이 아니다. 사용자에게 분담 요청을 받지 않아 독립 에이전트 없음.
- 초기 Git: `fix/23-ci-duplicate-runs`, HEAD `65100a82830ffd9fb6f95fb2879808632ea01e9f`, clean. 원격 main fetch 후 `bd2119d220a9f1346e725c7da1e94c7e76d0e812` 기준 `feat/27-billing-document-status` 생성. 기존 변경 없음.
- 재현 환경·실행 위치: 저장소 루트, macOS 27.0.1 (26A434) aarch64, Corretto Java 25.0.4.1 (25.0.4.10.1), Gradle 9.7.1, Docker 29.5.2, Testcontainers `postgres:15`, JVM `Asia/Seoul`, Jetty random-port HTTP. PostgreSQL 정확 패치 버전·브라우저 버전은 미측정. 기존 Stripe mock은 통합 테스트 공통 환경이며 실제 결제·메일은 수행하지 않음.
- 로컬 선언: `ai/local-state/issue27-config/plan.json`, 환경 요약·보고서 출력 전용 `reports.gradle`을 inputs로 연결. 필수 regression·qa·review, QA/Review independent=false. 하네스 구조·의존성·DB 마이그레이션 변경 없음.
- 검증 대상: 기준 main + 기능·테스트 4파일 및 사용 안내 1파일의 당시 내용. 결과 요약 MD는 현행 하네스 식별 제외 범위. 최종 run은 코드·환경 변경 없이 실제 46건 실행, `:test` EXECUTED, fresh XML 3개 전체 확인.
- 하네스 버전·대상 내용 해시: a84b508a4f0881d625536c2a3bf2fcd69af99fdf13f8a39c84d9f431866271fb / product be7d9ddf06ec03085ec479ee94a6afa9bbcb3d6c247835f98c6b0394c3cc16dd. 하네스 코드 기준 main의 `ai/harness/run.py`, WORKFLOW·local-validation에 따라 수행.
- 증거 위치: `ai/local-state/issue27-r6-xml/`, `ai/local-state/issue27-downloads/`, `ai/local-state/issue27-config/qa.md`, `review.md`. 로컬 실행 영수증으로 시각·명령·종료 코드·건수·체크섬을 연결했으며 원본 로그·결과 JSON을 저장소나 Notion에 첨부하지 않음. ignored 증거는 이 로컬 checkout에서 보존되고 커밋에는 포함하지 않음.
- gate/resume: 2026-10-13 12:31:30 KST 이전 최종 regression·qa·review 모두 passed, 각각 gate/resume exit0. 커밋 이후 HEAD 변화는 현행 하네스에서 기존 결과를 보수적으로 무효화한다. 이 판정을 우회하지 않고 커밋 전 검증 시점과 커밋된 파일 내용의 일치를 구분해 보고한다. 커밋 후 새 테스트로 표시하지 않는다.

## 주요 변경·판단

`EventApiController.getAllDocumentsXls`에 6행을 추가했다. 예약의 현재 enum 상태를 STATUS로, 예약의 confirmationTimestamp를 행사 시간대로 `withZoneSameInstant` 변환한 `yyyy-MM-dd HH:mm` 값으로 출력한다. 확정 시각이 null이면 빈 문자열이다. `Generated on`은 기존 문서 생성 시각을 유지한다. 일반 출력 11→13열, 이탈리아 전자청구 출력 15→17열이며 기존 필드 이름·순서·값은 동일하다.

`streamAllDocumentsFor`와 `BillingDocumentRepository`는 수정하지 않았다. 기존 VALID INVOICE의 최신 generation_ts와 VALID 기타 문서 조회 정책을 유지한다. fixed-clock 합성 데이터에서 같은 생성 시각의 청구서 2개가 포함되는 것도 기존 정책이다. 일반 취소는 CANCELLED, 대변전표 취소는 CREDIT_NOTE_ISSUED로 기존 처리 결과를 그대로 출력한다. 예약·결제·취소 정책은 바꾸지 않았다.

## 변경과 검증

| 완료 기준 | 변경·확인 내용 | 명령·절차 | 종료 코드·실제 건수 | 증거·결과 |
|---|---|---|---|---|
| 컴파일 | 최종 Gradle test의 재실행으로 Java·테스트 소스 컴파일 | 아래 회귀 명령, rerun-tasks | 0, 테스트 건수와 별개 | 실제 테스트 실행까지 완료. 별도 bootJar·프론트 빌드 미실행 |
| 예약 상태·빈 시각 | enum 전체 14종, null은 빈 값 | EventApiControllerDocumentsTest | 최종 단위 25건, 실패·오류·스킵 0 | JUnit 1파일 |
| 시간대·경계 | 서울 자정, 미국 연도/날짜 경계, 취리히 DST 6사례 | 같은 단위 테스트 | 위 25건에 포함 | withZoneSameInstant 결과 명시 기대값 |
| 기존 컬럼·권한 | 일반11열 값·전자청구 기존15열/빈값·값 있음, 빈 문서, 접근검사 선행·거절 시 조회 없음 | 같은 단위 테스트 및 자체 diff 대조 | 위 25건에 포함, diff check 0 | Repository·Manager·권한 정책 변경 없음 |
| 기존 청구·취소 회귀 | 청구서 생성·결제·취소·대변전표 | BillingDocumentCreationIntegrationTest 9건, BillingDocumentManagerTest 12건 | 21건, 실패·오류·스킵 0 | PostgreSQL Testcontainers, JUnit 2파일 |
| 실제 Excel 다운로드 | 실제 Jetty HTTP 요청 4회, HTTP200·MIME·attachment 이름, 별도 ZIP/XML 확인 | 합성 Principal 필터 + 실제 Controller/AccessService/DB, QA | 다운로드4파일·8출력행, ZIP/XML 절차 exit0 | 시점별 중복 행 합계이며 고유 문서 8건 아님 |
| 실제 DB | 일반 예약1건 대기→확정→취소, 확정 시각 갱신1행, 별도 대변전표 예약1건 | 실제 예약/취소 서비스와 JDBC 조회 | confirmation_ts update 영향1행, 현재 CANCELLED·문서 count 일치 | 테스트 내 query/assert, 이후 공통 DataCleaner 정리 |
| 권한 없는 HTTP | 실제 AccessService의 AccessDenied·응답 본문·attachment 없음 | 합성 비소유자 요청 | 차단1회 | 기존 컨트롤러 ExceptionHandler가 HTTP500으로 반환. 범위 밖 한계 |
| 자체 QA·Review·gate | 실물·diff·요구사항·증거 대조, 필수 완료 판정 | begin→확인→record→gate/resume | 최종 모두0 | 동일 Codex 자체 수행, 독립 리뷰 아님 |
| 화면·로그인·Excel 화면 | 제품 UI 변경 없음, 다운로드 서버 기능을 직접 검증 | 미실행 | 미실행, 0건 통과로 표시하지 않음 | 테스트용 Principal로 로그인 대체; 실제 브라우저/Excel 렌더링 미검증 |
| DB 마이그레이션·CI·전체 제품 묶음 | 스키마 변경 없음, push 제외 요청 | 미실행 | 미실행 | 관련 46건 외 전체 회귀·원격CI 미확인 |

최종 회귀 명령(저장소 루트):

```sh
./gradlew test --tests alfio.controller.api.admin.EventApiControllerDocumentsTest --tests alfio.controller.api.v2.user.reservation.BillingDocumentCreationIntegrationTest --tests alfio.manager.BillingDocumentManagerTest --console=plain --no-daemon --no-build-cache --rerun-tasks -Dpgsql.version=15 -Duser.timezone=Asia/Seoul -Dbilling.download.artifacts=ai/local-state/issue27-downloads -I ai/local-state/issue27-config/reports.gradle -Dharness.reports=ai/local-state/issue27-r6-xml
```

최종 실물 파일(헤더 제외 문서행):

| 파일 | 문서행·상태·확정일 | 바이트·SHA-256 |
|---|---|---|
| `ai/local-state/issue27-downloads/cancelled.xlsx` | 2 / CANCELLED / 2026-10-13 01:30 | 3037 / `68d903237a622ae1494d91ba1f26eb74f8195aa9525a0d33467196b84fc30764` |
| `ai/local-state/issue27-downloads/completed.xlsx` | 2 / COMPLETE / 2026-10-13 01:30 | 3037 / `59e777c4f8413ca455f7ac5041ff2f768645c5c47981470f8fd4e4ccde9fe42a` |
| `ai/local-state/issue27-downloads/credit-note.xlsx` | 3 / CREDIT_NOTE_ISSUED / 2026-10-13 12:00 | 3108 / `332f677d9c41093478e72665a300b1370ae8572dcf6a58942a18b92ecf8b3356` |
| `ai/local-state/issue27-downloads/pending.xlsx` | 1 / OFFLINE_PAYMENT / 빈 값 | 2965 / `2537674e107ef47a31df9e651d015b0e5a330faf9472a1a516a745c1ef99ff86` |

실행별 실제 건수(실행/실패/오류/스킵, 같은 테스트 반복을 신규 건수로 합산하지 않음):

| 실행 ID | UTC 시작 → 종료 | 종료 코드 | 실제 건수 |
|---|---|---|---|
| issue27-20261013 | 2026-10-13T03:25:39.645556+00:00 → 2026-10-13T03:26:05.507293+00:00 | 1 | 45/1/0/0 |
| issue27-r2-20261013 | 2026-10-13T03:26:32.168650+00:00 → 2026-10-13T03:26:51.712132+00:00 | 1 | 45/1/0/0 |
| issue27-r3-20261013 | 2026-10-13T03:27:15.940895+00:00 → 2026-10-13T03:27:35.738365+00:00 | 1 | 45/1/0/0 |
| issue27-r4-20261013 | 2026-10-13T03:28:08.696741+00:00 → 2026-10-13T03:28:28.385496+00:00 | 1 | 45/1/0/0 |
| issue27-r5-20261013 | 2026-10-13T03:29:04.601630+00:00 → 2026-10-13T03:29:24.317715+00:00 | 0 | 46/0/0/0 |
| issue27-final-20261013 | 2026-10-13T03:30:07.204371+00:00 → 2026-10-13T03:30:27.081910+00:00 | 0 | 46/0/0/0 |

## 시간 측정

- 실제 시작: **2026-10-13 12:22:39 KST (03:22:39 UTC)**, 지침 확인·조사·환경 준비 직전 clock.
- 필수 검증·Review·gate 완료 후 실제 종료: **2026-10-13 12:31:30 KST (03:31:30 UTC)**.
- 전체 처리 시간: **531초 = 8분51초 = 8.85분**. 초기 요구사항 확정에 필요한 준비도 포함한다. Notion 기존의 ‘요구사항 확정 후’ 정의와 다르므로 이번 실행 정의를 명시하고 다른 이슈와 개선율 비교를 하지 않는다.
- 회귀 자동 명령 6회 실행 구간 합계: 124.485068초(컴파일·컨테이너 준비·실행·도구 대기를 함께 포함). 순수 AI 대기 시간으로 계산하지 않으며 531초에 다시 더하지 않는다.
- AI 추론·도구 응답의 순수 대기: 미측정. CI는 미실행이므로 CI 대기 없음. 개인 사정으로 중단된 구간: 관측 없음. 사람 직접 검토·수정 시간: 각각 **미측정**; 사용자 제공 값도 없음. 에이전트 구현·리뷰 시간을 사람 시간으로 변환하지 않음.
- 커밋·최종 기록·Notion 반영·재조회·보고는 측정 종료 이후 후처리로 전체531초에서 제외한다.

## 실패·리뷰·재개·하네스 기여

| 단계·문제 | 조치 | 재검증·사람 개입 |
|---|---|---|
| 준비 환경: .git/FETCH_HEAD 쓰기·GitHub 네트워크 제한 | 승인된 Git 작업 escalation, main fetch·이슈 조회·분기 | exit0, 사람 추가 개입 관측 없음 |
| 준비 환경: Docker socket 제한 | 환경 조회 escalation | Docker29.5.2 확인 exit0 |
| 첫 회귀: 청구서 주소 누락·422, 45건 중1실패 | 합성 필수 청구 주소 입력 | 둘째 예약 생성 정상, HTTP 환경 문제로 실패 |
| 둘째 회귀: 테스트 서버 DispatcherServlet 미등록·404 | 테스트 전용 Dispatcher 등록 | 셋째 실제 Controller 호출 확인 |
| 셋째 회귀: 보안 필터가 합성 Principal을 덮어씀·500 | 테스트 필터 setMatchAfter(true) | 넷째 실제 다운로드3개 확보 |
| 넷째 회귀: 대변전표 취소를 CANCELLED로 기대 | 기존 코드·DB에서 CREDIT_NOTE_ISSUED 확인, 일반 취소/대변전표를 따로 검증 | 다섯째46건 통과. 제품 동작에 맞춘 구분이며 실제 취소 검증을 삭제하지 않음 |
| 자체 리뷰: 테스트 필터가 다른 행사 요청을 감쌀 가능성1건 | 헤더+다운로드 경로에 한정, 권한 거절 본문도 확인 | 최종46건 통과, QA·Review 새 begin/record |
| 하네스 운영: 첫 regression running 중 같은ID Review begin 호출 | 종료 저장에서 begin 유실을 show로 확인, 최종에는 순차 수행 | Review 완료로 주장하지 않음. 최종 새 begin/record 정상 |
| 수동 근거 작성 도구: Python stdin UTF-8 오류로 review 근거 누락 | 명시 encoding 선언, 근거 재작성 | 중간 gate/resume 각각1로 거부 → record 후 각각0 |

- 실행 중 실패→보완→재검증 복구 주기 **8건**: 환경2(동일 escalation으로 해결한 Git/GitHub 묶음1 + Docker1), 테스트 준비4, 하네스 운영1, 도구1. 정상 대기·읽기 재조회·리뷰 보완 후 성공 재실행은 실패 재시도로 세지 않는다.
- 자동 회귀 6회: 실패4회, 성공2회. 최종46건만 최종 검증으로 보고하며 누적 반복 건수를 신규 테스트 수로 표시하지 않음.
- 리뷰 발견·제출 전 보완 **1건**(테스트 격리), 신규 운영 결함0. 완료 리뷰 제출 후 재작업0, 외부 리뷰 제출0. QA 신규 제품 결함0.
- 필수 검증 누락: 최종0. 중간 Review 근거 작성 실패로 record 불가·gate 거부1사건이 있었고 보완 완료. 같은 사건의 record/gate/resume 실패를 각각 독립 결함으로 부풀리지 않음.
- 하네스 기여: fresh 전체 JUnit 실제46건·실패/스킵·Gradle 실행상태를 확인하고 실패 실행을 완료로 승인하지 않음. 수동 근거가 누락됐을 때 gate가 완료를 거부함. 최종 대상·시각·체크섬·자체 수행을 연결. 제품 결함 탐지를 하네스 단독 기능이라고 주장하지 않음.
- 하네스 한계: 초기 FAILED Gradle 출력의 상태 요약은 AMBIGUOUS 안내였고 실제 실패 원인은 fresh XML에서 확인. 같은ID 동시 상태 수정은 강제로 방지하지 않으므로 수행자가 순차 규칙을 지켜야 한다. 독립 검토·원격CI·브라우저를 대체하지 않음. 다른 이슈 대비 속도 차이를 하네스 인과 효과로 단정하지 않음.

## 이번 요청 재개·정정 — issue27-current-20261013

- 실제 착수: 2026-10-13 13:08:48 KST (04:08:48 UTC), 조사·환경 준비 직전 clock. 기존 구현 6파일이 main에서 이미 스테이징됨. 기존 변경을 모두 보존했다. 이전 기록의 clean/브랜치는 이번 초기 상태가 아니다.
- 최신 원격 main: fetch exit0, HEAD와 origin/main 모두 `bd2119d220a9f1346e725c7da1e94c7e76d0e812`. `feat/27-billing-document-status`를 이 기준으로 새로 생성했다. 하위 AGENTS.md 없음, 루트 EditorConfig·기존 Java/JUnit 관례 확인.
- 이번 작업은 기존 구현의 리뷰·재검증·기록 정정이며 새 구현 소요 시간을 복원하지 않는다. 앞 절의 531초는 이전 기록의 관측값이다. 이번 구간에 합산하거나 상단 비교표에 신규 구현 시간으로 넣지 않는다.
- 선언: `ai/local-state/issue27-current-config/plan.json`. 실행 영수증: `ai/local-state/issue27-current-20261013/`. 필수 regression/qa/review, 자체 수행. 하네스·환경 inputs 및 제품 내용 해시는 앞 기록과 동일. 하네스 자체 변경·새 의존성·공개 API 요청 변경·DB 마이그레이션 없음.
- 실제 회귀: 04:09:53.838208–04:10:16.132220 UTC, exit0, `:test EXECUTED`, **46건/실패0/오류0/스킵0**. 단위25, 청구문서 통합9, Manager12. 새 XML3개 전체 `ai/local-state/issue27-current-xml/` 확인. 컴파일도 실행됐으며 bootJar·프론트 빌드는 미실행.
- 명령: 앞의 최종 회귀 명령에서 다운로드 경로를 `ai/local-state/issue27-current-downloads`, XML 경로를 `ai/local-state/issue27-current-xml`로 바꿔 현행 `run`으로 실제 실행. 캐시·이전 실행 재사용 아님.
- 실제 Excel: Jetty HTTP200·MIME·attachment 검증 후 **4파일·8출력행**. pending1/OFFLINE_PAYMENT/빈 값, completed2/COMPLETE/2026-10-13 01:30, cancelled2/CANCELLED/동일 확정일, credit-note3/CREDIT_NOTE_ISSUED/2026-10-13 12:00. 별도 Python ZIP/XML 확인 exit0, 전 행13열·끝2열 및 값 확인. 이번 실물의 크기·SHA256은 `ai/local-state/issue27-current-config/qa.md`에 보존. 앞 실물 표는 이전 실행 파일이다.
- DB: 실제 예약2건의 기존 서비스 처리, 확정시각 update1행, CANCELLED 조회 및 문서 count 정합성 통과. HTTP 비소유자 거절1회·attachment 없음. 실제 로그인 대신 테스트 Principal을 사용했으며 기존 AccessService는 실제 실행. 기존 HTTP500 반환 한계 유지.
- 화면: UI·브라우저·Excel 앱 렌더링 미실행. 다운로드 자체는 실제 HTTP 서버로 확인. 스키마 변경 없음; 마이그레이션·전체 제품 회귀·원격CI·실제 결제·외부메일 미실행. 관련 검증 범위 밖이거나 요청의 push 제외에 따름.
- 자체 리뷰: 제품 diff6행, enum14종·서울 자정/미국 연도 경계/취리히 DST6사례, 기존 일반11/전자청구15열 및 조회SQL·권한 보존을 fresh46건·실물과 대조. 신규 제품 결함0. 기존 기록의 원격 커밋 링크·2커밋 계획·Notion 저장 완료 주장은 현재 Git/Notion 상태와 불일치해 이 절로 정정했다. 이번 커밋 전 실제 Notion 갱신·재조회를 수행한다.
- 재시도: Git fetch sandbox 실패→escalation 성공1, Docker 조회 sandbox 실패→escalation 성공1, GitHub connector 필수 인자 누락→repo/issue_number 지정 성공1. 실행 중 재시도3건(환경2·도구1). 회귀 실패0. Review 근거가 완료되기 전 show의 거부는 정상 대기 판정이며 결함·재시도로 계산하지 않음.
- 하네스 운영 사건1: regression 실행 중 qa begin을 조기 호출해 종료 저장에서 not_run으로 복원됨. 종료 후 새 begin→실제 실물 확인→record로 순차 복구. 테스트 재시도와 별도로 기록. 하네스 변경 없이 사용 규칙 준수. 사람 개입 관측 없음.
- 하네스 기여: 새 XML 전체·실제46건·Gradle 실행 상태·내용/환경 해시를 연결하고 Review 실행 중 완료를 거부. 상태 유실은 show에서 발견해 올바르게 재기록. 필수 검증 최종 누락0, 완료 리뷰 제출 후 재작업0, 외부 리뷰 게시0. 독립 리뷰·브라우저 검증을 대체하지 않음.
- 문서 검사: `python3 -B ai/tests/check.py` exit0, `git diff --check` 및 `git diff --cached --check` exit0. 하네스 코드 변경이 없어 하네스 회귀 전체는 미실행.

## 이번 시간·완료 판정

- 필수 회귀·자체 QA·Review·gate/resume 완료 후 실제 종료: **2026-10-13 13:11:43 KST (04:11:43 UTC)**. 시작13:08:48부터 **175초=2분55초**. 이번 재개 구간이며 최초 구현 전체 시간은 이번에 미측정. 기존531초와 합산하지 않음.
- 회귀 명령 실행22.294012초(컴파일·컨테이너·테스트·도구 응답 포함), 전체175초에 이미 포함. 순수 AI 대기 미측정, CI 미실행으로 CI 대기 없음. 개인 중단 관측 없음. 사람 직접 검토·수정 각각 미측정, 에이전트 시간을 사람 시간으로 계산하지 않음.
- 최종 regression/qa/review 모두 passed, gate/resume 각각 exit0. 필수 제품 검증 누락0. 요청 전체의 Notion 저장·단일커밋은 후처리에서 완료. HEAD가 변하는 커밋 후에는 기존 게이트 결과가 보수적으로 무효화되므로 커밋 전 대상 검증과 커밋 내용 일치를 구분하며 새 테스트로 주장하지 않음.

## 커밋·기록 위치·후처리

- 앞 기록에 적혔던 두 커밋 계획·원격 링크는 현재 이번 변경의 근거가 아니므로 제거했다. 사용자 승인에 따라 기능·회귀·[사용 안내](../../billing-document-export.md)·이 MD를 **커밋1개**로 묶는다. 로컬 식별자는 커밋 후 Notion과 최종 보고에 기록한다. 원격 commit/MD 링크는 push 전 확인된 근거로 사용하지 않는다.
- [Notion 작업 기록](https://app.notion.com/p/alf-3f1d39b5368d803e8cfdd857b77c88ba)의 맨 아래 기존 준비 틀을 이슈 #27 단일 기록으로 갱신하고 저장 후 재조회에서 제목1개·175초·46건·미측정·한계를 확인했다. 다른 기존 기록·상단 비교표 수치는 보존하고 이번 측정 경계 차이를 표시한다.
- 기록 정리·Notion 반영·재조회·커밋·보고는 필수 제품 검증/리뷰 종료 후 후처리로 분리한다. 원격 push·PR·외부 댓글·병합은 수행하지 않는다.
