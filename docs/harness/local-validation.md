# 로컬 실행 결과·검증 게이트

[AGENTS.md](../../AGENTS.md) → [WORKFLOW](../../ai/WORKFLOW.md) → 이 문서 또는 필요한 [역할](../../ai/routing.md) 순서로 탐색한다. Python 3.9 이상 표준 라이브러리와 Git만 필요하다. 에이전트·제품 클래스·DB·화면 선택자에 결합하지 않는다.

## 저장과 선언

`ai/local-state/<실행 ID>/`에 `plan.json`, `state.json`, 항목별 최소 실행 영수증을 저장한다. `.gitignore`가 이 디렉터리와 Python 캐시를 제외한다. `init`과 후속 명령은 제외 규칙 및 추적 파일 유무를 확인한다. 강제 `git add -f`를 권한 수준에서 막는 장치는 아니다. 개별 상태는 CI에 전송하지 않으며 결과 JSON·원본 로그를 저장소·노션에 첨부하지 않는다.

원본 stdout/stderr는 디스크에 저장하지 않는다. 마지막 출력 일부를 메모리에서 테스트 요약 추출에만 사용한다. 상태에는 명령, 건수, 시각, 코드·조건 해시, 짧은 요약과 근거 경로·체크섬만 남긴다. 명령 인자·완료 조건·수동 근거에는 비밀값·실제 개인정보·오류 덤프를 넣지 않는다. 환경 변수는 값 대신 해시를 기록한다. 해시도 비밀정보 저장 수단으로 삼지 않는다.

작업 시작 전에 아래 형태의 선언을 `ai/local-state/plan.json`에 작성한다. 필수 항목은 최소 하나 필요하며 Review·QA를 포함할지는 작업 완료 기준에 맞춰 정한다. 이번 예시의 `independent: false`는 동일 Codex의 자체 확인이다. 독립 Review·QA가 요구되면 `true`로 선언하고 실제 별도 수행자의 근거를 확보한다. 역할 이름은 실제 에이전트 인스턴스 이름과 구분한다.

```json
{
  "version": 1,
  "completion": "구조 검사·회귀·자체 QA·Review를 완료한다",
  "inputs": ["ai/tests/check.py"],
  "environment": [],
  "checks": [
    {"id": "structure", "kind": "static", "required": true,
     "role": "verification", "condition": "링크·필수 파일 검사 통과",
     "command": ["python3", "-B", "ai/tests/check.py"]},
    {"id": "regression", "kind": "test", "required": true,
     "role": "verification", "condition": "실제 회귀 테스트 1건 이상, 실패·오류 없음",
     "adapter": "unittest",
     "command": ["python3", "-B", "-m", "unittest", "discover", "-s", "ai/tests", "-p", "test_*.py"]},
    {"id": "qa", "kind": "qa", "required": true,
     "role": "verification", "condition": "합성 CLI 전체 흐름 확인",
     "procedure": "임시 저장소에서 init/run/begin/record/gate/resume 확인",
     "independent": false},
    {"id": "review", "kind": "review", "required": true,
     "role": "review", "condition": "diff와 이슈 완료 조건 대조",
     "procedure": "대상 diff·근거·제외 범위를 자체 리뷰",
     "independent": false}
  ]
}
```

`inputs`에는 환경·검증 설정 파일을 지정한다. Git 제외 파일도 명시하면 해시 비교에 포함한다. 파일은 저장소 안의 상대 경로이며 누락되면 판단을 거부한다. `environment`에는 검증에 영향을 주는 환경 변수 이름을 지정한다. 지정한 변수가 없으면 통과하지 않는다. 운영체제·Python 버전도 자동 비교한다. 도구 버전·DB·브라우저 등 외부 환경은 수행자가 확인해 비밀값 없는 버전/환경 요약 파일을 `inputs`로 선언한다. 외부 환경 변화를 자동 탐지한다고 주장하지 않는다.

## 명령과 실행 시점

저장소 루트에서 실행한다. 다른 저장소에는 `--root <경로>`를 하위 명령 앞에 사용한다. 명령 실행 디렉터리는 해당 루트이며 shell 해석 없이 인자 배열을 실행한다. 임의 명령 실행은 `run`에서만 수행한다.

