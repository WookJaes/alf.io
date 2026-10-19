# PR 46 필수 라디오 선택지 검증

- 작업 ID / 날짜 / 상태: pr46-radio-gate / 2026-10-19 / completed
- 요청: PR #46 참고 의견에 따라 필수 `radio`에도 `select`와 같은 선택지 방어를 적용한다. 생성·수정·행사 생성 API v1·관리자 행사 생성에서 선택 가능한 값이 하나 이상 있어야 한다.
- 완료 기준: 잘못된 필수 radio 설정 저장 차단, 정상 필수 radio와 빈 선택지의 비필수 radio 허용, 관련 Java·브라우저·컴파일·정적 검사, 실제 관리자·로그아웃 참가자 화면 확인, 자체 리뷰·gate 후 별도 로컬 커밋.
- 실행 모드: 현재 Codex가 구현·검증·QA·리뷰를 순차 수행한다. QA와 리뷰는 자체 수행이며 독립 에이전트를 사용하지 않았다.
- 초기 Git: `fix/45-required-dropdown-validation`. 원격보다 앞선 검토 대응 로컬 커밋 1개와 참고 대응 미커밋 파일 7개·작업 기록 1개를 보존하며 이어서 작업했다.
- 범위: 필수 select/radio 검증, 관리자 저장 차단·안내, 해당 회귀와 두 유형의 생성 시 비활성 값 보존. checkbox 등 다른 유형, 새 의존성, 공개 HTTP API 형식, DB 스키마는 변경하지 않는다.
- 실행 환경: 저장소 루트, macOS 27.0.1 aarch64, Corretto Java 25.0.4.1, Gradle 9.7.1, Docker 29.5.2, 격리 PostgreSQL 16.13, Chrome 155.0.8059.80. 합성 데이터만 사용한다.
- 최종 선언: `ai/local-state/pr46-radio-current/gate-plan.json`. 자동 검사 5개, 실제 화면 QA, 자체 리뷰를 필수로 선언했다. 로컬 상태·원본 로그·화면 자료는 커밋에 포함하지 않는다.
- 아래 결과는 현재 환경에서 새로 실행한 결과다. 검증 후 제품 코드 변경은 없다.

## 변경 내용

- `Validator.isValidRequiredSingleChoice`로 필수 select/radio의 빈 목록·null·공백·전체 비활성 선택지를 거부한다.
- 관리자 폼은 저장 요청 전에 같은 조건을 확인하고 선택지 추가 안내를 표시한다. 값을 복구하거나 비필수로 바꾸면 저장할 수 있다.
- 수정은 DB에 저장된 유형으로 검증하므로 요청의 유형을 바꾸는 우회를 거부한다.
- 두 행사 생성 경로는 행사 저장 전에 검증한다. 잘못된 입력 때문에 행사나 필드가 남지 않는지도 확인한다.
- 생성 요청의 비활성 값을 두 유형에 한해 추출하고 기존 DB 열에 저장한다. 활성·비활성 값이 섞인 정상 설정도 비활성 상태를 보존한다.

## 수정 전후 검증

