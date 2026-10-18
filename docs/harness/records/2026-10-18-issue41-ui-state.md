# 이슈 41 명단 비교 화면 상태 보존

- 작업 ID / 날짜 / 상태: issue41 / 2026-10-18 / completed (구현·검증 완료)
- 요청·완료 기준: [이슈 41](https://github.com/WookJaes/alf.io/issues/41). 필터·페이지 실패 상태 보존, 정상 전환 초안 보존, 비교와 초안 연결 유지 및 기존 생성·편집·복사 검증.
- 실행 모드·실제 담당: 현재 Codex 순차 구현·자체 QA·자체 리뷰. 하위 에이전트 없음.
- 초기 Git: main, 작업 트리 깨끗함. fetch 후 HEAD와 origin/main 모두 `090d10bfafdf094128a9227ea25f687cb3577a76`. 여기서 `fix/41-reconciliation-ui-state` 생성.
- 범위: Lit 명단 비교 컴포넌트, 실제 Chrome 합성 응답 회귀 테스트, 실제 앱·DB E2E 확장과 본 기록. 실제 개인정보·외부 AI 호출 없음. 기존 설치 Playwright를 사용하며 새 의존성·잠금 파일 변경 없음.
- 실행 기준: 저장소 루트. macOS arm64, Node 26.0.0, Python 3.9.6, Vite 7.3.0, 설치 Chrome.
- 계획: 수정 전 실제 컴포넌트 정상/실패 재현 → 회귀 실패 확인 → 최소 수정 → 같은 테스트·관리자 빌드 → 자체 QA·리뷰·게이트 → 로컬 커밋.

## 변경과 검증

- 제품 수정 전 테스트 준비: 제한된 셸에서 Vite listen EPERM 발생. before 훅 실패로 제품 시나리오는 실행되지 않았으며 제품 버그 검출 건수로 계산하지 않는다. 로컬 서버·Chrome 실행 권한으로 재실행한다.

## 실패·리뷰·재개

- 초기 생략 범위: 실제 DB·로그인·외부 AI 연결은 합성 화면 상태 재현에서 제외했다. 이후 추가 요청으로 실제 DB·로그인 검증을 수행했으며 아래 추가 검증 결과에 구분해 기록했다. 외부 AI는 계속 제외한다.

## 새 재현 기록 (제품 수정 전)

대상 제품은 main `090d10bfafdf094128a9227ea25f687cb3577a76`의 원본 컴포넌트다. 삭제된 이전 기록을 복구하거나 재사용하지 않았다. `frontend/admin/test/reconciliation.html`에서 실제 Lit 컴포넌트를 Vite로 로드하고 Chrome 155.0.8059.80에서 Playwright가 화면의 select·체크박스·버튼·textarea를 조작했다. 컴포넌트 상태나 메서드를 직접 주입하지 않았다. 두 페이지의 합성 비교와 `example.invalid` 이메일, 합성 초안 응답을 준비했다. API는 브라우저 라우팅으로 모두 대체했다.

실행 방법(저장소 루트, 설치된 Playwright 경로를 환경에 맞게 지정):

```sh
PLAYWRIGHT_MODULE_PATH="$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright" \
SCREENSHOT_DIR=ai/local-state/issue41/before \
node --test frontend/admin/test/reconciliation.test.mjs
```

당시 테스트는 아래 첫 6건이며, 이후 정상 동작·연결 검증 8건을 추가했다. 테스트 준비 오류가 해소된 실행에서 정상 대조군 1건 통과, 버그 검출 5건 실패, 종료 코드 1이었다. 화면 캡처 포함 재실행도 동일했다.

| 시나리오 | 수정 전 관측 | 수정 후 기대·관측 |
|---|---|---|
| 정상 MATCHED 필터 응답 | 정상 일치 필터와 matched-0 목록으로 전환, 통과 | 같은 정상 동작 유지 |
| MATCHED 조회 503 | 오류 표시, 필터 MATCHED지만 attention-0 이메일 불일치 목록 유지 | ATTENTION 필터와 기존 목록 유지 |
| 다음 페이지 정상 응답 | attention-1로 이동, 선택 0개·편집 textarea 제거 | 이동 후 선택 1개·편집 문구 유지 |
| 다음 페이지 503 | attention-0 / 1페이지 그대로지만 선택·초안 제거 | 기존 페이지·선택 체크·편집 문구 유지 |
| ALL 필터 정상 응답 | 전체 필터로 전환하면서 선택·초안 제거 | 전환 후 선택·편집 문구 유지 |
| ALL 필터 503 | 오류 표시, 전체 필터·이전 목록이 남지만 선택·초안은 유실 | ATTENTION과 기존 목록·선택·초안 유지 |

실제 화면 확인은 자동 단언과 별도로 캡처를 직접 열어 수행했다. 수정 전 화면에서 필터 ‘정상 일치’와 목록 ‘이메일 불일치’의 불일치, 조회 실패 후 1페이지가 유지되면서 선택이 0개로 바뀌고 초안 영역이 사라지는 현상을 관측했다. 캡처의 로컬 보관 위치는 아래에서 별도로 구분한다.

## 수정 내용과 완료 조건 대응

- `operation()`의 요청 전 선택·초안 초기화를 제거했다. 조회 실패는 오류만 표시하고 기존 결과와 편집 상태를 보존한다.
- `load()`는 요청 필터를 인자로 받고 성공한 응답의 버전이 현재 요청과 같을 때 결과와 필터를 함께 갱신한다. 네이티브 select는 요청 중 마지막 성공 값을 표시한다.
- 같은 비교 ID에서는 정상 페이지·필터 이동에도 선택·초안을 보존한다. 현재 목록 밖의 선택도 포함된다는 안내를 추가했다.
- 다른 비교 조회·새 비교의 정상 전환 또는 삭제 성공 시에만 이전 선택·초안을 해제한다. 새 비교의 결과 조회 실패도 기존 필터·결과·초안을 유지한다. 다른 비교와 초안이 연결되지 않게 한다.
- 제품 수정은 해당 컴포넌트 한 파일이다. 공개 API·DB·의존성 변경은 없다.

## 최종 검증 절차

당시 하네스 실행 ID는 `issue41-final`, 선언은 `ai/local-state/issue41/plan.json`이다. 이 선언과 실행 영수증은 로컬 보관 자료이며 커밋에 포함하지 않는다. 아래 하네스 명령은 당시 실행 내역으로, 새 checkout에서는 로컬 선언을 먼저 준비해야 한다. 필수 항목은 Chrome JUnit 회귀 14건, 관리자 production 빌드, 자체 QA, 자체 리뷰이며 독립 수행은 요구하지 않았다. 수정 전 실험은 직접 명령으로 실행했고 최종 검증부터 하네스에 선언했다. 처음 `issue41` 초기화는 본인이 먼저 만든 증거 디렉터리가 존재해 거부되어 새 실행 ID를 사용했다.

```sh
python3 -B ai/harness/run.py init issue41-final ai/local-state/issue41/plan.json
python3 -B ai/harness/run.py run issue41-final regression
python3 -B ai/harness/run.py run issue41-final build
```

회귀 명령은 위 Node 명령에 `--test-reporter=junit --test-reporter-destination=ai/local-state/issue41/junit.xml`을 추가하고 캡처 디렉터리를 `ai/local-state/issue41/final`로 지정한다. 빌드는 `npm --prefix frontend/admin run build`로 TypeScript 검사와 Vite production 빌드를 실행한다. 테스트 대기 코드를 요청 이벤트 대기로 정리한 뒤 최종 검증을 갱신했다.

추가 8건은 지연 응답 중 이전 상태 보존·성공 반영 1건, 페이지 왕복 후 재편집·클립보드 실제 복사 1건, 다른 비교 조회/새 비교/삭제 각각의 성공·실패 6건이다.

이번 변경에 포함한 [회귀 테스트](../../../frontend/admin/test/reconciliation.test.mjs)와 [화면 진입점](../../../frontend/admin/test/reconciliation.html)으로 최종 14건을 실행하는 명령은 다음과 같다. 저장소 루트에서 실행하며 기존 관리자 의존성·Playwright·Chrome 설치가 필요하다. `PLAYWRIGHT_MODULE_PATH`는 실행 환경에 맞게 지정한다. 이 명령은 커밋되지 않은 하네스 선언이나 과거 결과 파일을 사용하지 않는다.

```sh
PLAYWRIGHT_MODULE_PATH="$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright" \
node --test frontend/admin/test/reconciliation.test.mjs
```

초기 합성 API 검증 단계에서는 전체 Gradle 백엔드 테스트, 실제 DB·로그인 통합, 실제 AI 공급자, 다른 브라우저·기기, 원격 CI를 실행하지 않았다. 아래 추가 검증에서 실제 로그인·DB 통합과 관련 백엔드 회귀를 보완했다. 초기 14건의 합성 응답 검증을 서버 권한·DB 저장·AI 동작의 검증으로 계산하지 않는다. 원격 푸시·PR·이슈 댓글은 요청에 따라 수행하지 않는다.


## 화면 확인과 자체 리뷰

- 실제 화면 자체 QA: 하네스 `begin` 후 최종 Chrome 캡처를 직접 열었다. 정상 일치 필터와 matched-0 목록의 일치, 503 후 확인 필요 필터와 attention-0 목록 유지, 1페이지 선택 체크와 편집 문구 보존, 정상 2페이지·전체 필터 전환 후 선택 1개와 편집 초안 유지, 다른 비교 전환 후 이전 초안 제거를 확인했다. 이 초기 단계에서는 자동 단언과 별도로 이미지 직접 검토를 수행했으며 실제 서버 통합은 이후 추가 검증에서 확인했다.
- 자체 리뷰: [제품 컴포넌트](../../../frontend/admin/src/event/attendee-reconciliation/attendee-reconciliation.ts) 변경과 이슈 완료 조건, 회귀 테스트를 대조했다. 조회 성공·요청 버전 일치 때만 필터와 결과를 반영하는지, 실패 경로에서 선택·초안을 초기화하지 않는지, 같은 비교의 이동과 다른 비교의 전환을 구분하는지 확인했다. 기존 요청 취소·행사 변경 시 정리 동작과 초안 생성의 보존 의미도 검토했으며 미해결 차단 사항은 발견하지 않았다. 독립 리뷰가 아닌 현재 Codex의 자체 리뷰다.

## 로컬 보관 자료 (커밋 미포함)

다음 경로는 당시 실행 자료의 로컬 보관 위치다. 저장소만으로 열람할 수 있는 근거가 아니며, 새 checkout에서 파일이 존재한다고 가정하지 않는다. 공유 가능한 실행 방법·관측 결과·검토 내용은 이 문서 본문과 이번 변경에 포함한 테스트에 남겼다. 원본 로그·JUnit·상태 JSON·캡처는 커밋에 첨부하지 않는다.

| 자료 | 로컬 위치 | 용도 |
|---|---|---|
| 수정 전 캡처 | `ai/local-state/issue41/before/2.png`, `before/4.png` | 필터 불일치와 실패 후 선택·초안 유실 관측 |
| 수정 후 캡처 | `ai/local-state/issue41/final/1.png`~`14.png` | 자동 실행 후 화면 보관; 자체 QA에서 1·2·3·4·5·9번 직접 열람 |
| 자동 회귀 보고서 | `ai/local-state/issue41/junit.xml` | 실제 14건·실패/오류/스킵 0 집계 |
| 하네스 선언·영수증 | `ai/local-state/issue41/plan.json`, `ai/local-state/issue41-final/` | 당시 대상·명령·완료 게이트 추적 |
| 자체 QA·리뷰 메모 | `ai/local-state/issue41/qa.md`, `ai/local-state/issue41/review.md` | 본문에 요약한 확인 내용의 로컬 상세 메모 |

## 초기 합성 API 검증 단계 결과

| 검증 | 실제 결과 | 저장소에서 확인할 수 있는 내용 |
|---|---|---|
| 수정 전 Chrome 회귀 | 6건 실행, 정상 대조 1건 통과·버그 검출 5건 실패, 종료 1 | 위 수정 전 대상 커밋·실행 방법·시나리오별 관측 표 |
| 수정 후 같은 회귀 | 동일 6건 모두 통과, 종료 0 | 위 6개 시나리오의 수정 후 관측과 이번 변경에 포함한 회귀 테스트 |
| 최종 확장 Chrome 회귀 | 실제 14건, 실패 0·오류 0·스킵 0, 종료 0 | [회귀 테스트](../../../frontend/admin/test/reconciliation.test.mjs)와 위 최종 14건 실행 명령·집계 |
| 관리자 컴파일·번들 | TypeScript·Vite production 빌드 성공, 종료 0 | `npm --prefix frontend/admin run build`; 테스트 건수로 계산하지 않음 |
| 실제 화면 자체 QA | 정상 필터, 실패 시 필터·선택·초안 보존, 정상 이동 시 초안 유지, 다른 비교 연결 해제 확인 | 위 「화면 확인과 자체 리뷰」의 실제 화면 관측 요약 |
| 자체 리뷰 | 상태 반영·실패 보존·버전 검사·비교 ID 경계·회귀 범위 대조, 미해결 차단 사항 없음 | 위 「화면 확인과 자체 리뷰」의 검토 범위·판단 요약 |
| 초기 완료 게이트 | regression/build/qa/review 전부 passed, 종료 0 | 당시 `gate issue41-final` 실행 결과 요약; 재실행에는 로컬 선언·영수증 필요 |
| diff 검사 | 공백 오류 없음, 종료 0 | `git diff --check` 및 커밋 전 staged 검사 |

초기 검증 대상은 기준 `090d10bfafdf094128a9227ea25f687cb3577a76`에 제품 컴포넌트·브라우저 회귀 테스트 변경을 더한 작업 트리다. 이후 아래 추가 검증을 위해 Java E2E 테스트를 확장했다. 초기 결과는 해당 시점의 실행 기록이며 최신 작업 트리의 게이트 판정과 구분한다. 커밋 후 HEAD 변경을 새 테스트 실행으로 표시하지 않는다.

코드·회귀 테스트·검증 기록의 통합 커밋 메시지: `fix: 명단 비교 필터 불일치와 편집 초안 유실 수정`. 요청 범위의 제품·회귀·이 기록만 포함하며 원격 작업은 수행하지 않는다. 실제 서버 통합의 추가 수행 결과와 최종 미검증 범위는 아래 추가 검증에 구분해 기록했다.


## 실제 관리자 로그인·백엔드 추가 검증

- 추가 요청: 실제 관리자 로그인과 백엔드까지 연결한 검증 및 결과 기록. 검증 단계에서는 커밋하지 않고 기존 스테이징과 미커밋 변경을 보존했다.
- 시작 상태: `fix/41-reconciliation-ui-state`, HEAD `090d10bfafdf094128a9227ea25f687cb3577a76`, 기존 제품·브라우저 회귀·작업 기록 변경 유지. 기존 14건은 합성 API 응답 검증으로 한정하며 아래 통합 검증과 구분한다.
- 환경: Java Corretto 25.0.4.1, Gradle Wrapper 9.7.1, Docker 29.5.2, 전용 PostgreSQL 16.13 컨테이너, 실제 앱 JAR, 설치 Chrome. 제한된 셸의 Docker 소켓 접근은 권한 오류였으며 실제 제품 테스트 실패로 계산하지 않는다. 실행 권한이 있는 환경에서 Docker 응답을 확인했다.
- 실행 범위: 기존 `AttendeeReconciliationE2ETest`에 이슈 41 전용 통합 시나리오를 추가한다. 관리자 폼 로그인·행사 생성·합성 CSV 업로드는 실제 UI를 사용한다. 정상 비교·필터·페이지·초안 생성은 실제 컨트롤러·저장소·암호화 DB 경로를 통과한다. AI 공급자만 로컬 모의 HTTP 서버로 대체한다.
- 데이터: 정상 일치 1개·확인 필요 54개를 준비해 기본 50개 페이지 크기에서 두 페이지를 확인한다. 전용 DB의 해당 합성 비교 암호문만 잠시 잘못된 값으로 바꿔 실제 백엔드 `STORAGE_UNAVAILABLE`/HTTP 503을 유발하고, `finally`에서 복원한다. 브라우저 fetch나 API 응답 자체를 가로채지 않는다. 기존 사용자 DB·계정·행사에는 접근하지 않는다.
- 통합 시나리오 확인 범위: 503 필터/다음 페이지 실패 시 이전 결과·필터·선택·편집 초안 유지, 복구 후 정상 MATCHED/ALL 필터·페이지 왕복과 초안 유지, 클립보드 복사, 기존 로그인·권한·암호화·AI 오류 E2E 유지.
- 진단 실행: `./gradlew e2eValidation --tests alfio.e2e.AttendeeReconciliationE2ETest --console=plain --no-daemon --no-build-cache -Pverbose -I ai/local-state/issue41-backend/reports.gradle -Dissue41.reports=ai/local-state/issue41-backend/xml`. 임시 init script는 보고서 출력 위치만 지정하며 로컬 보관·커밋 미포함이다. 실제 E2E 2건·실패 0·오류 0·스킵 0, 종료 0을 확인했다. 앱 JAR은 이번 작업 트리의 양쪽 프론트를 포함해 빌드했다. 변경 없는 빌드 작업의 UP-TO-DATE는 테스트 실행 건수로 세지 않았으며 `:e2eValidation`은 실제 실행됐다.


### 재실행 방법과 관측 결과

아래 명령은 로컬 선언·init script가 없어도 함께 변경한 [실제 앱 E2E](../../../src/test/java/alfio/e2e/AttendeeReconciliationE2ETest.java)와 기존 관련 백엔드 테스트를 실행한다. 저장소 루트에서 Java 25·실행 중인 Docker·Chrome이 필요하다. 보고서는 Git 제외 `build/test-results/`에 생성된다.

```sh
./gradlew e2eValidation --tests alfio.e2e.AttendeeReconciliationE2ETest --console=plain --no-daemon --no-build-cache -Pverbose
./gradlew test --tests 'alfio.manager.attendee.*Test' --tests alfio.controller.api.admin.AttendeeReconciliationApiControllerTest --rerun --console=plain --no-daemon --no-build-cache -Dpgsql.version=16.13
```

이번 관련 백엔드 실행은 두 번째 명령에 `-I ai/local-state/issue41-backend/reports.gradle -Dissue41.unitReports=ai/local-state/issue41-backend/unit-xml`을 추가해 새 보고서 경로만 지정했다. 실제 `:test` 수행과 새 XML의 42개 testcase를 하네스 `issue41-backend-final`에서 확인했다. E2E 2건은 앞서 직접 실행한 결과를 QA에서 대조했으며, 하네스 QA를 새로운 E2E 실행으로 세지 않았다.

| 경로 | 실제 관측 결과 |
|---|---|
| 관리자 로그인·실제 비교 | 로그인 폼 HTTP 200과 admin 폼 로그인 후 행사 생성·CSV 업로드. 실제 백엔드 집계 정상 일치 1개·확인 필요 54개, 첫 페이지 50개 표시 |
| 필터 조회 실제 503 | 저장 복호화 실패의 HTTP 503을 확인. MATCHED 변경 시 오류 안내와 함께 ATTENTION·F-2 이메일 불일치 목록·선택 체크·편집 문구 유지 |
| 다음 페이지 실제 503 | 다음 페이지 API의 HTTP 503을 확인. 화면은 1/2페이지·기존 선택·편집 문구 유지 |
| 저장 결과 복원 후 정상 전환 | 실제 HTTP 200. MATCHED에서 정상 일치 목록 1개 표시, ALL 전환·2/2페이지 이동·1/2 복귀 후 선택 1개와 편집 문구 유지 |
| 복사·DB·기존 E2E | 실제 클립보드의 편집 문구 일치, 암호화 저장 확인. 기존 소유자·관리자·외부 조직 로그인, 재조회·권한 차단·AI 오류·타임아웃·잠금 해제 E2E도 통과 |

### 추가 검증 최종 결과

| 검증 | 실제 결과 | 저장소에서 확인할 수 있는 내용 |
|---|---|---|
| 실제 앱·로그인·DB E2E | 실제 2건, 실패 0·오류 0·스킵 0, 종료 0 | 위 실행 명령·관측 표와 `AttendeeReconciliationE2ETest`의 기존/추가 테스트 |
| 관련 백엔드 회귀 | 실제 42건, 실패 0·오류 0·스킵 0, 종료 0 | 위 `test` 명령과 `alfio.manager.attendee.*Test`, `AttendeeReconciliationApiControllerTest` |
| 금지 API 검사 | `forbiddenApisTest` 실제 수행, 종료 0 | `./gradlew forbiddenApisTest licenseTest --console=plain --no-daemon --no-build-cache --rerun-tasks` |
| 라이선스 검사 | 실제 강제 실행 확인, 종료 0 | `./gradlew licenseTest --rerun-tasks --info --console=plain --no-daemon --no-build-cache`의 `Executed with '--rerun-tasks'` 확인. 플러그인 결과 줄의 UP-TO-DATE를 새 검사 통과 근거로 사용하지 않음 |
| 실제 앱 화면 자체 QA | 관리자 로그인 화면 상태, 필터 503 보존, 페이지 503 및 정상 이동 뒤 편집 textarea 유지 확인 | 위 실제 관측 요약. 자동 단언과 별도로 생성된 화면 캡처를 직접 열어 확인 |
| 자체 리뷰 | 격리 DB 소유·실제 503 주입·finally 복원·선택/초안 단언·로컬 AI 한정 확인, 차단 사항 없음 | 추가 Java 테스트와 위 관측 표 대조. 제품 코드는 추가 수정하지 않음 |
| 추가 완료 게이트 | backend-regression/qa/review 모두 passed, 종료 0 | `gate issue41-backend-final` 결과 요약. E2E·Java 검사 결과 확인은 수동 QA이며 새 테스트 실행으로 세지 않음 |

- Java 컴파일의 기존 폐기 예정 API·문서 주석 경고와 Selenium의 Chrome CDP 155/지원 153 호환 경고가 있었다. 실제 브라우저 실행·단언·클립보드 검증은 통과했다. 경고 해소를 위한 의존성 변경은 하지 않았다.
- 로컬 보관·커밋 미포함: `ai/local-state/issue41-backend/xml/`(E2E 2건 XML), `unit-xml/`(관련 42건 XML), `plan.json`·`reports.gradle`(선언·보고서 위치 설정), `ai/local-state/issue41-backend-final/`(하네스 영수증), `build/validation/issue41-screen/`(filter-503/page-503/filter-success/page-success 캡처). 이 경로가 새 checkout에 존재한다고 가정하지 않는다.
- 실제 화면 확인: `filter-503.png`의 Logged in as admin·저장 오류·ATTENTION·선택 1개·F-2 체크, `page-503.png`의 1/2페이지·편집 textarea, `page-success.png`의 2/2페이지·동일 편집 textarea를 직접 확인했다. 로그인·목록 정상/실패·편집 보존을 합성 API 캡처와 구분했다.
- 정리: 테스트의 finally/AutoCloseable에서 브라우저·앱·전용 DB·모의 AI·임시 CSV를 정리했다. 종료 후 Testcontainers 라벨의 실행 중 컨테이너 0개를 읽기 전용으로 확인했다.
- 남은 미검증 범위: 전체 백엔드 suite·다른 기능 E2E 전체·다른 브라우저/기기·원격 CI·실제 AI 공급자. 이번에는 명단 비교 관련 로그인·백엔드·DB 통합까지 검증했으며 외부 AI·실제 개인정보는 사용하지 않았다.
- 변경 범위: 추가 검증에서는 Java E2E 테스트와 이슈 41 기록만 수정했다. 최종 사용자 승인에 따라 제품 컴포넌트·브라우저 회귀 테스트와 화면 진입점·Java E2E·이 기록의 다섯 파일을 `fix: 명단 비교 필터 불일치와 편집 초안 유실 수정`으로 함께 커밋한다. 원격 푸시는 수행하지 않는다.