```sh
python3 -B ai/harness/run.py init issue-17 ai/local-state/plan.json
python3 -B ai/harness/run.py run issue-17 structure
python3 -B ai/harness/run.py run issue-17 regression
python3 -B ai/harness/run.py begin issue-17 qa
# 선언한 QA 절차를 실제 수행하고 짧은 근거 파일 작성
python3 -B ai/harness/run.py record issue-17 qa --evidence ai/local-state/qa.md --scope '합성 CLI 전체 흐름' --reporter '현재 Codex 자체 QA' --summary '정상 통과와 변경 후 거부 확인'
python3 -B ai/harness/run.py begin issue-17 review
# 실제 diff와 근거를 검토하고 짧은 근거 파일 작성
python3 -B ai/harness/run.py record issue-17 review --evidence ai/local-state/review.md --scope '하네스 diff와 완료 조건' --reporter '현재 Codex 자체 리뷰' --summary '범위·게이트·회귀 대조 완료'
python3 -B ai/harness/run.py show issue-17
python3 -B ai/harness/run.py gate issue-17
python3 -B ai/harness/run.py resume issue-17
```

- `init`: 선언 검증·제품 출발 커밋·실행 ID·초기 미실행 상태를 저장. 기존 ID 덮어쓰기 금지.
- `run`: 시작 시 코드·환경·조건을 저장하고 실제 명령을 실행. 종료 시각·종료 코드·요약·근거 영수증 기록. 실행 도중 상태가 바뀌어도 무효.
- `begin` → `record`: 수동 QA·Review 시작 시각·대상을 먼저 고정. 실제 수행 후 종료 시각·확인 범위·보고 주체·근거 체크섬을 기록. 독립 수행 요구 시 `--independent`로 실제 별도 수행 사실을 보고한다. 시작 없이 결과만 기록할 수 없다.
- `show`: 역할·종류·명령/절차·시각·종료 코드·건수·대상·요약과 판정 조회.
- `gate`: 필수 항목 모두 유효해야 종료 코드 0. 실패·미실행·실행 중·무효·근거 부족은 1. 기록·환경 확인 불가는 2.
- `resume`: 같은 검사를 읽기 전용으로 수행하고 유지할 결과와 다음 단계·이유를 표시. 상태 수정·명령 실행·커밋·푸시·PR·머지는 하지 않는다.
- `reuse`: 동일 코드·하네스·전체 선언·환경에서 검증된 결과를 새 ID에 연결. 원래 실행 시각을 보존하고 출처·재사용 시각·이유를 기록. 새 실행으로 세지 않는다.

```sh
python3 -B ai/harness/run.py reuse next-run regression --source issue-17 --reason '동일 코드·환경·완료 조건에서 이미 확인'
```

단일 작업 ID의 변경 명령은 순차 수행한다. 실행 중 프로세스가 중단되면 `running`이 남아 완료로 판정되지 않는다. 누락·손상된 기록은 신뢰할 수 있는 사본으로 복구하거나 새 ID로 선언하고 필요한 검증을 재실행한다. 상태 파일에 `completed`를 적어도 통과하지 않는다.

## 종류별 근거와 한계

| 종류 | 통과에 필요한 근거 |
|---|---|
| test / unittest | 실제 실행 명령 영수증, 단일 unittest 최종 요약, 실행 건수 1 이상, 실패·오류 0 |
| test / junit | 새 보고서의 실제 testcase, 실행 건수 1 이상, 실패·오류 0, 보고서 경로·체크섬 |
| build | 실제 명령 종료 0, 실행 영수증, 캐시·미실행 표시 없음 |
| static | 실제 명령 종료 0, 실행 영수증, 캐시·미실행 표시 없음 |
| qa / review | begin 당시 대상, 실제 확인 기간, 범위·절차·보고 주체·요약, 비어 있지 않은 근거 파일·체크섬, 요구된 독립 수행 보고 |

테스트의 `executed`는 스킵을 제외한 실제 수행 건수이며 실패·오류 건수를 별도 기록한다. 다른 종류에는 테스트 건수를 적용하지 않는다. JUnit은 `adapter: junit`, `report: ai/local-state/<새 경로>.xml`을 선언하고 해당 경로에 보고서를 생성하는 명령을 사용한다. 기존 보고서가 있으면 실행을 거부하므로 재실행에는 새 경로를 사용하거나 자신이 만든 기존 보고서를 정리한다. `UP-TO-DATE`·`FROM-CACHE`·`NO-SOURCE`를 감지하면 새 실행 통과로 처리하지 않는다. 다른 도구의 캐시는 도구에 맞는 실제 실행 옵션을 선언하는 책임이 있다.

JSON의 모양만으로 통과하지 않는다. 영수증·시각·종류별 근거·현재 대상과의 일치까지 검사한다. 로컬 파일을 악의적으로 모두 위조하는 행위나 명령이 실제 목적에 적합한지, 보고자가 진실하게 확인했는지를 증명하는 보안 실행기는 아니다. 수동 범위·독립 수행의 충분성은 Review와 WORKFLOW에서 확인한다. 결과를 기록한 주체와 선언한 수행 역할을 혼동하지 않는다.

