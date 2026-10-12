# 이슈 #23 빌드·CodeQL 실행 최소화

- 작업 ID / 날짜 / 상태: issue23 / 2026-10-12 / completed — 요청된 로컬 구현·검증 범위 완료, 원격 이벤트 확인은 후속
- 요청·완료 기준·수정 범위: [이슈 #23](https://github.com/WookJaes/alf.io/issues/23). 일반 빌드·CodeQL의 main 대상 PR/main 브랜치 push는 유지하고 다른 브랜치 push 중복·무관한 문서 전용 실행을 제외한다. 기존 검증·분석·정기 실행과 다른 워크플로우·Docker 배포 jobs를 보존한다. 사용자 요청 범위는 로컬 구현·검증·커밋까지다.
- 실행 모드·실제 담당: 현재 Codex 순차 구현·자체 QA/Review, 하위 에이전트 없음.
- 브랜치·기준 HEAD·시작 시 기존 변경: fix/23-ci-duplicate-runs / 38466c08d975640c228f7d507bf86f93f3a4cb1b / 깨끗함. origin/main fetch 후 해당 최신 main에서 분기. 기존 브랜치·worktree·로컬 상태 보존.
- 환경·명령 기준: 저장소 루트, macOS arm64, Java 25·Gradle Wrapper 9.7.1, Python 3·기설치 PyYAML 6.0.3. actionlint 미설치; 검증용 의존성 추가 없음.
- 계획: 빌드/분석 입력 조사 → 빌드 이벤트·필터·안내 → 정적 대조·Gradle dry-run·하네스 구조/회귀·자체 QA/Review·gate → 첫 커밋 → CodeQL 이벤트·필터·안내·검증 → 둘째 커밋.
- 선언·실행 근거: Git 제외 ai/local-state/issue23-*/ 아래 선언·검사 스크립트·최소 근거. 원본 로그·결과 JSON·비밀값·개인정보를 저장소/외부에 첨부하지 않는다.
- 변경 범위: .github/workflows/build-on-push.yml, .github/workflows/codeql-analysis.yml, docs/testing/automatic-validation.md, 이 단일 작업 MD. 제품·DB·의존성·브랜치 보호/필수 검사·기존 기록은 수정하지 않는다.

## 변경과 검증

| 완료 기준 | 조사·변경 내용 | 명령·절차 | 종료 코드·실제 건수 | 증거·결과 |
|---|---|---|---|---|
| 기존 검사 보존 | jobs·PostgreSQL 15/16/17·Java·실행 명령·Codecov/CodeQL 업로드·배포 조건 유지 | 출발 커밋 YAML/Gradle 조사 | 정적 확인 | jobs 불변을 검사에서 대조 |
| 빌드 입력 | src·프론트·Gradle·config/HEADER·lib_exclude·src/main/dist/Dockerfile | build/distribution/jacoco 작업 의존 조사 | 정적 확인 | 배포 컨텍스트·license·bootJar 입력 포함 |
| 분석 입력 | 현재 CodeQL 자동 감지 Java·JavaScript·Python·Actions | 기존 main run 38218980785의 자동 감지 요약 조회 | 기존 원격 근거, 새 분석 실행 아님 | 제품 외 ai/scripts Python·workflow와 website JavaScript/HTML도 입력 |

## 실패·리뷰·재개

- Docker 배포 보존: main 브랜치 push 제한만 추가하면 기존 태그 push가 사라져 PROD job이 도달 불가능해진다. 빌드는 tags: ['**']를 명시해 태그 경로를 유지한다. 태그 push는 GitHub 규칙상 paths 필터를 적용하지 않는다. 포크의 이미지 jobs는 기존 저장소 조건으로 스킵된다. 브랜치 push 범위 축소는 요청된 변경이며 원본의 기능 브랜치 dev 배포까지 유지한다는 주장은 하지 않는다.
- 문서 전용 제외는 브랜치 push/PR에 적용한다. 빌드 릴리스 태그 및 CodeQL 정기 실행은 경로 필터와 별개다.
- 정적 대조·dry-run은 GitHub 실제 이벤트/테스트/분석 실행 성공으로 표시하지 않는다. 원격 PR·후속 push 중복 제외·문서 전용 제외·main push·schedule·태그 이벤트는 푸시/PR/이벤트 실행 금지 범위이므로 후속 확인으로 남긴다.
- 관련 기억: 요청된 지침·현재 CI/Gradle·기존 실행 언어 요약만 조사. 이슈 #21 환경·시나리오의 재실험은 필요하지 않음.

### 일반 빌드 변경·검증

- src/frontend·Gradle/Wrapper/catalog/buildSrc/build-logic·config/HEADER·lib_exclude·Codecov 설정·자체 workflow를 필터에 연결하고 마지막에 md/rst/adoc 문서를 제외했다. src/main/dist/Dockerfile은 src 필터에 포함된다. 웹사이트·하네스 Python은 일반 Gradle 입력이 아니므로 제외했다.
- Git 제외 inspect.py가 YAML 파싱·101개 대표 경로/이벤트·혼합 변경·문서 제외·태그 예외·이전 jobs 동일·기타 workflow 바이트 동일을 확인했다. 실제 GitHub matcher/원격 이벤트 실행이라고 표시하지 않는다.
- Gradle dry-run: `./gradlew --no-daemon --console=plain --dry-run build distribution jacocoTestReport -Dpgsql.version=16`, 종료 0. 작업32개·필수11개 연결 확인. 실제 build/test/배포 수행0건이며 기존 task 캐시나 스킵을 테스트통과로 계산하지 않는다.
- 하네스 구조 검사 종료0, 하네스 회귀 실제70건·실패/오류/스킵0. 자체 QA/Review와 gate issue23-build-run 종료0. 선언: ai/local-state/issue23-tools/build-plan.json. 기준은 38466c0 + 빌드workflow/안내diff, 커밋후HEAD 통과아님.
- 첫 커밋 검증파일 SHA-256: build-on-push.yml `192de2ed765cb90a83c3a06d1ee630b3b2d28c2258abb459d595741fafeb5a4b`, 안내 `43e5439ee784bc55c368241fd9fdeb12a6f4432defaf071a211bfaa4b101f732`. 커밋blob과 내용일치를 확인한다.
- 신규형식적 회귀는 추가하지 않고 기설치 검사도구·최소 정적 대조와 기존 하네스 회귀를 사용했다. 실제 제품 변경이 없으므로 전체DB버전테스트/E2E/마이그레이션/화면은 재실행하지 않는다.

### CodeQL 변경·최종 검증 준비

- 첫 커밋: 72758afa913a82e3d821f6c7da9fbd446b025cf6 — 일반 빌드 이벤트·필터·안내·기록. 검증 파일/기록이 커밋blob과 일치했다. HEAD 변경으로 이전 gate를 새 HEAD 통과로 사용하지 않는다.
- CodeQL에 빌드 입력뿐 아니라 실제 감지 언어의 저장소 전체 확장자·패키지파일·전체 workflow/로컬 actions/CodeQL설정 경로를 포함한다. Python 하네스/검증 도구와 website JS/HTML도 분석 대상이므로 놓치지 않는다. md/rst/adoc 문서만 제외하며 분석 자체의 언어·query·분석경로는 축소하지 않는다.
- CodeQL main PR/push 필터를 연결하고 기존 매일 UTC00:00(한국09:00) schedule을 유지한다. 분석·업로드·Java25·security-events/contents 권한과 기존 명령은 그대로다. 별도 workflow jobs와 제품·DB·의존성·기존 기록 변경 없음.
- 최종 선언 ai/local-state/issue23-tools/codeql-plan.json, 실행 ID issue23-codeql-run. 검증 대상은 첫 커밋 + CodeQL/안내diff이며 최종 파일 고정 후 static/graph/structure/regression/자체 QA·Review/gate를 순차 실행한다.

### 최종 결과·커밋 경계

- 양쪽 YAML·대표 경로·main/PR 브랜치 이벤트·문서 전용/혼합 변경·태그/schedule 예외·기존 jobs/권한과 다른 3개 워크플로우 불변을 총 227항목 대조해 종료 0을 확인했다. actionlint는 미설치라 사용하지 않았으며 기설치 PyYAML과 GitHub 공식 이벤트/필터 문서로 검사했다. 이 로컬 대조는 GitHub 이벤트 실실행이 아니다.
- CodeQL 작업 그래프: `./gradlew --no-daemon --console=plain --dry-run build -x test` 종료 0. bootJar·두 프론트·compileJava·processResources·licenseMain·build 연결 및 test 작업 제외 확인. 실제 CodeQL 분석·결과 업로드·제품 빌드/테스트는 수행하지 않았다.
- 최종 구조 검사 종료 0, 하네스 회귀 실제 70건·실패/오류/스킵 0. 자체 QA·Review 완료 및 `gate issue23-codeql-run` 종료 0. 기준은 72758afa913a82e3d821f6c7da9fbd446b025cf6 + CodeQL/안내 diff이다.
- 최종 검증 파일 SHA-256: CodeQL workflow `5f0ee3c9084a4051a222e24e9bf50c55bbd993acfd52229f5540bef9b8bcd5ac`, 안내 `540c97dceb39ca643436df142a0303970c3a4b8d26dac8b8f7550e693205b7e5`. 빌드 workflow는 첫 검증 이후 바이트 동일. 둘째 커밋 blob과 내용 일치를 확인하고 실제 SHA 대응은 Git 제외 ai/local-state/issue23-tools/commits.md와 완료 보고에 남긴다. 자기 자신의 SHA를 본문에 넣기 위해 커밋을 재작성하지 않는다.
- 둘째 커밋 목적: CodeQL의 중복 실행·문서 전용 분석 제외 및 해당 안내/기록. 커밋 후 HEAD 변경으로 이전 gate/resume는 무효이며, 검증 내용과 커밋 내용 일치만 증명한다. 새 HEAD에서 테스트·gate를 재실행했다고 주장하지 않는다.
- 후속 원격 확인: 관련 변경 main 대상 PR 자동 실행 → PR 브랜치 후속 push의 별도 빌드/CodeQL 실행 없음 → 문서 전용 PR/main 브랜치 push 제외 → 관련 main push 실행 → CodeQL 정기 분석/업로드 유지. 기존 빌드 태그 경로는 실제 릴리스 절차에서 확인한다. 별도 테스트 태그/이미지 배포를 만들지 않는다.
- 로컬 구현·검증 완료와 원격 이벤트 확인을 구분한다. 이번 요청 범위의 푸시·PR 생성·머지·워크플로우 dispatch·외부 댓글·이슈 수정은 수행하지 않는다. 원격 실행 URL은 새로 생성하지 않는다.
- 테스트·분석 코드 및 다른 CI가 불변이므로 전체 제품 테스트/3개 DB 버전·E2E·마이그레이션·화면 재실행은 생략했다. 비밀값·실제 사용자 데이터를 추가하지 않았고 Git 제외 검사 상태·캐시·원본 결과를 커밋하지 않는다. 사용자 데이터·프로세스·기존 실험 worktree를 정리하거나 변경하지 않았다.


### PR #24 리뷰 보완: Lombok 입력 누락

- [리뷰 지적](https://github.com/WookJaes/alf.io/pull/24#discussion_r4246919537)에 따라 루트 lombok.config가 실제 컴파일 입력인데 필터에서 빠진 것을 확인했다. 생성자 메타데이터·생성 코드 커버리지·annotation 사용 오류 정책이 빌드에 영향을 준다. 기존 CI 통과는 이 설정 단독 변경의 실행 누락을 검증하지 못했다.
- 시작 브랜치 fix/23-ci-duplicate-runs, HEAD 9074d2758f75ad174d12fdb7ebf929e35f2ef1b2, 기존 변경 없음. 사용자 승인 범위는 수정·검증·추가 커밋까지이며 푸시·외부 답변·PR 본문 수정은 하지 않는다.
- 계획: 수정 전 설정 단독 변경 누락 재현 → 두 workflow의 push/PR 필터 4곳에 lombok.config 추가 → 기존 안내에 입력 반영 → 단독/문서/혼합 및 기존 필터/이벤트/jobs 보존 확인 → 구조·회귀·자체 QA/Review·gate → 수정 목적의 커밋 1개.
- 실행 모드: 현재 Codex 자체 수행, 독립 Review/QA·하위 에이전트 없음. 새 선언은 ai/local-state/issue23-lombok-tools/plan.json, 실행 ID issue23-lombok-run. 이전 실행/기록은 보존한다.

- 수정 전 새 대표 경로 검사 종료 1: build/push에서 lombok.config 단독 변경이 실행되지 않는 것을 재현했다. 두 workflow 총 4곳에 입력을 추가하고 안내를 갱신했다. lombok.config 자체·제품·Gradle·jobs·기타 workflow는 변경하지 않는다.

- 수정 후 YAML·경로·이벤트·jobs·다른 workflow 보존 정적 대조 243항목 종료 0. 양쪽 push/PR의 네 목록 각각 Lombok 설정 단독 포함·README 단독 제외·둘의 혼합 포함 확인. 추가 케이스는 Git 제외 검사 근거이며 선언형 설정을 복제하는 새 추적 테스트는 추가하지 않았다.
- `python3 -B ai/tests/check.py` 종료 0, `python3 -B -m unittest discover -s ai/tests -p test_*.py` 실제 70건·실패/오류/스킵 0. 자체 QA·Review 완료, `gate issue23-lombok-run` 종료 0. 현재 기준은 9074d2758f75ad174d12fdb7ebf929e35f2ef1b2 + 수정 diff다. 실제 제품 테스트·CodeQL 분석·GitHub 이벤트는 실행하지 않았다. 설정 내용/제품/작업 명령이 불변인 필터 보완이므로 기존 제품/분석 실행과 Gradle dry-run을 다시 수행하지 않았다.
- 수정 검증 SHA-256: build workflow `74788652437fce51ab28133b07156ea6f0601962774a621b37c6e4639a8c44eb`, CodeQL workflow `677eb83f1abbf9178c9f7add3a8613e9bafab83147740e092bebe74ab98f6d07`, 안내 `d0cdaa6edcebe8d4bc3ddb842c2ce7d6a5305f9550de44b8b7928b9480e5be9a`. 커밋 직후 해당 blob 내용 일치 및 HEAD 변경에 따른 기존 gate 무효를 확인하고 실제 커밋 대응을 Git 제외 ai/local-state/issue23-lombok-tools/commits.md에 기록한다.
- 로컬 리뷰 보완·검증 완료. 수정 커밋은 이후 사용자 승인하에 푸시해야 원격 PR에 반영된다. 기존 PR 본문·리뷰 댓글·이슈는 변경하지 않는다. 후속 리뷰 재확인·원격 CI와 Lombok 설정 단독 실제 이벤트는 별도이며 로컬 확인만으로 원격 완료를 주장하지 않는다.
