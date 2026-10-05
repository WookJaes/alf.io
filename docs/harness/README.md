# 프로젝트 하네스

현재 Codex에서 “요청한 기능을 하네스 절차에 따라 구현·검증해줘” 또는 “이전 작업 기록을 읽고 재개해줘”처럼 요청한다. 루트 [AGENTS.md](../../AGENTS.md)가 [워크플로우](../../ai/WORKFLOW.md)와 [라우팅](../../ai/routing.md)을 안내한다. 별도 Codex CLI·SDK·로그인이 필요하지 않다.

## 폴더

| 위치 | 내용 |
|---|---|
| ai/WORKFLOW.md, routing.md | 현재 Codex의 작업 절차와 역할 선택 |
| ai/roles/ | 역할 7개의 책임; 요청 시 필요한 문서만 조회 |
| ai/templates/ | 통합 작업 기록, 실패·기억 양식 |
| ai/memory/ | 확인된 지식과 인덱스 |
| ai/tests/ | 문서·기억·증거 검사와 그 회귀 테스트 |
| docs/harness/records/ | 실제 작업·검증·실패·리뷰 이력 |
| docs/harness/evidence/ | 검토 가능한 증거 |
| ai/local-evidence/ | Git 제외 임시 로그·증거 |

기본은 단일 Codex가 책임을 순차 수행한다. 독립 리뷰·다중 에이전트 실행은 실제 분담했을 때만 표시한다. 모델 버전만으로 역할·기억·테스트를 없애지 않으며 작업별 필요성으로 선택한다.

## 검사

Python 표준 라이브러리만 사용한다. 검사 도구는 로컬 파일만 읽고 문서 링크·역할 파일·기억 근거·증거 체크섬을 확인한다. 모델을 호출하거나 에이전트 상태 전환을 강제하지 않는다.

```sh
python -B ai/tests/check.py
python -B -m unittest discover -s ai/tests -p 'test_*.py' -v
```

`python`이 없는 환경에서는 `python3`로 실행한다.

## GitHub Actions 자동 검사

[하네스 전용 워크플로우](../../.github/workflows/harness-check.yml)는 `AGENTS.md`, `ai/**`, `docs/harness/**`, `docs/conventions/git.md` 또는 워크플로우 자체를 변경한 PR과 `main` 푸시에서 실행된다. 제품 빌드·DB·Docker 없이 Python 3.13 표준 라이브러리로 Linux(`ubuntu-latest`)와 Windows(`windows-latest`)에서 위 두 명령을 실행한다. 기존 제품 CI는 그대로 실행된다.

Windows에서는 다음 명령을 추가로 실행해 UTF-8 모드를 끈 상태에서도 실제 저장소의 UTF-8 문서를 검사한다. Python 버전·기본 파일 인코딩·UTF-8 모드를 로그에 남긴다. `PYTHONIOENCODING=utf-8`은 출력 로그 인코딩만 지정하며 기본 파일 인코딩이나 UTF-8 모드를 바꾸지 않는다.

```sh
python -B -X utf8=0 -c "import locale, sys; print(sys.version); print('default_encoding=' + locale.getencoding()); print('utf8_mode=' + str(sys.flags.utf8_mode)); assert sys.flags.utf8_mode == 0"
python -B -X utf8=0 ai/tests/check.py
python -B -X utf8=0 -m unittest discover -s ai/tests -p 'test_*.py' -v
```

전체 회귀 테스트에는 CP949 기본 인코딩 모사와 실제 기억 양식 검증이 포함된다. `unittest`의 `Ran N tests`로 실제 실행 건수를 확인한다. 검사와 테스트를 각각 별도 단계에서 실행하므로 하나라도 종료 코드가 0이 아니면 해당 Actions 작업이 실패한다. 실제 Actions 실행 URL·환경별 결과·건수를 PR에 기록하고, 미실행·스킵은 통과로 표시하지 않는다.

제품 변경은 작업에 맞는 테스트·화면 확인을 별도로 수행한다. 하네스 검사 성공은 제품 검증 성공을 의미하지 않는다. 작업 기록에서 실제 결과와 생략 범위를 구분한다.
