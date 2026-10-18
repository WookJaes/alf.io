# 선택지 없는 필수 드롭다운 저장 차단 작업 기록

- 작업 ID / 날짜 / 상태: issue45 / 2026-10-18 / completed
- 요청·완료 기준·수정 범위: [이슈 #45](https://github.com/WookJaes/alf.io/issues/45). 필수 select의 선택지 전체 삭제 시 관리자 생성·수정 및 서버 저장 차단, 정상 설정·참가자 예약 진행 유지.
- 실행 모드·실제 담당: 현재 Codex 순차 구현·자체 검증·자체 리뷰.
- 브랜치: `fix/45-required-dropdown-validation`. 시작 브랜치 main, 작업 트리 깨끗함. `git fetch origin main` 성공 후 `origin/main`에서 분기.
- 재현 환경: macOS arm64, Corretto 25.0.4.1, PostgreSQL 16.13 격리 Testcontainers, 로컬 Chrome. 저장소 루트에서 실행. 합성 정보만 사용, 메일 disabled, disable-jobs, 무료 티켓.
- 계획: 새 환경 준비 → 수정 전 관리자·참가자 화면 및 정상 대조군 재현 → 회귀 테스트 실패 → 최소 수정 → 자동·화면 재검증 → 자체 리뷰·gate → 로컬 커밋.
- 이전 재현 결과는 검증 근거로 재사용하지 않음.
- 임시 환경·근거: `ai/local-state/issue45/` (Git 제외).

## 변경과 검증

| 완료 기준 | 변경·확인 내용 | 명령·절차 | 종료 코드·실제 건수 | 증거·결과 |
|---|---|---|---|---|
| 최신 main 기준 | fetch 후 브랜치 생성 | `git fetch origin main`, `git switch -c fix/45-required-dropdown-validation origin/main` | 각 0 | 최신 원격 main에서 분기 확인 |

## 실패·리뷰·재개

- 환경 준비 명령 오류: 존재하지 않는 `prepareBootJar` 작업 호출로 종료 1. 제품 버그와 무관하며 `bootJar`로 정정.
- Docker·Git 쓰기는 샌드박스 밖 실행이 필요하여 승인된 도구 권한으로 진행.
- 커밋·원격 작업: 로컬 커밋만 승인됨. 푸시·PR·이슈 댓글은 수행하지 않음.

## 수정 전 새 재현 결과

- 기준 커밋에서 관리자·공개 프론트와 Java를 `bootJar --rerun-tasks --no-build-cache -x frontendPnpmInstall -x frontendAdminPnpmInstall -x pnpmSetup -x nodeSetup -PfrontendDev=false`로 실제 빌드(종료 0). 최초 일반 bootJar 호출은 캐시 사용이어서 실제 재빌드로 보완했다.
- 새 `ValidationEnvironment`가 PostgreSQL 16.13 컨테이너와 앱을 만들었다. 합성 조직 및 `issue45-owner` 계정의 권한은 ROLE_OWNER만 부여했다. SQL은 계정·조직 준비에만 사용했고, 행사 생성·게시와 추가 항목의 모든 변경은 실제 관리자 UI에서 했다.
- 화면 도구: Selenium(행사 생성·게시) 및 Playwright(실제 관리자·참가자 화면), Chrome 155.0.8059.80 headless, 1440×1200.
- 관리자: Create new → Custom → 제목·내부 이름 입력 → Single-choice drop-down → Required field 켬(checked 검증) → 기본 선택지 전체 삭제 → Save. POST 200, success=true. 별도 GET으로 required=true, restrictedValues=[] 저장 확인.
- 로그아웃 후 예약: 무료 티켓 1개, 합성 예약자·참석자 기본 필수 정보 입력. 드롭다운에는 빈 항목만 표시. Continue 후 `This field is required`, URL 변화 없음.
- 정상 대조군: 잘못된 항목을 UI로 삭제하고 Blue 선택지가 있는 필수 드롭다운을 생성·저장. 참가자 화면의 Blue 선택 후 Continue로 예약 개요 단계 도달. 결제·최종 예약 확정은 하지 않음.
- 근거: `ai/local-state/issue45/baseline.md`, `before-empty_required-save.png`, `before-participant-blocked.png`, `before-normal_required-save.png`, `before-participant-normal.png`. 하네스 `issue45-before`의 baseline-ui begin/record 완료.
- 백엔드 회귀: 제품 수정 전 10건 실제 실행, 8 실패 / 0 오류 / 0 스킵. 생성 null·빈 배열·공백, 수정 null·빈 배열·전부 비활성·요청 type 우회가 기대한 검증 누락을 검출. 정상 설정 및 다른 필드 대조군은 통과. `ai/local-state/issue45/before-junit/`에 해당 실행의 XML이 있음. 하네스는 실패 실행을 통과로 판정하지 않는다.
- UI 회귀: 제품 수정 전 7건 중 2 실패 / 5 통과 / 스킵 없음. 생성에서 전체 삭제, 기존 빈 선택 드롭다운의 필수 전환 시 오류 안내 없이 저장되는 현상을 검출. Vite 합성 API 구성 요소 테스트이며 위 실제 앱 화면 근거와 구분한다.
- 준비 오류: 초기 스위치 호스트 클릭이 실제 손잡이를 누르지 않아 required=false로 저장됨 → 해당 시도는 버그 재현 근거에서 제외. 보이는 손잡이 클릭 및 checked 검증으로 정정. 반응형 로그아웃 메뉴·중복 텍스트 선택자 오류도 정정 후 다시 실행했다. 백엔드 최초 mock Event 타입 준비 오류와 UI 대조군 라벨 누락 역시 정정 후 위 최종 건수로 재실행했다.

## 수정 내용

- 관리자 `additional-field-edit.ts`: 필수 select에 실제 선택할 값이 없으면 생성·수정 저장 직전에 차단하고 선택지 추가 안내를 표시한다. 선택지를 복구하거나 필수를 해제하면 안내가 해제된다.
- `Validator`: 필수 select에 비어 있지 않고 비활성화되지 않은 값이 하나 이상 있는지 공통 검사한다. 생성의 기존 ValidationResult 응답 형식은 유지한다.
- `PurchaseContextFieldManager`: 직접 삽입과 수정에서도 DB 쓰기 전에 검사한다. 수정은 저장된 type 기준이며, 요청 type을 바꿔 select 선택지가 직렬화되지 않게 하는 우회도 차단한다.
- `AdditionalFieldApiController`: 잘못된 수정은 HTTP 400으로 응답한다. 정상 수정의 응답 형식과 동작은 유지한다.
- Java 단위 회귀 10건, 컨트롤러·DB 통합 회귀 3건, Lit 브라우저 회귀 7건을 추가했다. 브라우저 테스트 실행 스크립트와 기존 브라우저 CI에 새 회귀를 연결했다.
- 새 의존성·공개 정상 API 호환성 변경·DB 마이그레이션 없음. 다른 필드 유형의 검증 규칙은 변경하지 않음.

## 수정 후 검증

| 구분 | 명령·절차 | 실제 결과 | 근거 |
|---|---|---|---|
| Java 자동 테스트 | `./gradlew --no-daemon test --tests alfio.manager.PurchaseContextFieldManagerTest --tests alfio.controller.api.admin.AdditionalFieldApiControllerIntegrationTest --tests alfio.util.ValidatorTest --tests alfio.controller.api.admin.EventApiControllerIntegrationTest --tests alfio.manager.EventManagerIntegrationTest -Dpgsql.version=16.13 --console=plain --no-build-cache -I ai/local-state/issue45/reports.gradle -Dharness.reports=ai/local-state/issue45/verified-junit` | 종료 0. 130건 실행, 실패·오류·스킵 0. 새 단위 10 + 새 통합 3 + 기존 Validator 66 + 기존 관리자 API 13 + 기존 EventManager 38 | `ai/local-state/issue45/verified-junit/` |
| 관리자 UI 자동 회귀 | 저장소 루트에서 `CHROME_PATH='<로컬 Chrome 실행 파일>' node --test frontend/admin/test/required-dropdown.test.mjs` (또는 frontend/admin에서 `pnpm run test:required-dropdown`) | 종료 0. 7건 통과, 실패·스킵 0. 생성 전체 삭제·기존 빈 항목의 필수 전환·복구 및 정상 select/text/country/checkbox/radio 수정 | 실제 Lit 컴포넌트, Vite 합성 API. 실제 앱 QA와 구분 |
| 컴파일·프론트·패키징 | `./gradlew --no-daemon bootJar --rerun-tasks --no-build-cache -x frontendPnpmInstall -x frontendAdminPnpmInstall -x pnpmSetup -x nodeSetup -PfrontendDev=false --console=plain` | 종료 0. Java 컴파일, 관리자 tsc/Vite, 공개 Angular 및 bootJar 실제 실행. 자동 테스트 실행으로 계산하지 않음 | `issue45-verified` build 영수증 |
| 라이선스·금지 API | `python3 -B ai/local-state/issue45/static-check.py`: Gradle `licenseMain licenseTest forbiddenApisMain forbiddenApisTest --rerun-tasks --no-build-cache --console=plain --info`, 변경 Java 파일별 Header OK와 금지 API 두 작업 실제 실행 확인, `git diff --check` | 종료 0. 변경 Java 5개 헤더 각각 확인, main/test 금지 API 실제 검사 통과 | 아래 도구 판정 보완 참조 |
| 실제 관리자 생성 | ROLE_OWNER → Custom → 필수 select → 선택지 전체 삭제 → Save | 선택지 추가 안내 표시. POST 미발생, 별도 GET으로 미저장 확인. 같은 폼에서 Blue 추가 후 정상 저장 | `after-admin-create-blocked.png`, `final-qa.md` |
| 실제 관리자 수정 | UI로 생성한 빈 선택 드롭다운을 Edit에서 필수로 전환 → Save | 안내·저장 차단, POST 미발생, 기존 required=false 보존 | `after-admin-update-blocked.png` |
| 실제 서버 직접 요청 | 로그인 세션에서 CSRF 포함 POST. 생성: 빈 배열/null/공백값, 수정: 빈 배열/null/type 우회/전부 비활성/빈 선택 드롭다운 필수 전환 | 생성 HTTP 200의 validation failure 및 삽입 없음. 수정 HTTP 400 및 기존 Blue·required 플래그 보존. 각 요청 후 별도 조회로 확인 | `final-qa.md`; 독립 DB 통합 테스트도 저장 상태 확인 |
| 정상 설정·관련 동작 | 실제 UI에서 필수 select·text 생성 및 라벨 수정, 로그아웃 → 참가자 티켓 1개·기본 정보·text 입력·Blue 선택 → Continue | 정상 저장·수정, 참가자 Overview 도달. 외부 결제·최종 확정·메일 발송 없음 | `after-admin-normal-fields.png`, `after-participant-normal.png` |

### 도구 판정 보완

- `issue45-final`의 Java 130건·UI 7건·빌드는 성공했지만 static 항목은 Gradle 종료 0이어도 라이선스 플러그인의 `UP-TO-DATE` 표시 때문에 하네스가 거부했다. 이를 검사 통과로 덮어쓰지 않았다.
- `--info`로 새 실행을 확인한 결과 플러그인은 각 파일에 대해 실제 `Header OK in: ...`를 출력하고, 강제 재실행 사실도 출력하면서 작업 결과는 UP-TO-DATE로 표시했다. 금지 API 작업은 실제 실행되었다.
- 임시 검사 어댑터는 종료 코드뿐 아니라 변경 Java 5개 각각의 실제 Header OK 출력과 금지 API main/test의 실제 작업 줄을 요구한다. 원본 stdout은 파일로 저장하지 않는다.
- 선언이 달라져 `issue45-verified`를 새로 init하고 Java 회귀·UI 회귀·실제 빌드·정적 검사를 새로 수행했다. 이전 실행을 새 통과로 재사용하지 않았다.
- 통합 테스트 종료 때 기존 Jetty 종료 경고가 출력됐지만 해당 JUnit은 전부 실제 수행·통과했고 종료 코드는 0이었다. 기존 deprecation·문서 주석·번들 크기 경고도 제품 회귀 실패와 구분한다.

### 검증 대상·증거 연결

- 코드 대상: 작업 시작 시 최신 원격 main에서 분기한 `fix/45-required-dropdown-validation` 브랜치의 최종 제품·테스트·CI 변경. 모든 검증 후 제품 코드 변경 없음. `issue45-verified` 스냅샷으로 판정.
- 실제 QA 대상: 최종 빌드 후 새 격리 환경에서 시작한 애플리케이션 JAR. 구체적인 코드·산출물 식별 근거는 Git 제외 로컬 검증 기록에 보관한다.
- 임시 스크립트·환경: `ai/local-state/issue45/environment.gradle`, `src/alfio/e2e/Issue45Environment.java`, `baseline.mjs`, `final.mjs`, `environment.md`. 실제 증거는 동일 디렉터리에 있고 Git에는 넣지 않는다.
- 최종 선언: `ai/local-state/issue45/verified-plan.json`. 로컬 실행 ID: `issue45-verified`. Review·QA는 동일 Codex의 자체 수행이며 독립 에이전트 수행을 요구하지 않았다.

## 미검증 범위와 한계

- 전체 제품 테스트·전체 E2E·다른 브라우저/운영체제는 실행하지 않았다. 현재 관리자 메뉴에서 사용하는 화면은 실제로 검증했다. 영향 범위의 Java 130건, 실제 Chrome 관리자·참가자 흐름과 구성 요소 회귀 7건으로 한정했다.
- 실제 화면의 관련 필드 대조군은 select와 text 생성·수정·예약 입력이다. country/checkbox/radio는 자동 UI·단위 회귀로 확인했고 실제 참가자 화면의 각 유형 조합은 확인하지 않았다.
- 외부 결제·메일·실제 개인정보는 사용하지 않았다. 예약은 요구된 다음 단계(Overview)까지만 진행했다.
- 기존 운영 데이터의 잘못된 설정을 일괄 수정하는 마이그레이션은 요청 범위에 포함하지 않았다.
- 하네스 자체 구조는 수정하지 않아 하네스 전체 회귀를 추가 실행하지 않았다.

## 최종 판정

- 자체 리뷰: 최종 스테이징 diff와 완료 조건, 실패·통과 근거, 변경 범위 및 미검증 범위를 대조했다. 완료를 차단하는 미해결 문제 없음. 근거: `ai/local-state/issue45/review.md`.
- `issue45-verified`의 regression / ui-regression / build / static / qa / review 전부 유효. 커밋 전 `gate`와 `resume` 모두 종료 0, 완료 판정 통과.
- 위 판정 후 제품 코드·테스트·설정 변경 없음. 이 기록의 결과 요약만 갱신했다. 커밋 후 HEAD 변화는 코드 재검증으로 오기하지 않는다.
- 최종 사용자 승인에 따라 수정·회귀·검증 기록을 하나의 로컬 Conventional Commit으로 묶는다. 원격 푸시·PR 생성·이슈 댓글 등록 없음.
- 검증용 앱·DB는 수행한 환경의 종료 절차로 정리하며, Git 제외 경로의 최소 근거·스크린샷은 로컬에 남긴다.
