# 로컬·포크 자동 검증

저장소 루트에서 실행한다. 제품 E2E·DB 검증에는 Java 25, Docker Engine(실행 중), Python 3, 인터넷 접근이 필요하다. Python 회귀만 실행할 때는 아래 별도 조건을 따른다. E2E는 설치된 Chrome과 Selenium Manager가 준비한 호환 드라이버를 사용한다. CI의 ubuntu-latest에도 Chrome이 설치되어 있어야 한다. 새 라이브러리는 추가하지 않는다.

## 일반 빌드 CI

일반 빌드는 관련 변경이 포함된 main 대상 `pull_request`(opened·synchronize·reopened)와 main 브랜치 `push`에서 실행한다. 같은 저장소의 PR 브랜치 push에서는 실행하지 않아 동일 변경의 push/PR 중복 검증을 줄인다. PR은 기본 checkout의 merge ref, main push는 반영된 커밋을 검증한다. PR 생성 전 브랜치 검증은 필요한 로컬 Gradle 명령으로 수행한다.

경로 필터는 `src/**`(제품·테스트·리소스·Dockerfile), `frontend/**`(공개/관리자 코드·설정·잠금 파일), `build.gradle*`, `settings.gradle*`, `gradle.properties`, `gradle/**`, `buildSrc/**`, `build-logic/**`, `config/**`(현재 license의 HEADER), `lib_exclude`(bootJar 제외 입력), `lombok.config`(생성자 메타데이터·생성 코드·컴파일 정책), `gradlew`, `gradlew.bat`, `.codecov.yml`, `codecov.yml`, 해당 `.github/workflows/build-on-push.yml`이다. 마지막 `!**/*.md`, `!**/*.rst`, `!**/*.adoc`로 경로 내부 문서를 제외한다. README·docs·하네스 설명·별도 website는 일반 빌드 입력에 포함하지 않는다.

PR 내부 변경 분류도 `.md`·`.rst`·`.adoc`를 영역 판정보다 먼저 제외한다. 백엔드 코드와 프론트 문서를 함께 바꾸면 `backend_check`, 프론트 코드와 백엔드/공통 설정 경로의 문서를 함께 바꾸면 `frontend_check`를 선택한다. 실제 양쪽 코드 또는 공통 빌드 설정 변경은 전체 `build`를 선택한다. main push·태그는 전체 빌드를 유지한다. 분류·job 선택 회귀는 `python3 -B -m unittest discover -s scripts/ci -p 'test_*.py' -v`로 실행하며, 임시 Git 커밋에 실제 워크플로우의 분류 Bash와 job 조건을 적용한다.

