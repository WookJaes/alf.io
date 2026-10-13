# 이슈 #29 청구 문서 다운로드 권한 응답

- 작업 ID / 날짜 / 상태: issue29 · 2026-10-13 · in_progress
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

미실행.

## 리뷰·시간·커밋

필수 검증·자체 리뷰 종료 시각은 실제 완료 후 기록한다. AI/CI 대기·개인 중단·사람 검토/수정 시간은 확인한 값만 기록하며 미확인 값은 미측정으로 남긴다. 기록 정리·커밋은 제품 작업 측정과 분리한다. push·PR·외부 댓글·병합 미요청.
