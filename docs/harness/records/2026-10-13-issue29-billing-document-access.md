# 이슈 #29 청구 문서 다운로드 권한 응답

- 작업 ID / 날짜 / 상태: issue29 · 2026-10-13 · completed
- 요청: [이슈 #29](https://github.com/WookJaes/alf.io/issues/29). 비소유자 403·문서/파일/attachment 미노출, 실제 소유자 로그인→관리자 메뉴 다운로드, 기존 13열 및 STATUS/CONFIRMATION_DATE, 빈 확정일·취소·행사 시간대 날짜 경계, 기존 조회/권한 유지, 기존 E2E·CI 연결.
- 실행 모드: 현재 Codex 순차 구현·자체 QA·자체 Review. 독립 에이전트 미사용.
- 실제 시작: 2026-10-13 15:09:14 KST (06:09:14 UTC), 조사·환경 준비 직전 clock.
- 초기 Git: main, tracked/untracked/staged 변경 없음. #27 추가 검증 MD 미커밋 없음. 기존 #27 기록은 이전 근거이며 이번 재현 결과가 아니다.
- 기준: fetch 후 HEAD=origin/main=`850a36dcd7c059025cd2c56a940d5883cc983909`. 브랜치 `fix/29-billing-document-access` 생성.
- 지침: AGENTS.md·ai/WORKFLOW.md·Git 규칙·작업 양식·로컬 검증·verification/review·루트 EditorConfig 확인. 하위 AGENTS 없음.
- 환경: 저장소 루트에서 실행, macOS/aarch64, Corretto 25.0.4.1, Docker 29.5.2, 기존 ValidationEnvironment postgres:16.13, 기존 ValidationBrowser headless Chrome. Gradle/Chrome 실제 버전은 실행 출력으로 확인 예정.
- 초기 준비: git fetch sandbox exit1(FETCH_HEAD 권한)→승인된 escalation exit0. Docker sandbox 조회 실패→escalation exit0. 제품 실패와 구분한다.
- 재현 계획: 격리 앱·DB에서 관리자 로그인으로 합성 행사 준비, 서로 다른 조직의 ROLE_OWNER 두 계정 생성. 실제 폼 로그인으로 대상 행사 소유자 메뉴 다운로드 및 XLSX 확인. 다른 조직 소유자도 실제 로그인 및 관리자 API 접근 성공 확인 후 대상 Excel 요청의 상태/본문/헤더/파일 여부 확인. SQL 합성 자료는 제품 정책 구현과 구분한다.
- 검증 도구: 새 E2E는 수정 전 기준 상태에서 검증 준비용으로 사용하고 선행 커밋에는 이 MD만 포함한다. 제품 수정은 재현 기록 선행 커밋 이후 시작한다. 재현용 E2E의 거절 기대값은 HTTP500으로 고정하고 선행 커밋 이후 HTTP403으로 전환한다. Git 제외 Gradle init script로 새 검증 클래스를 실행 대상에 연결한다.
- 구현 계획: 다운로드 메서드의 행사 소유권 검사에서만 AccessDeniedException을 403으로 처리하고 즉시 반환. 공통 예외 처리·조회·컬럼·접근 권한 변경 제외.
- 실행 ID·선언: 수정 후 ai/local-state/issue29-config/plan.json으로 관련 회귀·실제 E2E·QA·Review 필수 선언 예정. 원본 로그·DB 덤프·실제 개인정보·비밀값 커밋 제외.

## 수정 전 재현

- 준비 도구 실패1: 첫 `./gradlew e2eValidation --tests alfio.e2e.BillingDocumentDownloadE2ETest --console=plain --no-daemon --no-build-cache -Pverbose -I ai/local-state/issue29-config/baseline.gradle` exit1, 1건/실패1/스킵0. Actions를 열어 Excel 링크가 숨겨져 timeout. Download 선택자로 정정.
- 준비 도구 실패2: 같은 명령(출력 범위 before2), exit1, 1건/실패1/스킵0. XPath의 첫 요소가 표시되지 않음. 기존 반응형 메뉴 중 표시된 요소를 찾도록 정정. 두 실행은 HTTP 권한 재현 성공이 아니다.
- 제품 비교 `git diff --exit-code origin/main -- src/main` exit0. 제품 수정 없음. 재현용 신규 E2E·Git 제외 설정·이 MD만 준비.
- 준비 도구 실패3: 동일 명령(출력 before3), exit1, 1건/실패1/스킵0. 메뉴 다운로드 및 13열/3행 값은 통과했고 비교 HTTP200·attachment 뒤 MIME에 `;charset=utf-8`이 붙어 문자열 완전 일치 실패. MIME 주 타입을 검증하도록 정정. 제품 오류가 아니다.
- 준비 도구 실패4: 동일 명령(출력 before4), exit1, 1건/실패1/스킵0. 소유자 전체 검증 및 실제 로그인 비소유자의 관리자 목록 HTTP200·대상 HTTP500까지 확인. 실제 앱은 거절 메시지를 JSON 문자열로 직렬화하여 따옴표가 존재했고, 통합 테스트의 plain 문자열 기대와 달라 본문 비교 실패. JSON 문자열은 파싱한 뒤 메시지 전체를 비교하도록 도구 수정. 이 실행에서는 이후 attachment·파일 검증이 실행되지 않았으므로 전체 재현 완료로 표시하지 않는다.
- 최종 수정 전 재현: 위 명령을 before5 XML 범위로 실행, **exit0, 실제1건/실패0/오류0/스킵0**, `:e2eValidation` 실제 실행, Gradle 25초. 제품 compileJava·bootJar·프론트 빌드는 UP-TO-DATE이고 신규 compileTestJava만 실행됐다. 캐시된 제품 빌드를 신규 컴파일로 표시하지 않는다.
- 대상 제품은 원격 main `850a36d`와 동일(src/main diff exit0). Chrome155.0.8059.40/chromedriver155.0.8059.39/Selenium4.43.0, Gradle9.7.1. CDP153 대체 경고 있었으나 실제 다운로드/브라우저 검증 모두 통과; 새 의존성 설치 없음.
- 실제 소유자: 합성 ROLE_OWNER 폼 로그인→관리자 목록 HTTP200→행사 상세 Download 메뉴→Excel 파일1개. HTTP 비교200, attachment 파일명 일치, XLSX MIME(UTF-8 charset 부가). 전체 기존13열·문서3행·모든 기존 값 확인. OFFLINE_PAYMENT 빈 확정일, COMPLETE/CANCELLED는 UTC2025-12-31 15:30→Asia/Seoul2026-01-01 00:30. 생성일은2025-12-31 09:00으로 구분.
- 합성 DB: 예약3건·문서5건(유효 최신3, 이전1, 무효1) 실제 count 및 insert/update 건수 assertion. 출력3행으로 기존 최신/유효 조회 범위 확인. 취소는 CANCELLED 합성 상태이며 실제 취소 정책 실행은 기존 통합 회귀에서 검증할 예정이다.
- 실제 비소유자: 다른 조직 ROLE_OWNER 폼 로그인 성공·관리자 목록 HTTP200, 대상 행사는 목록에 없음. 대상 다운로드 **HTTP500**, 본문 raw JSON 문자열 `"Access to resource has been denied"`(메시지 전체 비교). Content-Disposition 없음, spreadsheet MIME 없음. 브라우저 직접 요청도 거절 메시지이며 XLSX·부분 파일을 포함한 다운로드 디렉터리 **0파일**. 로그인 실패/전역 차단이 아니다.
- 근거: `ai/local-state/issue29-before5-xml/TEST-alfio.e2e.BillingDocumentDownloadE2ETest.xml`(Git 제외), SHA256 `c7a08f655ed613462e5768d3ce9566a3ff1f79787906e8d1110d122c90f45351`. 재현용 E2E SHA256 `ee67d71a61cdec51e1599c3db104f93743d74aa76c5295f01320d3b3fb506f1d`. assertion·표준 출력과 XML 실제 testcase를 대조했다. 앱/DB/Chrome/임시 다운로드는 이 실행이 생성한 자원만 finally/close로 정리됐다.
- 수정 전 기록 검토: 예상과 실제·도구 실패4회·제품 캐시·미실행 범위 구분, #27 근거 혼용 없음. `python3 -B ai/tests/check.py` 및 공백 검사 exit0. 이 MD만 선행 커밋하며 제품 수정은 그 이후 시작한다.

## 구현·수정 후 검증

- 선행 커밋 `3f1dff175e6ea67ec9afc95b5a721058c01040da` 완료 후 제품 수정 시작. 다운로드의 기존 ownership 호출에서만 AccessDeniedException을 catch해 HTTP403/plain UTF-8 메시지 후 return. 데이터 조회·Excel 생성·attachment 설정 전에 반환한다. 공통 @ExceptionHandler/AccessService/SQL/컬럼/결제·취소·스키마 변경 없음.
- 회귀: 전용 unit 1건 추가(403·정확한 본문·attachment 없음·조회/config 무호출), 기존 HTTP 통합의 >=400 기대를 정확한403으로 강화. 실제 로그인 E2E는 재현과 같은 fixture/메뉴/파일/권한 조건에403 기대만 전환하고 검토용 합성 XLSX를 Git 제외 경로로 저장.
- 기존 E2E 연결: build.gradle.kts의 e2eValidation 클래스 allowlist에 신규 클래스1개 추가. 기존 scripts/validation/e2e.sh와 .github/workflows/e2e-test.yml의 실행 경로/paths를 유지. 필요성을 구현 전에 알렸으며 하네스 코드·새 의존성·DB 마이그레이션 없음.
- 구현 준비 실패1: issue29-after regression 하네스 명령 exit1, :test 미실행/건수 없음(컴파일 전 단계 실패). 코드 검토에서 AccessDeniedException import 누락 발견·정정. 하네스는 출력 원문을 보존하지 않아 컴파일 오류 원문은 미확보; 실제 명령 실패·테스트 미실행을 기록하고 추정 원인을 단정하지 않는다. 새 실행 ID issue29-final/새 XML 범위에서 재검증.
- 현재 선언: ai/local-state/issue29-config/plan.json, issue29-final. regression/e2e/structure/qa/review 필수, QA/Review 자체 수행. 검증 근거 경로·시각·내용/환경 해시는 로컬 영수증과 연결.
- 최종 회귀 명령(저장소 루트, 하네스 `run issue29-final regression`):

```sh
./gradlew test --tests alfio.controller.api.admin.EventApiControllerDocumentsTest --tests alfio.controller.api.v2.user.reservation.BillingDocumentCreationIntegrationTest --tests alfio.manager.BillingDocumentManagerTest --console=plain --no-daemon --no-build-cache -Pverbose -I ai/local-state/issue29-config/reports.gradle -Dharness.reports=ai/local-state/issue29-final-xml -Dpgsql.version=15 -Duser.timezone=Asia/Seoul --rerun-tasks -Dbilling.download.artifacts=ai/local-state/issue29-downloads/regression
```

- 06:19:23.356847–06:19:44.378027 UTC, **exit0, :test EXECUTED, 실제47건/실패0/오류0/스킵0**. 단위25·청구문서통합8·Manager14건. XML3개 전체 `ai/local-state/issue29-final-xml/test/`, 각각 체크섬과 실제 testcase를 하네스 영수증에 연결. --rerun-tasks로 컴파일·테스트 실제 수행; bootJar·프론트 빌드는 이 회귀 명령에 포함되지 않는다.
- 통합은 테스트 Principal을 사용하는 실제 HTTP403 회귀이며 실제 로그인 검증으로 표시하지 않는다. 기존 서비스 실제 예약·확정·취소·credit note 처리 및 다운로드 pending/completed/cancelled/credit-note 값은 해당8건에서 검증. 실제 로그인은 별도 E2E 결과로 구분한다.
- 기존 E2E 전체 명령(하네스 `run issue29-final e2e`):

```sh
./gradlew e2eValidation --console=plain --no-daemon --no-build-cache -Pverbose -I ai/local-state/issue29-config/reports.gradle -Dharness.reports=ai/local-state/issue29-final-xml -Dpgsql.version=15 -Duser.timezone=Asia/Seoul -Dbilling.e2e.artifacts=ai/local-state/issue29-downloads/e2e
```

- 06:20:19.089989–06:20:58.658504 UTC, **exit0, :e2eValidation EXECUTED, 실제3건/실패0/오류0/스킵0**. 기존 NormalFlow/ValidationBrowser 각1건·신규 BillingDocumentDownload 각1건, XML3개 전체 확인. 이전 XML/캐시 재사용이 아니다. 실제 E2E bootJar의 controller bytes가 이번 컴파일 클래스와 동일함을 별도 ZIP 비교 exit0으로 확인(SHA256 `4e4ec26ef7f654298a000a6a40b6a691fe9751f3aefd7bfc9dfe661c1e45e8a5`). 프론트 신규 빌드 통과나 전체 제품 빌드 통과로 확대하지 않는다.
- 기존 `scripts/validation/e2e.sh`가 호출하는 e2eValidation 전체를 실행했으며 테스트 allowlist/CI paths 연결을 읽어 확인. 기존 `python3 scripts/validation/check-results.py ai/local-state/issue29-final-xml/e2eValidation`도 exit0, 3건/스킵0 확인. 셸 스크립트 전체를 별도 중복 실행하지 않음. 원격CI는 미실행.
- 동일 합성 조건: 실제 소유자 로그인→관리자 목록200→Download 메뉴→XLSX1파일/13열/3행 정상. 비소유자 실제 로그인·관리자 목록200 뒤 대상 **403**, plain UTF-8 거절 메시지 전체 일치(문서 데이터 없음), attachment 없음·spreadsheet MIME 없음·브라우저 직접 요청 시 XLSX/부분파일 포함 **0파일**. 수정 전500과 차이는 상태 및 본문 직렬화(JSON 문자열→plain 메시지)이며 문서/파일 미노출은 동일하게 유지.
- 직접 ZIP/XML QA: E2E owner.xlsx 및 통합4파일 **총5파일/11데이터행**. pending1/빈 확정일, completed2/COMPLETE/서울2026-10-13 01:30, cancelled2/CANCELLED/동일 확정일, credit-note3/CREDIT_NOTE_ISSUED/2026-10-13 12:00. E2E3행은 pending 빈 값 및 COMPLETE/CANCELLED 서울2026-01-01 00:30. 전체13열·기존 컬럼 값·끝2열 확인. Excel 앱 렌더링은 미실행.
- DB 결과: E2E 예약3건·문서5건 및 출력3건 정합성; 이전/무효 문서 제외. 통합에서는 실제 예약 확정/취소/credit note 서비스와 저장 상태·확정시각 update 건수 확인. E2E 취소 데이터는 SQL 합성 상태이며 정책 검증으로 표시하지 않음.
- 자원: 기존 ValidationEnvironment.close/driver.quit/다운로드 디렉터리 finally가 이번 생성 앱·DB·Chrome·임시 다운로드만 정리. 검토용 합성5파일/로컬 하네스 영수증은 Git 제외 경로에 보존. 원본 로그/DB덤프/실제 개인정보/운영 비밀값 스테이징 제외.

| 완료 조건 | 수정 전 실제 결과 | 수정 후 실제 결과 | 근거 |
|---|---|---|---|
| 비소유자403 | 실제 로그인 뒤500 | 실제 로그인 뒤403 | 신규 E2E·HTTP통합·단위 |
| 문서/파일/attachment 미노출 | 거절메시지만·0파일·헤더없음 | 거절메시지만·0파일·헤더없음 | E2E 본문 전체/헤더/브라우저 디렉터리 |
| 소유자 메뉴 다운로드 | 200·XLSX정상 | 200·XLSX정상 | 실제 폼 로그인·메뉴 클릭 |
| 기존 컬럼·끝2열 | 13열/3행 정확 | 13열/3행 정확 | 실제 XLSX ZIP/XML·단위 전자청구17열 |
| 빈 값·취소·날짜경계 | 빈 값·CANCELLED·서울 연도경계 | 동일+통합 실제 취소·단위6경계 | E2E·기존 통합/단위 |
| 조회/행사 권한 유지 | 최신유효3문서·비소유자 행사목록 제외 | 동일 | DB5문서→3행·AccessService 무변경 |
| 기존 E2E/CI 연결 | 임시 init로 신규1건만 재현 | 정식 allowlist·전체3건 실제실행 | build.gradle·기존CI·새XML |
| 실행 건수/미실행 기록 | 도구실패4회와 최종1건 구분 | 회귀47+E2E3·실패/스킵0 | 하네스 영수증·QA·Review·gate |

## 리뷰·시간·커밋

- 자체 Review: 최종 제품 diff·새 E2E 전체·완료조건8개·새 XML6개/50건·실제5파일을 대조. 차단 발견0·추가 제품 결함0. 공통 예외·권한·조회·컬럼·스키마 범위 확대 없음. QA/Review는 현재 Codex 자체 수행이며 독립 리뷰가 아니다.
- 필수 structure/regression/e2e/qa/review 모두 passed, `gate issue29-final`·`resume issue29-final` 각각 exit0. 문서 구조 검사와 공백 검사 exit0. 필수 이슈 완료 조건 누락0.
- 하네스 기여: 실제 새XML 전체·건수·Gradle 대상작업 실행·코드/환경/조건 해시를 연결하고 테스트 미실행을 완료로 거부. 첫 구현 검증 실패 후 새 대상/보고서에서 재검증, QA/Review begin→실제 확인→record 연결. 하네스 자체 수정 없음; 자체 리뷰의 독립성/원격CI/Excel 시각 검증을 대신한다고 주장하지 않음.
- 재시도: 재현 도구4회(잘못된 메뉴·숨김 중복 메뉴·MIME charset·JSON 문자열), 구현 준비1회(import 누락 정정 후 재검증), 환경권한2회(fetch·Docker 조회). 최종 관련 테스트 실패0. 리뷰 후 제품 재작업0, 최종 필수 누락0, 기록/상태 유실0. 초기 failure 원문 미확보 한계를 위에 명시했다.
- 미검증: 전체 제품 테스트·원격CI·Linux/Windows 재실행·Excel 앱 렌더링·실제 결제/외부메일. 영향 범위 또는 외부 실행 제외 요청에 따라 미실행. 하네스 코드 무변경으로 하네스 자체 unittest 전체는 미실행. Chrome/CDP 버전 대체 경고가 있었지만 실제 기능 실행 통과와 구분.
- 실제 필수 검증·자체 Review·gate/resume 종료: **2026-10-13 15:22:53 KST(06:22:53 UTC)**. 시작15:09:14부터 전체 경과 **819초=13분39초**. 이는 조사·환경·재현·필수 선행 기록/커밋·구현·검증·리뷰를 포함한 벽시계 구간이다. 선행 커밋 실제 시각15:18:08 KST, 기록정리/커밋만의 별도 소요시간은 미측정이므로 이를 제외한 순수 제품 작업 시간을 산출하지 않는다.
- 관측 자동 검증 시간: 최종 회귀21.021180초, 전체E2E39.568515초(빌드·앱·DB·브라우저·도구 대기 포함), 전체819초에 포함. 수정 전 최종 testcase14.325초/Gradle25초, 최초 실패/모델 순수대기는 분리 미측정. AI 순수 대기 미측정, CI 미실행으로 CI대기 없음, 개인사정 중단 관측 없음. 사람 직접 검토·수정 시간 각각 **미측정**, 에이전트 시간을 사람 시간으로 환산하지 않음.
- 15:22:53 이후 최종 결과 기록 정리·스테이징·후속 커밋·보고는 후처리이며 위819초에 합산하지 않는다. 제품 순수 시간과 후처리 비용을 정밀 분리한 값으로 사용하지 않는다.
- 커밋1: `3f1dff175e6ea67ec9afc95b5a721058c01040da`, `docs: 청구 문서 권한 오류 재현 기록`, MD만 별도 저장. 구현 전 완료. 커밋2는403수정·회귀/E2E·정식실행연결·사용안내·이MD 갱신의 단일 논리적 변경이며 식별자는 최종 보고/로컬git log에서 확인한다. 두 커밋 합치기/기존 이력 재작성 없음.
- 검증 대상은 선행 HEAD `3f1dff175`+후속 커밋 예정 제품 diff(product 해시 `b9e0dad9af5e47130b0fca517bc1be9fc9ac341bdfa16efa27148b6526152051`). 커밋으로 HEAD가 바뀐 뒤 이 결과를 새 HEAD에서 재실행한 것으로 표시하지 않는다. 최종 staging 내용과 검증 코드 일치를 확인한다.
- 기존 #27 기록/PR #28 본문 보존. push·PR생성·외부댓글·병합 없음.


## 실제 파일 근거 요약

| 합성 파일(Git 제외) | bytes | SHA256 |
|---|---:|---|
| `ai/local-state/issue29-downloads/e2e/owner.xlsx` | 3202 | `39756172b7570657a00b04a629918157e368ead8f5933ff56eaaa7376809ca99` |
| `ai/local-state/issue29-downloads/regression/cancelled.xlsx` | 3037 | `ef58d81369e6f2cf4cce7201c8719acd852f3911cafc324585544260de087a3e` |
| `ai/local-state/issue29-downloads/regression/completed.xlsx` | 3036 | `0788c56d4ce2903a86b54e6ab761184770735e8eb3983af26785be818be67d7f` |
| `ai/local-state/issue29-downloads/regression/credit-note.xlsx` | 3108 | `683d33a2492425cb72e41040dd4a7851f7ff27f8fef0cbebe3dc1b968fd2a8b7` |
| `ai/local-state/issue29-downloads/regression/pending.xlsx` | 2965 | `a36833c2ac530c8ea0cffcdec531930408e191385fa139f41d0a1037fabaa644` |


## PR #30 CI 실패 수정

- 요청: CI 문제를 수정·검증 후 커밋. 시작 2026-10-13 16:03:47 KST(07:03:47 UTC), 브랜치 fix/29-billing-document-access, HEAD72908ef2d, 작업 트리 깨끗함. 기존 취소된 문서 커밋은 복원하지 않는다.
- 실패 원인 확정: 새 E2E의 Jackson2 ObjectMapper 호출이 build.gradle.kts 금지 API 규칙을 위반. [제품 CI](https://github.com/WookJaes/alf.io/actions/runs/38359046782)와 [CodeQL 빌드](https://github.com/WookJaes/alf.io/actions/runs/38359046915) 모두 :forbiddenApisTest 실패. 원격 E2E3건/마이그레이션/Linux·Windows 하네스는 통과. 이전 로컬 test/E2E 검증에 금지 API 검사가 누락됐으며 독립 리뷰도 발견하지 못했다. 이전50건 통과 사실과 전체 CI 실패를 구분한다.
- 수정 전 로컬 재현: `./gradlew forbiddenApisTest --console=plain --no-daemon --no-build-cache --rerun-tasks`, exit1, 실제215클래스 스캔/금지 호출2오류, BillingDocumentDownloadE2ETest.java:114에서 동일 ObjectMapper 오류. Gradle10초·8작업 실제 실행. 제품 테스트 실행이 아닌 정적 API 검사이며 오류2건을 테스트 실패2건으로 계산하지 않는다.
- 수정: 기존 alfio.util.Json.fromJson(denialBody,String.class) 사용. 거절 본문 전체 비교/403/데이터·파일 미노출 조건 유지. 제품 코드·기존 기대값·조회·권한·CI설정·의존성·DB스키마 변경 없음.
- 검증 계획: forbiddenApisMain/Test 실제 실행, 관련회귀47건과 기존 로그인E2E전체3건, 문서 검사 및 자체QA/Review. 하네스 issue29-ci-fix, 새 XML/실물 경로와 결과 시각·건수 연결. PR 본문 수정·외부댓글·push는 이번 요청 범위에 포함하지 않는다.

- 환경/도구 사건: forbidden 명령 자동 권한 검토가 시간초과하여 실행 전 거부됨→허용된1회 재시도 성공. 최초 issue29-ci-fix forbidden exit0(07:07:46.318873–07:07:55.848655 UTC), 이후 regression exit1, XML47건/실패8/오류0/스킵0. 단위25·Manager14는 성공, 통합8건은 Docker 환경 초기화 실패. /var/run/docker.sock 없음·DockerDesktop provider 소켓탐색null이 원인이고 Docker CLI 서버29.5.2는 정상 응답. 성공으로 표시하지 않는다.
- 환경 보완: Git 제외 ci-fix-reports.gradle에서 이번 Test 프로세스에만 실제 Docker 소켓 `unix:///Users/jeong/.docker/run/docker.sock` 지정. 기존 검증 설정은 보존, 제품/CI설정 변경 없음. 새 선언/실행 ID issue29-ci-fix-final·새 XML/실물 범위에서 모든 필수 항목 재검증한다. 소켓 재탐색 문제는 환경 실패이며 금지API 수정과 구분한다.

- 재검증 issue29-ci-fix-final: forbidden exit0, regression 자체 exit0/47건·실패0·오류0·스킵0이나 하네스 :test AMBIGUOUS(동일 작업 로그 중복)로 failed. Docker 초기화 문제는 해결됐으나 게이트 통과로 표시하지 않는다. 하네스 규칙은 수정하지 않고 상세 표준출력 -Pverbose만 제거한 새 실행 issue29-ci-fix-verified/새 XML 범위에서 재검증한다. 테스트 assertion·실행대상·보고서 수집 범위는 유지한다.

### CI 수정 최종 검증·리뷰 결과

- 최종 실행 ID `issue29-ci-fix-verified`, 선언 `ai/local-state/issue29-config/ci-fix-verified-plan.json`. 대상은 HEAD72908ef2d+E2E의Jackson3유틸교체 diff이며 이전 영수증 재사용이 아니다.

| 필수 항목 | 실제 실행 구간(2026-10-13) | 종료 코드·결과 |
|---|---|---|
| forbidden | 16:12:49.641784 KST–16:12:59.613532 KST | exit0 · 실제 명령 통과 |
| regression | 16:12:59.889279 KST–16:13:19.646934 KST | exit0 · 실제47건/실패0/오류0/스킵0 · :test EXECUTED |
| e2e | 16:13:20.095354 KST–16:14:00.049522 KST | exit0 · 실제3건/실패0/오류0/스킵0 · :e2eValidation EXECUTED |
| structure | 16:15:25.773227 KST–16:15:25.801224 KST | exit0 · 실제 명령 통과 |

최종 실제 명령(저장소 루트, 하네스 run):

```sh
./gradlew forbiddenApisMain forbiddenApisTest --console=plain --no-daemon --no-build-cache --rerun-tasks
```

```sh
./gradlew test --tests alfio.controller.api.admin.EventApiControllerDocumentsTest --tests alfio.controller.api.v2.user.reservation.BillingDocumentCreationIntegrationTest --tests alfio.manager.BillingDocumentManagerTest --console=plain --no-daemon --no-build-cache -I ai/local-state/issue29-config/ci-fix-reports.gradle -Dharness.reports=ai/local-state/issue29-ci-fix-verified-xml -Dpgsql.version=15 -Duser.timezone=Asia/Seoul --rerun-tasks -Dbilling.download.artifacts=ai/local-state/issue29-ci-fix-verified-downloads/regression
```

```sh
./gradlew e2eValidation --console=plain --no-daemon --no-build-cache -I ai/local-state/issue29-config/ci-fix-reports.gradle -Dharness.reports=ai/local-state/issue29-ci-fix-verified-xml -Dpgsql.version=15 -Duser.timezone=Asia/Seoul -Dbilling.e2e.artifacts=ai/local-state/issue29-ci-fix-verified-downloads/e2e
```

- 금지 API 검사는 Main/Test 모두 --rerun-tasks로 실제 수행했고 exit0. 정적 검사이며 테스트 건수에 합산하지 않는다. 기존 하네스의 캐시/미실행 거부 조건을 유지했다.
- 회귀47건(단위25/통합8/Manager14)과 기존E2E전체3건, 새XML6개 전체·체크섬을 영수증과 대조했다. 실제로그인 소유자메뉴 HTTP200/XLSX13열3행, 비소유자admin API200→대상403/거절본문전체/attachment·문서·파일미노출 통과. E2E 기대값·검증 조건을 바꾸지 않았다.
- 새 합성 XLSX5파일/11데이터행 ZIP/XML 확인. pending빈확정일·CANCELLED/COMPLETE서울연도경계·통합실제취소/creditnote 값 유지. 이번 생성 앱/컨테이너/Chrome/임시다운로드만 close/quit/finally정리했고 근거실물/영수증은Git제외경로에 보존.
- 자체QA/Review begin→확인→record, 차단발견0. 마지막 forbidden/regression/e2e/structure/qa/review 모두passed, gate/resume 각exit0. 새 하네스코드/의존성/제품API/DB마이그레이션 변경없음. 기존 금지API 누락을 이번 선언에 보완한 것이 하네스 기여이며 초기Docker실패·중복작업판정도 완료로 통과시키지 않았다.
- 필수검증·자체리뷰·gate/resume 실제종료 **2026-10-13 16:15:26 KST(07:15:26 UTC)**. 이번 요청 시작16:03:47부터 **699초=11분39초**(로컬재현·자동권한검토대기/시간초과·Docker환경조사·하네스재시도 포함 벽시계). 순수AI대기·사람직접검토/수정·정밀대기시간은미측정. 개인사정중단관측없음, 원격CI재실행없음. 최초819초에 합산하지 않는다. 이 시각 이후 기록정리·스테이징·커밋은 후처리로 분리한다.
- 미실행: 수정 후 원격CI/CodeQL분석·전체제품suite/전체distribution빌드·다른OS/Excel앱. CI의확인된실패원인은로컬금지API검사로재현·해결했으나 원격CI전체통과로 주장하지 않는다.
- 요청 범위2파일(E2E수정/이MD)을 하나의 수정 커밋으로 저장한다. 기록확인·문서구조·공백·스테이징diff 검사 후 커밋하며 HEAD변경후위결과를새HEAD재실행으로표시하지않는다. push/PR본문수정/외부댓글/병합미실행.