PostgreSQL 15·16·17의 기존 `build distribution jacocoTestReport`, Java 25·Codecov 업로드·Docker 이미지 jobs는 유지한다. 기존 릴리스 배포 입력을 보존하기 위해 모든 태그 push도 유지한다. [GitHub 규칙](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#onpushpull_requestpull_request_targetpathspaths-ignore)상 태그 push에는 경로 필터가 적용되지 않으므로 문서 전용 제외는 브랜치 push/PR에 대한 조건이다. 이미지 jobs의 기존 `alfio-event/alf.io` 저장소 조건은 유지돼 이 포크에서는 실행하지 않는다.

원격 실행이 허용되면 main 대상 PR에서 단일 영역은 해당 선택 검사, 양쪽 영역·공통 설정은 PostgreSQL 15·16·17 전체 빌드 3개가 실행되고 PR 브랜치 push 빌드는 생기지 않는지 확인한다. 문서 전용 PR/브랜치 push 및 main push에서는 실행하지 않아야 하며, 제품 변경의 main 반영 후에는 실행해야 한다. 태그 검증이 필요할 때는 기존 실제 릴리스 절차에서 확인하고 검증만을 위해 임의 태그·이미지 배포를 만들지 않는다. 실행 URL·이벤트·검증 커밋·결과는 후속 기록에 남긴다.

## CodeQL CI

CodeQL도 관련 변경이 포함된 main 대상 `pull_request`와 main 브랜치 `push`에서 실행하며 PR 브랜치 push는 제외한다. 기존 `0 0 * * *` 정기 실행(UTC 00:00, 한국 시간 09:00)은 그대로 유지하며 문서 변경 여부와 무관하게 기본 브랜치를 분석한다. 기존 CodeQL init·`./gradlew build -x test`·analyze·권한·Java 설정을 바꾸지 않는다.

일반 빌드와 공통인 `src/**`, `frontend/**`, `build.gradle*`, `settings.gradle*`, `gradle.properties`, `gradle/**`, `buildSrc/**`, `build-logic/**`, `config/**`, `lib_exclude`, `lombok.config`, `gradlew`, `gradlew.bat`를 포함한다. CodeQL은 현재 Java·JavaScript·Python·Actions를 자동 감지하므로, 추가로 저장소 전체 `**/*.java`, `**/*.kt`, `**/*.kts`, `**/*.js`, `**/*.jsx`, `**/*.ts`, `**/*.tsx`, `**/*.mjs`, `**/*.cjs`, `**/*.py`, `**/*.html`과 패키지 입력 `**/package.json`, `**/package-lock.json`, `**/pnpm-lock.yaml`, `**/pnpm-workspace.yaml`, `**/yarn.lock`, `.github/workflows/**`, `.github/actions/**`, `.github/codeql/**`를 포함한다. 하네스/검증 도구 Python 및 website의 JavaScript·HTML·패키지 변경도 분석 실행 대상이다. HTML은 스크립트를 포함할 수 있는 분석 입력으로 취급한다.

마지막 `!**/*.md`, `!**/*.rst`, `!**/*.adoc`로 문서 전용 변경을 제외한다. 이는 [워크플로우 실행 필터](https://docs.github.com/en/code-security/reference/code-scanning/workflow-configuration-options)이며 CodeQL 분석 자체의 languages/paths-ignore를 변경하거나 검사 대상을 줄이지 않는다. 빌드의 Codecov 전용 설정은 CodeQL 전용 입력으로 추가하지 않는다. 태그 push는 일반 빌드/기존 배포 경로에서 처리하고 CodeQL은 main 브랜치·PR·정기 실행으로 검증한다.

후속으로 제품 변경 PR과 하네스 Python/Actions 변경 PR에서 분석이 실행되는지, 후속 PR 브랜치 push가 별도 분석을 생성하지 않는지, 문서 전용 PR/main push에서 분석이 생기지 않는지 확인한다. 제품 변경 main push 및 기존 정기 실행의 분석/결과 업로드 성공도 확인한다. 빌드와 함께 경로 필터의 [GitHub diff 제한](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#git-diff-comparisons)을 따르며, 로컬 대표 경로 대조를 실제 원격 이벤트 확인으로 표시하지 않는다. 브랜치 보호·필수 검사 지정은 변경하지 않는다.

## PR 후속 실행 취소

일반 빌드·CodeQL·하네스는 `${{ github.workflow }}-${{ github.event_name }}-${{ github.event.pull_request.number || github.run_id }}` 그룹을 사용한다. `cancel-in-progress`는 `pull_request`에서만 true다. 같은 PR의 동일 워크플로우에 후속 커밋이 올라오면 이전 실행을 취소하고 최신 변경을 검증한다. 다른 PR·워크플로우는 별도 그룹이며 main push·태그·CodeQL 예약 실행은 각 run ID로 분리해 서로 취소하지 않는다. 기존 E2E·DB 마이그레이션·관리자 브라우저 회귀의 취소 식과 정책도 그대로 유지한다.

로컬 회귀는 실제 group/cancel 식에 같은 PR의 서로 다른 run ID, 다른 PR, 서로 다른 워크플로우 및 main push·태그·예약·수동 실행 값을 넣어 그룹 공유·분리를 확인한다. 이 검사는 GitHub 서버가 실행을 실제 취소했다는 증거가 아니다. 원격 후속 확인에서는 빌드·CodeQL·하네스 각각 같은 PR의 후속 커밋에서 이전 실행 cancelled와 최신 실행을 관측하고, 다른 PR·워크플로우·main 실행이 유지되는지 확인한다. CodeQL 정기 분석과 태그 빌드는 기존 일정·릴리스에서 관측하며 검증용 태그·배포는 만들지 않는다.

## CI 검증 도구의 Python 회귀

`CI validation checks`는 main 대상 PR과 main push에서 `scripts/validation/*.py`(결과 검사기·회귀), `scripts/ci/**`, `.github/workflows/**` 변경을 감지한다. 경로 내부 md/rst/adoc 문서는 제외한다. Python 3.13만 준비하고 Java·Docker·Gradle 준비 없이 아래 두 명령을 별도 단계로 실행한다. unittest 실패는 단계와 CI 실패로 전달된다.

```sh
python3 -B -m unittest discover -s scripts/validation -p 'test_*.py' -v
python3 -B -m unittest discover -s scripts/ci -p 'test_*.py' -v
```

워크플로우 회귀는 실제 CI의 결과 검사기 명령을 임시 복사본에서 실행한다. 정상 6건 통과 외에 검사기의 실패 거부를 무력화한 경우와 새 실패 테스트를 추가한 경우를 실행해 비정상 종료 전파를 확인한다. 합성 실패는 임시 복사본 안에서만 발생하며 실제 저장소 파일을 바꾸지 않는다. Python 전용 CI가 기존 E2E·DB 검증을 대체하지는 않는다.

## macOS·Windows의 로컬 Python 회귀

Python 회귀에는 Java·Docker·앱 기동이 필요하지 않다. 하네스는 Python **3.9 이상** 표준 라이브러리와 Git을 사용하며, 현재 Python CI는 **3.13**을 준비한다. `scripts/ci` 회귀에는 Git과 **Bash**도 필요하다. 저장소 루트에서 실행하며 임시 Git 저장소·합성 실패는 테스트의 임시 디렉터리 안에서만 만든다.

현재 `.github/workflows/ci-validation-check.yml`은 Ubuntu에서 두 Python 회귀를 `python3`로 실행한다. `scripts/ci/test_workflows.py`의 `PythonCITest`는 워크플로우의 실제 명령을 읽어 `bash --noprofile --norc -eo pipefail -c`로 실행한다. 따라서 **바깥 실행 명령만 `python` 또는 `py -3`로 바꿔도 내부 Bash의 요구사항은 해결되지 않는다.** Bash PATH에도 실제 Python을 실행하는 `python3`가 있어야 한다. 셸 별칭·함수만 정의하면 새 Bash에는 전달되지 않을 수 있다.

하네스 CI(`harness-check.yml`)는 Ubuntu·Windows에서 `python`으로 구조 검사와 `ai/tests`를 실행한다. Windows에서는 `PYTHONIOENCODING=utf-8`과 별도 `-X utf8=0` 회귀도 사용한다. 이것을 Ubuntu 전용 `scripts/ci` CI와 혼동하지 않는다. 테스트·워크플로우의 Python 선택 로직은 변경하지 않는다.

### macOS 준비·실행

Python 3.9 이상과 Git·Bash를 준비한다. 여러 Python 설치가 있으면 같은 터미널에서 실제 경로와 버전을 먼저 확인한다. 새 도구 설치나 시스템 PATH 변경을 이 문서의 명령이 자동 수행하지 않는다.

```sh
command -v python3
python3 -c 'import sys; print(sys.executable); print(sys.version); assert sys.version_info >= (3, 9)'
git --version
bash --version
bash --noprofile --norc -eo pipefail -c 'command -v python3; python3 -c "import sys; print(sys.executable); print(sys.version); assert sys.version_info >= (3, 9)"'

python3 -B ai/tests/check.py
python3 -B -m unittest discover -s ai/tests -p 'test_*.py' -v
python3 -B -m unittest discover -s scripts/validation -p 'test_*.py' -v
python3 -B -m unittest discover -s scripts/ci -p 'test_*.py' -v
```

### Windows 준비·실행 (Git Bash)

Git for Windows의 Git Bash와 정상 Python 설치를 준비한다. 먼저 Git Bash에서 `python -c 'import sys; print(sys.executable); print(sys.version)'`가 실행되는지 확인한다. `python`이 없다면 `py -3 -c 'import sys; print(sys.executable); print(sys.version)'`로 확인하고 아래 첫 줄의 `python -c`를 `py -3 -c`로 바꾼다. 둘 다 실패하면 정상 Python 설치부터 준비한다.

Windows의 `python3`가 없거나 Microsoft Store 안내용 실행 별칭을 가리키면 `Python ` 출력·종료 코드 49 등으로 내부 회귀가 실패할 수 있다. `command -v python3`만으로 정상 설치를 판정하지 말고 실제 `sys.executable`·버전을 확인한다.

다음은 **Git Bash에서 저장소 루트**를 연 뒤 실행하는 세션 한정 예시다. 확인한 Python의 절대 경로를 임시 `python3` 실행 파일에 고정한다. 전역 PATH·Windows 앱 실행 별칭·관리자 권한·개발자 모드를 변경하지 않는다. 전체 괄호 블록은 하위 셸에서 실행되며 종료 시 원래 PATH로 돌아가고 임시 파일을 정리한다.

```bash
(
  set -e
  regression_python_exe="$(python -c 'import sys; print(sys.executable)')"
  regression_python_exe="$(cygpath -u "$regression_python_exe")"
  "$regression_python_exe" -c 'import sys; print(sys.executable); print(sys.version); assert sys.version_info >= (3, 9)'
  git --version
  bash --version
  regression_shim_dir="$(mktemp -d)"
  trap 'rm -f "$regression_shim_dir/python3"; rmdir "$regression_shim_dir"' EXIT
  printf '#!/usr/bin/env bash\nexec %q "$@"\n' "$regression_python_exe" > "$regression_shim_dir/python3"
  chmod +x "$regression_shim_dir/python3"
  export PATH="$regression_shim_dir:$PATH"
  export PYTHONIOENCODING=utf-8
  hash -r
  bash --noprofile --norc -eo pipefail -c 'command -v python3; python3 -c "import sys; print(sys.executable); print(sys.version); assert sys.version_info >= (3, 9)"'
  python3 -B ai/tests/check.py
  python3 -B -m unittest discover -s ai/tests -p 'test_*.py' -v
  python3 -B -m unittest discover -s scripts/validation -p 'test_*.py' -v
  python3 -B -m unittest discover -s scripts/ci -p 'test_*.py' -v
)
```

PowerShell의 `py -3`만으로 내부 Bash PATH를 준비한 것으로 간주하지 않는다. 위 검사는 하네스의 Windows 기본 인코딩 회귀(`-X utf8=0`) 전체를 대체하지 않는다.

Windows에서는 테스트 코드가 정한 운영체제 조건에 따라 POSIX 전용 실행 중개 테스트(`test_execution_gate`) 46건과 chmod 실행 비트 변경 테스트(`test_session`) 1건을 건너뛴다. 이 47건은 환경 준비 실패가 아닌 의도된 스킵이며 실제 실행 건수에 포함하지 않는다. 심볼릭 링크 생성 권한이 없어 WinError 1314가 발생하면 해당 링크 생성 테스트도 추가로 스킵될 수 있다. 다른 실패를 일괄 skip하지 않는다. 자세한 기준은 [하네스 로컬 검증](../harness/local-validation.md#windows-링크-검사의-실행-조건)을 따른다.

### 직접 실행과 기존 근거 구분

[PR #55 논의](https://github.com/WookJaes/alf.io/pull/55#discussion_r4321166389)와 [리뷰 재현 기록](https://github.com/WookJaes/alf.io/pull/55#discussion_r4321016028)은 **기존 Windows 근거**다. 당시 Windows 10 / Python 3.13.15 / Git Bash에서 `scripts/ci` 14건 중 2건이 내부 `python3` 때문에 실패했고 정상 Python 대체 명령을 PATH에 둔 후 14건이 통과했다고 보고했다. 이는 이번 문서의 전체 명령을 Windows에서 새로 실행했다는 뜻이 아니다.

후속 [PR #57 리뷰의 Windows 실행 결과](https://github.com/WookJaes/alf.io/pull/57#discussion_r4322514584)는 리뷰어가 Windows 10 / Python 3.13.15 / Git Bash에서 위 블록을 그대로 실행한 별도 근거다. 구조 검사, 하네스 실제 243건(전체 290건 중 운영체제 조건으로 47건 스킵), 결과 검사기 6건, CI 회귀 14건이 통과했고 임시 PATH·파일 정리도 확인했다고 보고했다. 이 결과는 작성자의 macOS 실행이나 Windows 직접 실행 결과로 합산하지 않는다.

이번 수정의 macOS 실제 실행 결과·건수와 Windows 미실행 범위는 [작업 기록](../harness/records/2026-10-20-issue56-python-validation.md)에 구분한다. Python 회귀 성공은 제품 컴파일·프론트 빌드·브라우저 E2E·DB 마이그레이션 성공과도 구분한다.

## 자동 E2E

```sh
bash scripts/validation/e2e.sh
```

현재 작업 트리의 `bootJar`를 빌드하고 `e2eValidation`을 실행한다. 테스트마다 전용 PostgreSQL 16.13 컨테이너·임시 디렉터리·별도 앱 JVM·헤드리스 Chrome 세션을 만든다. 앱 포트와 DB 포트는 실행별로 할당한다. 기존 DB·앱·Chrome 사용자 프로필을 사용하지 않는다. 앱은 dev·disable-jobs 프로필로 실행한다. 합성 관리자와 조직을 전용 DB에 준비하고 실제 브라우저로 무료 온라인 행사 생성·게시 → 공개 티켓 1장 선택 → 합성 예약자·참석자 입력 → 약관 동의·확정을 검증한다. 최종 화면 및 DB의 COMPLETE 예약·ACQUIRED 티켓·참석자 필드를 확인한다.

현재 템플릿의 관리자 `displayName`, `format`, `organizationId`, `freeOfCharge`, `actions-dpdwn`과 공개 `amount`, `show-event-continue`, `first-name`, `reservation-page.title`, `app-success`를 사용한다. DOM 존재·클릭 가능·URL 및 표시 상태를 단계별로 기다린다. 화면 변경으로 선택자나 대기 조건이 맞지 않으면 실패한다.

두 전용 검사는 ValidationBrowser의 공통 Chrome 설정을 사용한다. Linux 탐색 정지와 관련된 `--disable-crashpad-for-testing` 옵션은 사용하지 않는다. 페이지 전체 로딩 대신 DOM 준비(EAGER)를 기다리고, 페이지 탐색·스크립트의 제한 시간은 각각 30초다. 이후 각 단계의 필수 화면 요소와 최종 DB 상태를 기존 기준으로 확인한다. 브라우저 시작 전 로그인 페이지의 HTTP 200·폼 존재를 별도로 확인해 앱 응답 실패와 브라우저 탐색 실패를 구분한다. 전용 실행 스크립트는 `-Pverbose`로 이 상태·브라우저 버전·마이그레이션 결과를 출력하며 응답 본문이나 비밀값은 출력하지 않는다.

E2E 전용 작업에는 지연된 이미지 응답이 있어도 로그인 DOM을 사용할 수 있는 브라우저 회귀 1건이 추가되어 있다. 기본 예약 흐름 1건과 합쳐 실제 2건을 실행하며, 이것을 제품 기능 두 개의 검증으로 계산하지 않는다. 일반 제품 검사에서는 이 브라우저 회귀도 opt-in 조건으로 스킵된다.

무료 행사로 Stripe·실제 결제·유료 청구 검증을 대체한다. 온라인 행사로 외부 지도·지오코딩 호출을 제외한다. `MAILER_TYPE=disabled` 및 disable-jobs로 외부 메일·정기 작업을 막는다. 실제 결제·메일 전달 및 기존 커스텀 메시지 발송은 검증하지 않는다. 데모 프로필은 행사 게시를 금지하므로 사용하지 않는다.

GitHub Actions → **Local E2E validation** → **Run workflow**에서 검증할 브랜치를 선택한다. 외부 서버·BrowserStack 비밀 설정과 저장소 이름 제한이 없다. 관련 변경이 포함된 main 대상 `pull_request`(기본 opened·synchronize·reopened)와 main `push`에서 자동 실행한다. 문서 전용 변경은 자동 실행하지 않으며 수동 실행에는 경로 제한이 없다. 브랜치 보호·필수 검사 지정은 변경하지 않는다.

E2E 경로 필터는 `src/main/**`(Java·설정·SQL·템플릿·기존 관리자 화면), `src/test/**`(전용 검사와 모든 테스트 컴파일 입력), `frontend/**`(공개·관리자 코드·화면·프론트 설정과 잠금 파일), `build.gradle*`, `settings.gradle*`, `gradle.properties`, `gradle/**`(Wrapper·버전 카탈로그), `buildSrc/**`, `build-logic/**`, `config/**`(Gradle license HEADER), `lombok.config`(컴파일 정책), `lib_exclude`(bootJar 구성), `gradlew`, `gradlew.bat`, `scripts/validation/e2e.sh`, 공통 `scripts/validation/check-results.py`·`test_results.py`, 해당 `.github/workflows/e2e-test.yml`이다. 뒤에 `!**/*.md`, `!**/*.rst`, `!**/*.adoc`를 적용해 이 경로 안의 문서도 제외한다. `docs/**`·루트 안내·별도 website 변경은 포함하지 않는다.

취소 그룹은 `${{ github.workflow }}-${{ github.event_name }}-${{ github.event.pull_request.number || github.run_id }}`이다. 같은 PR의 동일 워크플로우만 그룹을 공유하고 `cancel-in-progress`는 PR에서만 true다. main push·수동 실행은 각 run ID로 분리하고 이전 실행을 취소하지 않는다. 다른 PR·DB 마이그레이션과도 분리한다. 취소된 실행은 검증 통과로 처리하지 않는다.

## DB 마이그레이션

```sh
bash scripts/validation/migration.sh
```

이전 공식 릴리스 [2.0-M5-2606](https://github.com/alfio-event/alf.io/releases/tag/2.0-M5-2606)의 커밋 `2b4759f9136cf5c6f5cb7784c30c9a09da217151`을 git archive로 임시 디렉터리에 추출한다. 로컬에 커밋 객체가 없으면 해당 SHA만 upstream에서 fetch한다. 작업 브랜치·사용자 파일은 변경하지 않는다. **원본 이전 소스·그 소스의 Gradle Wrapper를 Java 17로 직접 빌드**하고 `alfio.version=2.0-M5-2606`을 확인한다. 빌드 JAR의 SHA-256을 출력하고 실행 직전 재확인한다. 현재 코드는 현재 Wrapper·Java 25로 별도 빌드한다.

Java 17 경로를 `JAVA17_HOME`으로 지정한다. macOS에서는 설치된 Java 17을 `/usr/libexec/java_home -v 17`로 자동 발견하며 설치 작업은 하지 않는다. CI는 Java 17·25를 setup-java로 각각 준비한다. Python 3.8 이상·git·tar가 필요하며 추가 Python 패키지를 사용하지 않는다. 이전 소스와 빌드 산출물은 실행 종료 시 삭제하지만 Gradle/npm 도구 캐시는 남겨 재사용한다.

공식 릴리스의 배포 JAR은 조사 중 내부 버전이 태그와 다른 2.0-M6-SNAPSHOT으로 확인돼 최종 기준에서 제외했다. 배포 파일 이름만으로 이전 코드의 정확한 버전을 주장하지 않는다.

이전 릴리스 앱의 Flyway 11.7.2가 전용 PostgreSQL 16.13 DB를 초기화한다. 같은 헤드리스 Chrome UI로 합성 관리자 행사 생성·게시·무료 예약·확정을 수행한 후 조직·행사·설명·분류·예약·티켓의 주요 필드 snapshot과 공개 행사/분류/예약 API 조회 결과를 메모리에 보관한다. 기존 데이터를 현재 앱의 모델로 만들어 이전 버전 검증이라고 주장하지 않는다.

이전 앱의 종료 완료를 확인한 뒤 **현재 작업 트리의 bootJar**(Spring Boot 4.1.1/Flyway 12.11.0)로 같은 DB를 업그레이드한다. 행 수만 비교하지 않고 식별자·참석자·상태·가격·행사-분류-예약-티켓 관계를 비교하며 고아 관계 0을 검사한다. Flyway 기존 버전 이력·체크섬·success 보존, 현재 정의 validate 성공, pending/failed 0 및 행사 data migration의 현재 버전/COMPLETE를 확인한다. 앱이 data migration 오류를 healthz 성공 뒤 로그에만 남겨도 실패로 처리한다. 공개 행사·티켓 분류·기존 확정 예약의 실제 HTTP 조회 결과도 비교한다.

선정 근거: 이전 릴리스 CI의 PostgreSQL 10/15/16과 현재 포크 CI의 15/16/17이 겹치는 지원 major 16을 사용한다. 공식 PostgreSQL 16 지원은 [2028-11-09까지](https://www.postgresql.org/support/versioning/)다. 16.13은 E2E 실기동을 확인한 고정 마이너이며 최신 마이너 여부를 검사하는 작업은 아니다. 두 버전 간 새 버전 SQL/Java 마이그레이션은 없어 새 적용 수가 0일 수 있다. 그렇더라도 기존 DB의 현재 Flyway 검증·행사 data migration·보존 검사는 실제 수행한다. 자세한 실행 근거는 [작업 기록](../harness/records/2026-10-08-issue15-migration.md)에 있다.

GitHub Actions → **Local DB migration validation** → **Run workflow**에서 검증할 브랜치를 선택한다. 현재 코드 체크아웃을 빌드하며 외부 latest 앱 이미지·레지스트리 로그인·기존 DB 비밀값을 사용하지 않는다. 관련 변경이 포함된 main 대상 `pull_request`(기본 opened·synchronize·reopened)와 main `push`에서 자동 실행하며 `workflow_dispatch`도 유지한다. 문서 전용 변경은 자동 실행하지 않는다. 전용 JUnit class는 `alfio.e2e.MigrationValidatorTest`로 이동했다. 이전 소스·JAR·앱·DB·브라우저는 성공·실패 후 정리한다. 유료 결제·외부 메일·모든 과거 버전 조합·PostgreSQL major upgrade는 범위 밖이다.

마이그레이션 경로 필터도 `src/main/**`, `src/test/**`, `frontend/**`, `build.gradle*`, `settings.gradle*`, `gradle.properties`, `gradle/**`, `buildSrc/**`, `build-logic/**`, `config/**`, `lombok.config`, `lib_exclude`, `gradlew`, `gradlew.bat`, 공통 `scripts/validation/check-results.py`·`test_results.py`를 포함한다. 전용 입력은 `scripts/validation/migration.sh`와 `.github/workflows/migration-test.yml`이다. 뒤의 `!**/*.md`, `!**/*.rst`, `!**/*.adoc`로 경로 내부 문서도 제외한다. 현재 `bootJar`가 양쪽 프론트를 포함하고 이전 앱의 UI 예약 흐름과 업그레이드 후 공개 API를 확인하며, 전체 테스트 소스를 컴파일하고 공통 환경·브라우저·예약 흐름을 사용하므로 프론트와 테스트 입력도 필요하다. 공통 Gradle 입력 중 현재 없는 buildSrc·build-logic도 향후 추가될 빌드 로직의 누락을 막도록 포함한다. 이전 릴리스 소스는 기존 고정 SHA를 유지한다. 다른 전용 스크립트·워크플로우 및 별도 website는 포함하지 않는다.

취소 그룹·정책은 E2E와 같은 식을 사용하되 워크플로우 이름으로 분리한다. 같은 PR의 마이그레이션 새 실행만 이전 마이그레이션 실행을 취소한다. 다른 PR·E2E·main push·수동 실행과 섞이지 않으며 push·수동 실행은 run ID별로 분리해 취소하지 않는다.

## 원격 후속 확인

로컬 YAML·필터·취소 식 확인과 실제 로컬 테스트는 GitHub 이벤트의 동작 증거를 대신하지 않는다. 원격 실행이 허용된 후 다음 순서로 **두 워크플로우 각각** 확인한다.

1. 관련 코드가 바뀐 main 대상 PR에서 자동 실행과 테스트 실제 건수·종료 결과·검증 SHA를 확인한다. PR의 기본 checkout은 merge ref이므로 실행의 head SHA와 실제 `git rev-parse HEAD`도 구분해 기록한다.
2. 같은 PR에 후속 변경을 올려 이전 실행이 cancelled, 최신 실행이 실행되는지 확인한다. 다른 PR·다른 워크플로우의 실행은 유지되어야 한다. 취소를 통과로 계산하지 말고 임시 앱·DB 정리도 확인한다.
3. main 반영 후 push 이벤트로 실행되는지 확인한다. 새 push가 이전 push·수동 실행을 취소하지 않는지도 확인한다.
4. Actions → 해당 검증 → Run workflow에서 브랜치를 선택해 수동 실행한다. 문서 전용 변경도 수동 검증할 수 있다.
5. 문서 전용 PR·push에서는 자동 실행이 생기지 않는지 확인하고 각 실제 실행 URL·이벤트·검증 커밋·결과를 기록한다. 경로 필터는 [GitHub의 diff 판정](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#git-diff-comparisons)(3,000파일 제한, 1,000커밋 초과·diff timeout 시 실행)에 따르므로 큰 변경은 수동 확인도 필요하다. 필수 검사 지정은 이 작업에 포함하지 않는다.

## 실행 결과·정리

전용 테스트 task는 UP-TO-DATE로 생략되지 않으며 `--no-build-cache`를 사용한다. 앱 빌드의 변경 없는 입력은 재사용할 수 있지만 테스트 실행으로 계산하지 않는다. 실행 전 해당 task의 이전 XML만 제거한다. 종료 코드와 새 XML의 tests/failures/errors/skipped를 확인하고 테스트 0건·스킵이 하나라도 있으면 실패한다. Gradle·앱 준비·브라우저·DB 검사 실패는 비정상 종료로 전달된다.

테스트의 finally/AutoCloseable로 브라우저·앱·컨테이너·임시 앱 로그를 정리하고 Testcontainers Ryuk도 비정상 JVM 종료의 컨테이너 정리를 지원한다. 강제 OS 종료 등으로 정리 완료를 확인할 수 없으면 해당 실행을 성공으로 보고하지 않는다. 기존 사용자 컨테이너와 DB는 제거하지 않는다. GitHub 취소는 신호 전달 뒤 프로세스 트리를 강제 종료할 수 있으므로 finally·EXIT trap의 완료를 보장하지 않는다. 실행별 임시 자원·Ryuk·GitHub 호스팅 runner 폐기를 통한 격리를 유지하며, 취소 시 정리의 실제 완료 여부는 원격 후속 확인에서 관측한다.

결과는 무시되는 `build/test-results/<task>`와 `build/reports/tests/<task>`에 남는다. 준비 실패 로그와 E2E 오류 DOM은 진단용으로 `build/validation`에만 남고 커밋하지 않는다. 이 원본에는 테스트용 비밀값이 포함될 수 있으므로 외부 게시하지 않는다. 원본 결과·JSON·로그·덤프·실제 개인정보는 커밋하지 않고 작업 MD에는 버전·명령·종료 코드·실제 건수·실패/원인/조치/재검증 요약만 기록한다.

기존 일반 제품 검사는 `./gradlew build distribution jacocoTestReport -Dpgsql.version=16 --no-daemon`으로 실행한다. opt-in E2E·마이그레이션의 스킵은 제품 테스트 통과 건수에서 제외한다. 프론트 단위 테스트 환경·하네스 자동 판단/오류 복구는 변경하지 않는다.
