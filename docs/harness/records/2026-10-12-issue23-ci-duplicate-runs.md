# 이슈 #23 빌드·CodeQL 실행 최소화

- 작업 ID / 날짜 / 상태: issue23 / 2026-10-12 / in_progress
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
