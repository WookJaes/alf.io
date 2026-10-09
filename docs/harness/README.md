# 프로젝트 하네스

현재 Codex에서 “요청한 기능을 하네스 절차에 따라 구현·검증해줘” 또는 “이전 작업 기록을 읽고 재개해줘”처럼 요청한다. 루트 [AGENTS.md](../../AGENTS.md)가 [워크플로우](../../ai/WORKFLOW.md)와 [라우팅](../../ai/routing.md)을 안내한다. 별도 Codex CLI·SDK·로그인이 필요하지 않다.

## 폴더

| 위치 | 내용 |
|---|---|
| ai/WORKFLOW.md, routing.md | 현재 Codex의 작업 절차와 역할 선택 |
| ai/roles/ | Review·QA·진단·기억 역할; 문서·구현·진행 관리는 WORKFLOW 공통 책임 |
| ai/harness/ | [로컬 결과·게이트·무효화·재개 명령](local-validation.md) |
| ai/local-state/ | Git 제외 선언·최소 실행 상태·근거; CI 전송 없음 |
| ai/templates/ | 통합 작업 기록, 실패·기억·PR 리뷰 글·대댓글 양식 |
| ai/memory/ | 확인된 지식과 인덱스 |
| ai/tests/ | 문서·기억·증거 검사와 그 회귀 테스트 |
| docs/harness/records/ | 실제 작업·검증·실패·리뷰 이력 |
| docs/harness/evidence/ | 검토 가능한 증거 |
| ai/local-evidence/ | Git 제외 임시 로그·증거 |

기본은 단일 Codex가 책임을 순차 수행한다. 독립 리뷰·다중 에이전트 실행은 실제 분담했을 때만 표시한다. 모델 버전만으로 역할·기억·테스트를 없애지 않으며 작업별 필요성으로 선택한다.

## PR 리뷰 양식 사용

이 지침이 포함된 저장소를 연 협업자·에이전트에는 `https://github.com/WookJaes/alf.io/pull/<번호>` 링크만 입력해도 된다. 루트 AGENTS.md가 리뷰 요청으로 해석해 [리뷰 역할](../../ai/roles/review.md)과 연결된 양식을 읽도록 안내한다. 기본은 최신 HEAD 리뷰 초안 작성이며, 같은 대화에서 해당 리뷰 게시를 이미 승인했다면 승인 범위에서 등록한다. 처음부터 게시까지 원하면 링크와 함께 “리뷰를 등록해줘”를 명시한다. 작업자 본인의 자체 리뷰에도 적용한다. 역할 문서가 [리뷰 글](../../ai/templates/pr-review.md)과 [대댓글](../../ai/templates/pr-review-reply.md) 양식을 연결한다. [PR #2](https://github.com/WookJaes/alf.io/pull/2)처럼 종합 의견은 차단·검토·참고와 검증 결과를 담고, 인라인 댓글은 재현·영향·수정 방향을 담는다. 대댓글은 작성자의 수정 보고와 리뷰어의 재확인을 구분한다. 이 파일들은 에이전트가 읽고 적용하는 문서이며 GitHub가 자동으로 불러오는 양식은 아니다.

리뷰와 대댓글은 사람이 이해할 수 있도록 문제·영향·수정 방향·확인 결과를 먼저 설명한다. `base/head`와 긴 해시 대신 “비교 기준”, “검토한 변경”과 커밋 링크를 쓰며, 명령·환경·실행 결과 등 재확인 정보는 상세 근거로 제공한다. 미검증 범위와 중요한 위험은 요약에서도 명시한다.

요청 예시:

```text
PR <URL>을 리뷰해줘. ai/roles/review.md와 연결된 PR 리뷰 양식을 읽고
최신 HEAD의 diff와 검증 근거를 확인해서 PR #2 형식으로 리뷰 종합 의견과
코드 위치별 댓글을 등록해줘. PR 설명 본문은 수정하지 마.
```

작성자의 답변·수정 이후에는 다음처럼 요청한다.

```text
PR <URL>의 리뷰 스레드 <URL>에 대한 작성자 답변과 최신 수정 커밋을 재확인해줘.
ai/roles/review.md와 ai/templates/pr-review-reply.md의 리뷰어 양식에 따라
해당 스레드에 대댓글을 등록해줘. PR 설명 본문은 수정하지 마.
```

작성자의 답변은 “PR <URL>의 스레드 <URL>에 수정 커밋과 실제 검증 결과를 작성자 양식으로 답변해줘”처럼 요청한다. 게시 없이 초안만 원하면 위 예시의 “등록해줘”를 “초안을 작성해줘”로 바꾼다. PR 승인·변경 요청·스레드 해결은 각각 요청한 범위에서 수행한다. 생성된 PR 설명 본문은 절대 수정하지 않는다. 본문 변경 요청에도 수정안을 초안이나 새 댓글로 제시하며 실제 본문은 편집하지 않는다.

## 검사

Python 표준 라이브러리만 사용한다. `ai/harness/run.py`는 명시한 로컬 명령을 실행하고 결과를 검사한다. `ai/tests/check.py`는 로컬 파일만 읽고 문서 링크·역할 파일·기억 근거·증거 체크섬을 확인한다. 모델을 호출하거나 에이전트 상태 전환을 강제하지 않는다.

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