## 무효화와 재개

Git 추적 파일 및 Git에서 무시하지 않는 미추적 파일의 내용·삭제·실행 권한과 HEAD를 비교한다. 제품 식별과 하네스 버전 해시는 분리하며 제품 파일 하나라도 달라지면 Review·QA를 포함한 기존 결과 전체를 보수적으로 무효화한다. 선언·명령·완료 조건·입력 설정·환경·하네스가 바뀌어도 전체 결과를 재검증한다. 파일별 세밀한 영향 분석은 하지 않는다.

하네스 경계는 `AGENTS.md`, `.gitignore`, `ai/**`, `docs/harness/**`, `docs/conventions/git.md`, 하네스 CI 파일이다. 로컬 상태·임시 근거·Python 캐시와 `docs/harness/records/**`의 결과 요약은 코드 식별에서 제외한다. 이 제외를 제품 코드를 숨기는 용도로 사용하지 않는다. Git 제외 제품·환경 파일이 검증에 영향을 주면 반드시 `inputs`에 선언한다. 외부 프로세스·서비스 상태는 선언한 환경 요약과 실제 확인에 의존한다.

`resume`은 유효한 결과를 유지해 표시하고 나머지 단계만 안내한다. 무효 판정은 현재 상태에서 계산하므로 상태 파일을 고쳐서 이전 `passed`를 덮어쓰지 않는다. 변경을 정확히 되돌려 코드·환경·조건이 이전 검증과 같아지면 그 결과는 다시 유효하다. 실행 중 중단된 항목은 다시 실행한다.

## 기존 제품 커밋에 적용

제품 코드·E2E·DB 환경을 가져오지 않고 하네스 파일만 옮긴다. 이번 변경 목록은 `.gitignore`, `ai/WORKFLOW.md`, `ai/routing.md`, `ai/harness/run.py`, `ai/tests/check.py`, `ai/tests/test_run.py`, `ai/tests/test_check.py`, `ai/roles/review.md`, `ai/roles/verification.md`, `ai/templates/work.md`, `docs/harness/README.md`, 이 안내다. `ai/roles/documentation.md`, `implementation.md`, `orchestration.md` 삭제도 포함한다. 이번 작업 기록은 이식에 불필요하다.

현재 변경을 파일 목록으로 제한한 패치로 내보내고 **별도로 새로 만든 적용용 checkout**에서 `git apply --check` → `git apply` → 하네스 검사·회귀 테스트를 수행한다. 기존 실험 worktree는 수정하지 않는다. 이전 제품 커밋에 기존 하네스가 없다면 현행 AGENTS·ai·docs/harness 안내·docs/conventions/git.md·하네스 CI의 필요한 파일도 하네스만 복사해 탐색 경로를 확보한다. `.gitignore`는 기존 규칙을 보존하고 로컬 상태 제외 규칙만 병합한다. 현행 제품 디렉터리·제품 설정·검증 환경은 복사하지 않는다.

이번 미커밋 변경을 내보낼 때 새 파일은 일반 `git diff`에서 빠지므로 아래처럼 별도로 포함한다. `--no-index`의 종료 코드 1은 차이가 있다는 정상 결과다. `.gitignore`는 패치에서 제외하고 적용 대상에서 `ai/local-state/`, `ai/harness/__pycache__/`를 기존 규칙에 추가한다.

```sh
git diff --binary -- ai/WORKFLOW.md ai/routing.md ai/roles ai/templates/work.md ai/tests/check.py ai/tests/test_check.py docs/harness/README.md > /tmp/alfio-harness.patch
git diff --no-index -- /dev/null ai/harness/run.py >> /tmp/alfio-harness.patch
git diff --no-index -- /dev/null ai/tests/test_run.py >> /tmp/alfio-harness.patch
git diff --no-index -- /dev/null docs/harness/local-validation.md >> /tmp/alfio-harness.patch
# 새 적용용 checkout에서만 실행
git apply --check /tmp/alfio-harness.patch
git apply /tmp/alfio-harness.patch
python3 -B ai/tests/check.py
python3 -B -m unittest discover -s ai/tests -p 'test_*.py'
```

기존 CI가 있다면 하네스 검사와 unittest discover만 연결한다. 이 저장소의 기존 [하네스 CI](../../.github/workflows/harness-check.yml)는 새 회귀 파일을 자동 발견한다. 기존 제품 CI·필수 검사는 유지한다. 로컬 상태 업로드·원격 작업 상태 검사는 추가하지 않는다. 이식 후 작업별 제품 명령·환경·완료 조건을 새로 선언하며 이번 작업에서는 제품 이슈 재실험을 시작하지 않는다.
