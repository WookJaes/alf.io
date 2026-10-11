# 이슈 #21 검증 자동 실행 연결

- 작업 ID / 날짜 / 상태: issue21 / 2026-10-11 / incomplete — 로컬 구현·검증 완료, 원격 미검증
- 요청·완료 기준·수정 범위: [이슈 #21](https://github.com/WookJaes/alf.io/issues/21). 두 워크플로우의 main 대상 PR·main push 자동 실행, 관련 경로 필터와 PR 중복 취소, 수동 실행 유지. 로컬 구현·검증·목적별 2개 커밋까지 수행하며 원격 확인은 후속 작업이다.
- 실행 모드·실제 담당: 현재 Codex 순차 구현·자체 Review·자체 QA. 하위 에이전트와 독립 검토 없음.
- 브랜치·기준 HEAD·시작 시 기존 변경: feat/21-validation-auto-run / c1863294cc32c6c56e5ae4a151c336574597c864 / 깨끗함. origin/main fetch 후 동일 SHA 확인. 충돌할 사용자 변경이 없어 기존 checkout 사용; 기존 실험 worktree·로컬 상태 보존.
- 변경 범위: .github/workflows/e2e-test.yml, .github/workflows/migration-test.yml, docs/testing/automatic-validation.md, 이 작업 MD. 제품 코드·스키마·의존성·기존 시나리오·브랜치 보호·필수 검사·과거 기록 변경 없음.
- 재현 환경·명령 기준: 저장소 루트, macOS arm64, Corretto 25.0.4.1 / 17.0.20.1, Gradle 9.7.1, Docker Engine 29.5.2, Chrome 154.0.8037.99, Python 3·PyYAML 6.0.3(기설치).
- 계획: 실행 의존 조사 → E2E 설정·안내 → YAML·필터·그룹 대조 및 실제 스크립트·하네스 검사 → 자체 Review/QA·gate → 첫 커밋 → 마이그레이션 설정·안내·동일 검증 → 둘째 커밋. 각 커밋의 검증 대상과 이후 HEAD 차이는 별도로 기록한다.
- 로컬 선언·최소 근거: Git 제외 ai/local-state/issue21-*/. 원본 로그·덤프·결과 JSON·비밀값·실제 개인정보는 커밋하거나 외부 게시하지 않는다.

## 변경과 검증

| 완료 기준 | 변경·확인 내용 | 명령·절차 | 종료 코드·실제 건수 | 증거·결과 |
|---|---|---|---|---|
| 기존 실행 유지 | contents: read, Java 25(E2E), Java 17→25(마이그레이션), 30분, 기존 bash 명령 보존 | 기존 YAML·Gradle·스크립트·테스트 조사 | 정적 확인 | bootJar·모든 test source 컴파일·공통 브라우저/환경·결과 검사 의존 |

## 실패·리뷰·재개

- 초기 sandbox에서 git fetch 및 Docker 소켓 접근 거부. 승인 범위의 권한 확장으로 fetch·환경 확인 성공; 코드 문제 아님.
- 취소 영향: 정상 성공·실패는 finally/AutoCloseable, 이전 소스는 EXIT trap으로 정리. 취소는 SIGINT→SIGTERM→프로세스 트리 강제 종료될 수 있어 finally/trap 완료를 보장하지 못한다. 기존 Ryuk 및 실행별 GitHub 호스팅 runner 폐기로 잔여 자원을 격리하며 취소를 통과로 계산하지 않는다. 원격 취소·정리의 실제 관측은 후속 확인이다.
- 생략 범위: 전체 제품 테스트·다른 DB 버전·새 시나리오·실제 결제·메일. 워크플로우 트리거 변경에 불필요한 확장이다.
- 원격 후속 순서: 관련 경로를 바꾸는 main 대상 PR 실행 → 같은 PR 후속 변경에서 이전 cancelled·최신 실행 확인 → main 반영 후 push 실행 → Actions에서 브랜치를 선택해 workflow_dispatch 실행 → 문서 전용 변경 제외 확인. 각 실제 URL·이벤트·검증 SHA·건수·정리 결과를 새 기록에 남긴다. 이번에는 원격 실행하지 않으며 이슈 전체 완료를 선언하지 않는다.

### E2E 자동 실행 변경·실제 검증

- main 대상 PR·main push에 제품/리소스·전체 테스트 컴파일 입력·프론트·Gradle/Wrapper/카탈로그·E2E 스크립트·공통 결과 검사·해당 워크플로우를 연결했다. 안내에 전체 필터·문서 제외·PR만 취소 정책을 반영했다.
- 필터 대표 사례: Java·application.properties·SQL·기존 관리자 HTML·공개 Angular 화면·Lit 소스·pnpm lock/workspace·.npmrc·Gradle catalog/Wrapper·공통 check-results.py는 포함. README·docs·frontend 내부 README/rst/adoc·website·다른 workflow/전용 스크립트는 제외. main 대상 PR 및 main push 필터 각각 대조, 수동 실행에는 필터 없음.
- 그룹 대표 사례: 동일 workflow PR #21 run 1/2는 동일; PR #22는 별도. push run 1/2·dispatch run 1/2는 모두 별도. YAML 파싱·표현식 평가·기존 jobs 전체 비교를 포함한 88개 정적 대조 통과. actionlint는 설치되어 있지 않아 사용하지 않았고 검사 의존성을 추가하지 않았다. GitHub 공식 workflow syntax와 cancellation 문서를 대조했다.
- 실제 명령: `bash scripts/validation/e2e.sh`(하네스의 Git 제외 execute.py가 그대로 실행하고 성공 후 새 XML을 Git 제외 전용 범위로 복사), 종료 0. `:e2eValidation` 실제 실행, 2건·실패 0·오류 0·스킵 0. 기존 앱 빌드의 캐시는 테스트 건수에 포함하지 않음.
- 실행 기준: c1863294cc32c6c56e5ae4a151c336574597c864 + E2E 설정·안내 diff. XML 근거: ai/local-state/issue21-e2e/xml/, 선언: ai/local-state/issue21-e2e/plan.json, 실행 ID: issue21-e2e-run.
- 하네스 init에 선언 디렉터리와 동일한 실행 ID를 사용해 거부됨: 기존 파일 보존 후 별도 -run ID 사용. 첫 실행에서 동일 ID의 회귀·E2E 명령이 겹쳐 회귀 영수증이 유효하지 않게 되었고, 실제 E2E 완료 후 회귀·guard를 순차 재실행해 상태를 복구했다. 이후 모든 동일 ID 변경은 순차 수행한다.
- bash -n 두 스크립트 통과. 실행 전/후 docker ps 비어 있음; 검증 앱 JVM·ChromeDriver 잔여 없음. 사용자 프로세스와 캐시를 제거하지 않았다.
- 커밋 전 검증 파일 SHA-256: e2e-test.yml `7265b1001d7c49bee714760ae687e2d8e482a24d9a29a22d0b81268e6717df6a`, automatic-validation.md `b2a7df7eeb21d38e80db35503779d474c4b13a6ac843e0e8c10d96940115c8dc`. 커밋 직후 blob 내용과 비교해 일치를 확인한다.

- 첫 커밋 전 gate issue21-e2e-run: 종료 0. structure 종료 0, 하네스 회귀 실제 70건, 결과 거부 회귀 실제 6건, 실패·오류·스킵 모두 0. 자체 QA·Review 유효. 이 판정은 위 출발 HEAD+diff에 대한 것으로 커밋 후 새 HEAD의 gate 통과를 뜻하지 않는다.

### DB 마이그레이션 자동 실행 준비

- 첫 커밋: `95a19e7b5d4ef9295e8c03b739615102335786af` — E2E 자동 실행. 커밋 blob의 두 검증 파일이 위 SHA-256 및 작업 트리와 일치함을 확인했다. 커밋으로 HEAD가 바뀌므로 이전 gate를 새 HEAD 통과로 사용하지 않는다.
- 마이그레이션 이벤트·필터·취소 설정은 실제 현재 bootJar/프론트·전체 테스트 컴파일·공통 브라우저/예약/결과 검사 의존에 맞췄다. 자체 workflow·migration.sh만 전용으로 연결했다. 기존 고정 이전 릴리스·Java 17→25·30분·권한·실행 명령·테스트 기준을 보존했다.
- 안내에 마이그레이션의 전체 필터·취소 정책·두 워크플로우 원격 후속 확인 순서·취소 정리 한계를 반영했다.
- 둘째 검증 대상: 95a19e7b5d4ef9295e8c03b739615102335786af + migration workflow/안내 diff. E2E workflow·제품·검증 scripts/tests는 첫 실제 실행과 바이트 동일하다. E2E를 새 HEAD에서 재실행했다고 주장하지 않으며 자체 QA에서 기존 실행과 입력 동등성을 확인한다.
- 둘째 선언: ai/local-state/issue21-migration/plan.json; 실행 ID issue21-migration-run. 구조·회귀·결과 검사·양쪽 YAML/필터/그룹 대조·실제 migration.sh·자체 Review/QA를 현재 대상에서 순차 수행한다.

- 마이그레이션 최초 실제 실행은 종료 0·1건·실패/오류/스킵 0이었으나 실행 중 안내의 화면 검증 범위를 이전 앱 UI + 현재 앱 공개 API로 정정하여 하네스가 코드/조건 변경으로 invalid 판정했다. 이를 최종 gate 통과로 사용하지 않고 새 XML 범위 xml-final 및 새 실행 ID issue21-migration-final에서 최종 문서 고정 후 재실행한다. 정적 검사·회귀·자체 검토도 최종 대상에서 갱신한다.

- 최종 정적 대조: 양쪽 각 88항목 + workflow 간 PR 취소 그룹 분리 1항목 = 177항목. main 이외 PR 대상/브랜치 push는 branches 필터로 제외, 수동은 필터 없음. .md/.rst/.adoc 문서만 바뀐 경우 제외. 두 jobs 설정은 출발 커밋의 파싱 결과와 동일.
- config/HEADER는 license 검사, custom.jvmargs는 bootRun 전용이며 이 전용 bootJar/test 작업의 입력이 아니다. 루트 .env·docker-compose·배포 설정·website·하네스 상태는 이 앱의 임시 작업 디렉터리/환경 허용 목록·전용 Docker 실행과 연결되지 않아 포함하지 않았다. 공통 결과 검사·관련 회귀 파일은 양쪽 필터에 연결했다.
- 첫 실제 E2E 이후 제품·프론트·Gradle·기존 scripts/tests·E2E workflow 추적 입력 2,201개가 첫 커밋과 바이트 동일함을 확인했다. 둘째 변경은 migration workflow·안내·이 기록뿐이며 E2E 실실행은 앞선 결과로 보고한다.
- 최종 검증 파일 SHA-256: migration-test.yml `b1bdbc6e0ae6a65b5e0dcfa46acdf4e8230e45764b94d5c4e918c0e619e42c67`, automatic-validation.md `565d6c15b057922fe4c6a6b4e436aa6d5cceea9688a4d5661b4e521df9dfaf9e`. 둘째 커밋 blob과 비교한다. 해당 커밋은 `git log -1 -- .github/workflows/migration-test.yml`로 식별하고 커밋 직후 실제 SHA·전체 커밋 파일 체크섬 대응을 Git 제외 최소 근거 ai/local-state/issue21-migration/commits.md에 남긴다. 자기 자신의 SHA를 본문에 넣기 위한 커밋 재작성은 하지 않는다.


### 최종 결과·커밋 경계

- 최종 실제 명령: `bash scripts/validation/migration.sh`(기존 명령 그대로 실행 후 성공한 새 XML만 Git 제외 경로에 복사), 종료 0. `:migrationValidation` 실제 실행·1건·실패 0·오류 0·스킵 0. 고정 이전 소스 Java 17 빌드 → 이전 앱의 합성 UI 예약 → 같은 PostgreSQL 16.13 DB의 현재 앱 업그레이드·보존/Flyway/API 확인. XML: ai/local-state/issue21-migration/xml-final/; 실행 ID issue21-migration-final.
- 최종 하네스 구조 종료 0, 하네스 회귀 실제 70건 및 결과 거부 회귀 실제 6건 종료 0·실패/오류/스킵 0. 자체 Review·QA 완료. `gate issue21-migration-final` 종료 0: 최종 commit 전 HEAD 95a19e7b5d4ef9295e8c03b739615102335786af + migration/안내 diff 기준이다.
- 최종 자원 확인: Docker 컨테이너·검증 앱 JVM·ChromeDriver·alfio-previous/alfio-validation 임시 디렉터리 모두 잔여 없음. 사용자 데이터·프로세스·기존 worktree·도구 캐시를 삭제하지 않았다.
- 둘째 커밋 목적/제목: `feat: DB 마이그레이션 검증 자동 실행 연결`. migration workflow·해당 안내/공통 원격 후속 설명·이 단일 기록의 추가 구간만 포함한다. 첫 커밋과 동일 목적의 파일이 겹쳐도 이미 저장된 E2E 구간은 다시 커밋하지 않는다. 최종 SHA와 파일 일치 확인은 위 Git 제외 commits.md 및 완료 보고에 연결한다.
- 커밋 후 HEAD 차이로 이전 gate/resume가 무효화되는 것을 확인한다. 검증한 workflow/안내·검증 입력과 commit blob의 내용/체크섬이 동일하다는 근거만 사용하며 새 HEAD에서 테스트/gate를 재실행했다고 주장하지 않는다.
- 로컬 구현·실제 검증 완료. 원격 main PR 자동 실행 → 같은 PR 후속 취소 → main push → 수동 실행 → 문서 전용 제외는 미실행. 실제 실행 URL 없음; 이슈 전체 완료는 미선언이다. 푸시·PR 생성·머지·원격 실행·외부 댓글·이슈 수정·노션 수정은 수행하지 않는다.