| 확인 항목 | 명령·절차 | 새 실행 결과·증거 |
|---|---|---|
| 수정 전 실제 저장 재현 | 현재 작업 트리를 건드리지 않은 임시 HEAD 복사본에서 `AdditionalFieldApiControllerIntegrationTest.reproducesRequiredRadioSavedWithoutSelectableOptions` 실행 | 종료 0, 1건에서 4가지 설정 저장 확인. null·빈 목록·공백 필수 radio에는 선택 가능한 값이 없었다. 전체 비활성 요청은 비활성 표시가 사라지고 활성 값으로 저장됐다. `ai/local-state/pr46-radio-current/repro-confirmed-junit/` |
| 수정 후 Java 회귀 | `python3 -B ai/harness/run.py run pr46-radio-gate regression` | 종료 0, 162건 실행·실패 0·오류 0·스킵 0. 필드 Manager·추가 필드 HTTP·행사 생성 API v1·관리자 행사 생성·기존 Validator/EventManager 회귀 포함. `ai/local-state/pr46-radio-current/gate-junit/` |
| 관리자 브라우저 회귀 | 하네스 `run pr46-radio-gate ui`, 내부 `node --test frontend/admin/test/required-dropdown.test.mjs` | 종료 0, 9건. select/radio 생성 차단·복구 저장·빈 비필수 항목의 필수 전환 차단·정상 수정 대조군 |
| TypeScript | 하네스 `run pr46-radio-gate typescript`, 내부 `tsc --noEmit -p frontend/admin/tsconfig.json` | 종료 0 |
| Java·양 프론트·패키징 | 하네스 `run pr46-radio-gate build`, 내부 `./gradlew --no-daemon bootJar --rerun-tasks --no-build-cache -x frontendPnpmInstall -x frontendAdminPnpmInstall -x pnpmSetup -x nodeSetup -PfrontendDev=false --console=plain` | 종료 0. Java 재컴파일·공개 Angular/관리자 Lit 빌드·bootJar 실제 실행 |
| 라이선스·금지 API·diff | 하네스 `run pr46-radio-gate forbidden`, 내부 검사 스크립트가 `licenseMain licenseTest forbiddenApisMain forbiddenApisTest --rerun-tasks --no-build-cache --console=plain --info`와 `git diff --check` 실행 | 종료 0. 수정 Java 파일 9개 각각 `Header OK`, 금지 API main/test 실제 실행 확인. 라이선스 플러그인의 작업 상태 표시와 실제 파일 검사 근거를 구분해 확인 |
| 실제 관리자·참가자 화면 | 하네스 QA begin 후 격리 행사에서 실제 관리자 폼·HTTP·로그아웃 예약 흐름 확인 | 종료 0. 관리자 생성·수정 차단 및 복구 저장, 직접 요청 거부·기존 값 보존 확인. 로그아웃 참가자가 Blue 선택 후 Continue로 Overview 도달. 빈 비필수 radio가 함께 있어도 진행 성공. `ai/local-state/pr46-radio-current/qa.md`와 같은 폴더의 화면 자료 |
| 자체 리뷰·gate | 최종 diff와 완료 기준·실행 근거 대조, 하네스 record/gate 실행 | 종료 0. 자동 검사 5개·자체 QA·자체 리뷰 모두 통과. `ai/local-state/pr46-radio-current/review.md` |

## 완료 판단

- 새 단위·HTTP 회귀에서 필수 radio 생성·수정의 잘못된 설정 거부와 DB 값 보존을 확인했다. 두 행사 생성 경로의 잘못된 설정 거부, 정상 필수 radio 및 빈 비필수 radio의 생성도 확인했다.
- 자체 리뷰: 최종 diff, 필수 유형 조건, 저장된 유형에 따른 수정 검증, 생성 전 검증 순서, 비활성 값 보존, 기존 변경 보존과 새 실행 근거를 대조했다. 추가 수정이 필요한 사항은 발견하지 않았다.
- 미검증 범위: 저장소 전체 테스트, 다른 브라우저 조합, 최종 주문 확정·결제·메일. 요청한 radio 저장 방어와 Continue 진행 범위는 검증했다.
- 커밋 전 기록 검토: 현재 변경과 새로 확인한 수정 전후 결과를 정리했다. 개인 절대 경로·실제 개인정보·비밀값·구체적인 커밋 해시는 포함하지 않았다.
- 최종 gate는 커밋 전에 통과했다. 기존 검토 대응 커밋은 보존하고 참고 대응만 별도 로컬 커밋한다. 새 검증 앱과 격리 DB는 확인 후 종료했다.
- 푸시·PR 본문 수정·댓글·스레드 해결은 수행하지 않는다.
