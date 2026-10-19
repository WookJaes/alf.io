# PR 46 행사 생성 검증 보완

- 작업 ID / 날짜 / 상태: pr46-event-creation / 2026-10-19 / completed
- 요청·완료 기준: [검토 의견](https://github.com/WookJaes/alf.io/pull/46#discussion_r4309919276). 행사 생성 API v1과 관리자 생성 요청에 빈 필수 select가 포함되면 서버 오류 대신 원인을 식별할 수 있는 검증 오류로 거부하고 행사를 저장하지 않는다.
- 실행 모드·담당: 현재 Codex의 순차 구현·자체 검증·리뷰. 별도 에이전트 없음.
- 초기 Git: `fix/45-required-dropdown-validation`, 원격 작업 브랜치와 동기화, 작업 트리 깨끗함. PR의 기존 수정 위에 리뷰 대응을 추가한다.
- 범위: 두 행사 생성 경로의 추가 입력 항목 검증과 회귀 테스트. radio 등 다른 유형으로 검증 규칙을 확대하지 않는다. 새 의존성·DB 마이그레이션 없음.
- 실행 환경·기준: 저장소 루트, Java 25, Gradle Wrapper, PostgreSQL 16.13 격리 Testcontainers. 합성 행사·계정과 테스트 이미지 대체 구현을 사용하며 실제 개인정보·외부 결제·메일 발송 없음.
- 계획: 실제 HTTP 처리 경로의 회귀 테스트 실패 확인 → 앞단 검증 보완 → 정상 대조군·기존 회귀 및 빌드 확인 → 자체 리뷰·gate → 로컬 커밋.
- 권한 범위: 로컬 커밋까지. 원격 푸시·PR 본문 수정·리뷰 답글·스레드 해결은 하지 않는다.
- 임시 선언·증거: `ai/local-state/pr46-creation-config/` 및 실행 ID별 Git 제외 디렉터리. 구체적인 코드 식별 근거는 로컬에만 보관한다.

## 변경과 검증

검증 수행 후 실제 결과를 아래에 기록한다.

## 환경 준비 실패

- 최초 회귀 실행은 Docker 엔진이 꺼져 ApplicationContext를 초기화하지 못했다. 제품 시나리오에 도달하지 않았으므로 버그 검출로 계산하지 않는다. Docker Desktop을 시작한 뒤 별도의 새 실행·보고서 범위에서 재실행한다.

## 수정 전 재현

- Docker 기동 후 `pr46-creation-repro`에서 두 컨트롤러의 HTTP 처리·예외 처리와 격리 DB를 연결한 새 회귀 8건을 실제 실행했다. 정상 필수 select와 빈 선택 항목 대조군 4건은 통과했다.
- API v1 및 관리자 행사 생성 각각 null/빈 배열 선택지 요청 4건에서 HTTP 400 기대값이 실제 HTTP 500으로 실패했다. API v1은 `unexpected exception`, 관리자는 선택지 필요 예외 문구를 반환했다. 각 응답 후 행사 미저장을 확인했다.
- 실행: `./gradlew --no-daemon test --tests 'alfio.controller.api.v1.EventApiV1IntegrationTest.*Dropdown*' -Dpgsql.version=16.13 --console=plain --no-build-cache -I ai/local-state/issue45/reports.gradle -Dharness.reports=ai/local-state/pr46-creation-config/repro-junit`. 종료 1, 8건 중 실패 4·오류 0·스킵 0. 보고서는 Git 제외 로컬 경로에 보관한다.

## 수정 내용

- API v1의 기존 추가 입력 사전 검증에 공통 필수 select 검증을 적용하여 `invalid.additionalInfo`와 HTTP 400으로 응답한다. 기존 이름 검증의 우선순위는 유지한다.
- 관리자 행사 생성의 추가 입력 검증에도 같은 조건을 적용한다. 기존 `/events/check`는 기존 형식의 검증 실패를 반환하며, `/events/new`에 직접 요청하더라도 저장 전에 HTTP 400과 `ticketFields[0].restrictedValues` 오류로 거부한다.
- 정상 생성 응답은 관리자 HTTP 200·OK와 API v1 HTTP 200·행사 식별자 형식을 유지한다. 정상 필수 select 및 빈 선택 드롭다운의 저장값을 대조한다.
- 저장 직전 매니저 방어 검증과 권한 검사는 유지한다. radio 등 다른 필드 유형의 검증 규칙은 변경하지 않는다.

## 수정 후 검증

| 구분 | 명령·절차 | 실제 결과 |
|---|---|---|
| 자동 회귀·통합 | `./gradlew --no-daemon test --tests alfio.controller.api.v1.EventApiV1IntegrationTest --tests alfio.controller.api.admin.EventApiControllerIntegrationTest --tests alfio.controller.api.admin.AdditionalFieldApiControllerIntegrationTest --tests alfio.manager.PurchaseContextFieldManagerTest --tests alfio.util.ValidatorTest --tests alfio.manager.EventManagerIntegrationTest -Dpgsql.version=16.13 --console=plain --no-build-cache -I ai/local-state/issue45/reports.gradle -Dharness.reports=ai/local-state/pr46-creation-config/final-junit` | 종료 0. 146건 실행, 실패·오류·스킵 0. API v1 16건(이번 신규 8건 포함), 기존 관리자 API 13건, 추가 항목 API 3건, 필드 매니저 10건, Validator 66건, EventManager 38건 |
| 컴파일 | `./gradlew --no-daemon compileJava --rerun-tasks --no-build-cache --console=plain` | 종료 0. 실제 강제 재컴파일. 테스트 실행과 구분함 |
| 정적 검사 | `./gradlew --no-daemon forbiddenApisMain forbiddenApisTest --rerun-tasks --no-build-cache --console=plain`, `git diff --check` | 각각 종료 0. main/test 금지 API 작업 실제 실행. 변경 Java 3개는 기존 라이선스 헤더와 동일함도 대조 |
| 요청·DB 자체 QA | MockMvc로 실제 컨트롤러·JSON 변환·예외 처리와 격리 DB를 연결해 응답 및 저장 상태를 대조 | 두 경로의 null/빈 배열 필수 select는 HTTP 400과 구체적인 검증 오류, 행사 미저장. 정상 필수 select 및 빈 선택 항목은 기존 HTTP 200 응답과 필수 여부·선택지 저장값 유지 |

- API v1 오류는 `invalid.additionalInfo`, 관리자 생성 오류는 `ticketFields[0].restrictedValues`로 원인을 식별할 수 있다. 관리자 사전 확인 API도 기존 HTTP 200·검증 실패 결과를 유지한다.
- 정상 API v1의 행사 식별자 응답과 관리자의 `OK` 응답이 변경되지 않았음을 검사했다. 관리자 생성은 일반 ROLE_OWNER 소유권 검사도 거친다.
- 최초 수정 후에도 146건이 통과했지만, 자체 검토에서 기존 이름 검증 순서를 명확히 유지하도록 정리한 후 새 실행 ID·보고서 범위로 다시 146건을 수행했다. 최종 결과는 `pr46-creation-final` 기준이다.
- 환경·제품·테스트 설정의 구체적인 식별 근거와 실행 영수증은 Git 제외 로컬 기록에만 보관한다. 검증 후 제품·테스트 변경 없음.

## 자체 리뷰·최종 판정

- 실제 diff와 검토 의견을 대조해 생성 전 검증, 권한 검사 순서, 기존 정상 응답 형식, 저장 직전 방어 유지 및 회귀 범위를 확인했다. 차단할 미해결 문제는 발견하지 못했다.
- Review·QA는 동일 Codex 자체 수행이다. 근거: `ai/local-state/pr46-creation-config/review.md`, `qa.md`.
- 로컬 선언: `ai/local-state/pr46-creation-config/final-plan.json`, 실행 ID: `pr46-creation-final`. regression / compile / forbidden / qa / review 모두 유효하며 커밋 전 gate는 종료 0·통과했다.
- 제품·회귀·이 기록만 하나의 로컬 Conventional Commit으로 묶는다. 원격 푸시·PR 본문 변경·리뷰 답글·스레드 해결은 하지 않는다.

## 미검증 범위

- 이번 요청은 서버의 행사 생성 오류 응답 보완이다. MockMvc의 HTTP 처리 경로 및 실제 격리 DB를 검증했으며, 브라우저·실제 네트워크 소켓을 거친 전체 화면 흐름은 재실행하지 않았다. 프론트엔드 소스는 변경하지 않았다.
- 전체 제품 테스트·전체 E2E·다른 운영체제·원격 CI는 이번 추가 변경에 대해 실행하지 않았다. 관련 기존 Java 검사는 위 146건으로 한정했다.
- radio 참고 의견은 이번 요청 범위 밖이므로 구현·재현·이슈 등록을 하지 않았다. 실제 결제·메일·운영 데이터도 사용하지 않았다.
