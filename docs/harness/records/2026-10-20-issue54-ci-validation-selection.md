# 이슈 #54 CI 선택 검증 보완

- 작업 ID / 날짜 / 상태: issue54 · 2026-10-20 · in_progress
- 요청: [이슈 #54](https://github.com/WookJaes/alf.io/issues/54), 최신 본문 updatedAt `2026-10-20T01:43:48Z`를 기준으로 선택 검증·공통 입력·Python 회귀 CI·PR 실행 취소를 수정하고 논리적 단위로 로컬 커밋한다. push·PR 생성·외부 댓글·이슈 종료·태그·배포는 제외한다.
- 실행 모드·실제 담당: 현재 Codex가 구현·검증·QA·리뷰를 순차 자체 수행. 하위 에이전트·별도 실행기 없음.
- 초기 Git: `main`, 추적·미추적 변경 없음. fetch 후 HEAD와 origin/main 모두 `c3d585bf3f605d51251e656d19f19cfbae085423`. `fix/54-ci-validation-selection`을 해당 origin/main에서 생성.
- 읽은 지침: 루트 AGENTS.md(하위 없음), ai/WORKFLOW.md, ai/routing.md, docs/conventions/git.md, docs/harness/local-validation.md, ai/templates/work.md, ai/roles/verification.md·review.md. 코드 입력 조사와 docs/testing/automatic-validation.md의 관련 절을 확인했다.
- 선행 의도: 닫힌 이슈 #21·#23·#39 및 병합 PR #22·#24·#40의 최신 본문 확인. PR/main 자동 검증·문서 제외·선택 검증·단일 JAR·기존 테스트·이미지 조건을 보존한다. 선행 기록의 원격 미검증을 원격 통과로 해석하지 않는다.
- 환경: 저장소 루트, macOS, Python 3.9.6, Bash·Git. 새 패키지나 의존성 없음.
- 선언: `ai/local-state/issue54/plan.json`. 각 커밋 전 새 실행 ID로 init/run/begin/record/gate. 독립 수행 요구 없음.
- 계획: ① 선택 검증·입력 필터와 실제 코드 회귀 ② Python 전용 CI 연결·실패 전파 ③ PR 취소 분리. 각 단계에 직접 관련 문서·검증 기록을 포함한다.

## 변경과 검증

### 선택 검증과 입력 조사

- 수정 전 현재 workflow의 `detect_changes` 안 실제 Bash를 추출해 임시 Git 저장소의 변경 커밋에 실행했다. 분류 알고리즘을 테스트에 복제하지 않았다. 문서 전용·문서 혼합에서 full=true가 재현됐다. 8개 테스트 실행, 실패 subtest 포함 24건, 종료 1. 단일·양쪽 영역·공통 설정·main/태그 전체 빌드 보존 사례는 통과했다.
- 실제 입력: e2e.sh → e2eValidation, migration.sh → 고정 이전 소스 Java 17 빌드 + migrationValidation. 두 현재 task 모두 bootJar·전체 테스트 compile/runtime 입력을 사용한다. bootJar는 양쪽 frontend·public index 변환·MJML 및 src/main 리소스를 포함하고 lib_exclude를 직접 읽는다. Lombok 플러그인과 루트 lombok.config가 컴파일 정책에 영향을 준다. gradle/**(버전 카탈로그·Wrapper), settings/build/gradle.properties·Wrapper 및 frontend 잠금 파일은 기존 감지 범위다.
- 누락 보완 대상: lombok.config, lib_exclude, config/**(Gradle license HEADER 입력), buildSrc/**·build-logic/**(추가될 경우 공통 Gradle 빌드 로직). 현재 buildSrc·build-logic은 없으며 현재 존재하는 입력이라고 주장하지 않는다. src/main/dist는 이미 src/main/**에 포함된다. 이전 릴리스의 입력은 고정 SHA에 묶여 있어 현재 frontend 감지 범위를 줄일 근거가 없다.
- 제품·API·앱 설정·DB 스키마·의존성·Gradle task·E2E/DB/브라우저 시나리오 변경 없음.

## 실패·리뷰·한계

- 최초 sandbox 내 fetch는 .git 쓰기 제한, gh 조회는 네트워크 제한으로 실패했다. 승인된 작업 범위의 권한 확장 실행으로 fetch·원격 조회를 완료했다.
- 로컬 경로 matcher는 이 저장소에서 사용하는 순서 있는 리터럴 glob만 다룬다. YAML 전체 파서 또는 GitHub 서버의 diff·이벤트 동작을 검증했다고 주장하지 않는다.
- 실제 제품 빌드·E2E/DB/브라우저·CodeQL 분석·원격 Actions 신규 실행·취소는 아직 수행하지 않았다. 제품과 검증 task 본체가 바뀌지 않는 이번 CI 조건 수정에는 로컬 실제 분류·Python 테스트와 정적 보존 대조를 적용한다. 원격 후속은 별도 승인된 push·PR 이후 관측해야 한다.

### 첫 번째 커밋 검증

- 최종 테스트를 변경 전 origin/main에 다시 적용: 8개 실행, 실패 subtest 30개·오류 0. 변경 후 동일 테스트 8개 통과(종료 0). 최초 24개 실패와 차이는 혼합 사례의 각 방향을 별도 subtest로 끝까지 실행하도록 보강했기 때문이다.
- 대표 결과: 문서 전용은 job 없음, Java+frontend 문서는 backend_check, frontend+src/config 문서는 frontend_check, 실제 양쪽은 build, 공통 설정은 build, main push·문서 태그 push도 build. 확장자 md/rst/adoc 각각 확인.
- `issue54-selection` run: 하네스 구조 검사 종료 0, CI 회귀 실제 8건·결과 검사기 실제 6건·하네스 회귀 실제 287건 모두 실패/오류 0. 하네스 Windows 전용 3건은 macOS에서 skip(전체 발견 290), 실제 실행에 포함하지 않았다.
- Ruby 2.6.10 YAML 파싱과 기준 커밋 객체 비교: 기존 6개 workflow에서 허용한 분류·필터 변경 외 동등함을 확인(종료 0). actionlint는 설치되어 있지 않아 실행하지 않았다.
- 자체 QA·리뷰: 실제 diff·회귀 코드·문서와 이슈 조건 대조, 차단 사항 없음. 무시되는 근거 `ai/local-state/issue54/qa-selection.md`·`review-selection.md`에 연결. `gate issue54-selection` 통과(종료 0). staged diff와 공백 검사를 거쳐 선택 검증·입력 필터·회귀·직접 관련 문서를 함께 커밋한다.

### 두 번째 커밋: 결과 검사기 Python CI

- 첫 커밋: `6eb61cd5b` — 선택 검증·공통 입력 감지·실제 분류/필터 회귀·안내를 함께 포함했다.
- `CI validation checks` 신설: main 대상 PR과 main push의 scripts/validation/*.py·scripts/ci/**·workflow 변경에서 Python 3.13만 준비한다. Java·Docker·Gradle 준비 없이 결과 검사기와 CI 회귀를 별도 단계로 실행한다. 문서 전용 제외·읽기 권한·5분 제한을 적용했다.
- 실제 명령은 workflow에서 읽어 임시 복사본에 실행했다. 정상 결과 검사기 회귀 6건 종료 0, checker의 sys.exit를 print로 변조하면 실패가 드러나 종료 1, 추가 실패 회귀도 발견되어 종료 1을 확인했다. 실제 저장소 checker나 테스트는 변조하지 않았다.
- 최초 Python CI 테스트 2건이 로컬의 `python` 명령 부재로 실패했다. Ubuntu workflow와 로컬 모두 사용할 수 있는 `python3`로 명시하고 재검증했다. 테스트 기대값을 완화하지 않았다.
- `issue54-python`: 구조 검사 종료 0, CI 회귀 실제 11건·결과 회귀 실제 6건, 실패/오류/스킵 0, 모두 종료 0. 실제 CI의 두 unittest 명령과 일치한다. 기존 하네스 본체/회귀는 바뀌지 않아 앞 단계 287건을 새 실행으로 재집계하지 않았다.
- 새 YAML 파싱·기존 6개 workflow 보존 대조 종료 0. 자체 QA·리뷰와 gate 통과(종료 0). 관련 diff·staged diff·공백 검사를 확인해 CI·직접 관련 회귀·문서를 함께 커밋한다. 로컬 Python 3.9.6 실행이며 원격 Python 3.13·Actions job 관측은 미실행이다.
