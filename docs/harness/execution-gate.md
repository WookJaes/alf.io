# 작업 실행 전 게이트

명시적으로 연결한 중개 CLI에서 **실행 전에 판정하고 허용한 도구만 전달**한다. 기존 [결과 게이트](local-validation.md)의 `run/gate/resume/reuse`는 검사 실행·완료 판정을 계속 담당한다. 테스트·완료 게이트 통과는 커밋·푸시·게시 승인이 아니다.

## 연동 조사와 첫 버전

2026-10-15 Codex CLI `0.144.6`, `features list`의 `hooks stable true`, Python `3.9.6`, Git `2.50.1`을 확인했다. 실행 중인 데스크톱 앱의 버전은 미확인이고 CLI와 동일하다고 가정하지 않는다. 별도 Codex CLI 에이전트·SDK·로그인은 사용하지 않았다.

[공식 Hooks 문서](https://learn.chatgpt.com/docs/hooks)의 `PreToolUse`는 Bash/exec_command·apply_patch·MCP 호출을 거부할 수 있다. 하지만 훅 오류·시간 초과·잘못된 응답에는 호출이 계속될 수 있고 `permissionDecision: ask`는 미지원이다. 기존 셸의 `write_stdin` 입력은 다시 사전 훅을 거치지 않는다. 신뢰하지 않은 프로젝트 훅은 생략된다. 따라서 훅 설치만으로 오류 시 차단을 보장하지 않고 **판정과 실제 전달을 함께 소유하는 중개기**를 선택했다.

연결 경로는 `현재 Codex → exec_command → execution_gate.py JSON stdin → 판정 → 파일/고정 argv 도구`다. 새 에이전트를 만드는 실행기가 아니다. 수정·프로세스 요청을 이 CLI에 전달해야 통제된다. 새 의존성·제품 API·DB 변경은 없다.

| 도구 | 대상 검사와 지원 동작 | 승인 |
|---|---|---|
| `file.write` | 부모가 존재하는 UTF-8 일반 파일 생성·전체 교체. 상대 경로·기존 내용 해시·권한 확인 | 정책에서 추가 가능 |
| `git.commit` | 기존 스테이징 전체 경로·HEAD·전체 index 목록 해시 확인. add는 하지 않음 | 필수 |
| `git.push` | 저장소 규칙에 맞는 현재 작업 브랜치 HEAD를 origin의 같은 브랜치로 전달. 실제 push URL 전체가 승인 저장소 1개여야 함. force·추가 tag·서브모듈 push 없음 | 필수 |
| `github.issue_comment` | 고정 github.com의 선언한 저장소·이슈 번호에 새 댓글 POST. PR conversation 댓글도 같은 API | 필수 |
| 임의 셸·복합 명령·PR 본문 수정·다른 도구 | 미지원으로 거부. 임의 인자나 `approved` 필드 추가도 거부 | 승인으로 해제 불가 |

명령 문자열의 특정 단어 검색으로 판정하지 않는다. Git 검사·실제 커밋·푸시는 같은 안전 설정을 사용한다. hook·fsmonitor·커밋/푸시 서명·자동 maintenance/gc와 임의 credential helper·askpass를 비활성화한다. 사용자나 저장소의 Git 설정 파일 자체는 바꾸지 않는다. 서명 필수 저장소나 별도 hook·프로그램 기반 인증이 필요한 작업에는 이 어댑터를 그대로 적용하지 않는다. 필요한 인증이 없으면 푸시가 실패하며 임의 인증 프로그램을 대신 실행하지 않는다. gh 댓글 도구는 기존 gh 자격 설정을 사용하되 토큰·출력은 저장하지 않는다.

푸시는 Git이 제공한 `--exec-path`의 HTTPS helper 경로·내용 해시를 승인 범위에 추가한다. 실제 전달은 그 디렉터리로 `GIT_EXEC_PATH`를 고정하고 `GIT_ALLOW_PROTOCOL=https`로 제한한다. PATH·호출 환경으로 넣은 다른 helper는 선택하지 않는다. `remote.*.vcs`, URL 재작성, 개별 URL의 redirect 설정은 미지원으로 거부하며 HTTP redirect는 비활성화한다. helper가 바뀌면 기존 승인과 일치하지 않아 다시 확인해야 한다. 승인 범위가 보강됐으므로 이전 버전에서 발급한 푸시 승인을 그대로 재사용하지 않는다. 정책 JSON 형식은 바뀌지 않는다.

이 설정의 근거는 [Git 원격·프로그램 설정](https://git-scm.com/docs/git-config)과 [Git 환경 변수](https://git-scm.com/docs/git)에서 확인했다. Git 설치 자체·라이브러리·OS의 모든 하위 실행까지 격리하는 장치는 아니다. Git 실행 파일과 HTTPS helper를 신뢰한 환경을 전제로 한다.

## 정책과 사용자 승인

[예시 정책](../../ai/harness/execution-policy.example.json)을 복사해 작업 ID·기존 검증 run ID·실제 개발 저장소 절대 경로·저장소·허용/보호 경로·실행 파일과 SHA-256으로 채운다. 자리표시자 상태는 유효한 정책이 아니다. `.git` 디렉터리가 있는 일반 checkout과 POSIX만 지원한다. worktree·Windows 실행 중개는 실행 전에 거부한다. 기존 run.py의 Windows 지원은 유지한다.

**사람이 별도 터미널에서** 정책을 검토하고 `TRUST`를 입력한다. `AUTHORITY`는 아직 존재하지 않는 저장소 밖 디렉터리다. 에이전트가 작성한 선언은 그 자체로 승인되지 않는다.

```sh
python3 -B ai/harness/execution_authority.py --authority "$AUTHORITY" init /absolute/reviewed-policy.json
```

외부 디렉터리에 고정 정책 해시·서명 키·승인·전달 journal·최소 사건 기록을 둔다. 디렉터리는 0700, key는 0600이다. 일반 중개 요청은 저장소 밖을 수정하지 못한다. 기본 보호 경로는 `.git`, `.codex`, `.agents`, `.github`, `AGENTS.md`, `.gitignore`, `.gitattributes`, `.gitmodules`, `ai/harness`, `ai/WORKFLOW.md`, `docs/conventions/git.md`이며 정책에서 추가할 수 있다. 허용 경로와 겹쳐도 보호를 우선한다. 보호 파일·정책 유지보수는 별도 검토 경로에서 수행해야 한다.

승인할 요청 JSON을 사람이 확인한 뒤 아래 명령에서 `APPROVE`를 입력한다. 전체 인자는 화면에서만 검토하고 기록에는 해시만 둔다. `--yes`, stdin의 “승인됨”, 환경 변수로 승인하지 않는다.

```sh
python3 -B ai/harness/execution_authority.py --authority "$AUTHORITY" approve /absolute/request.json --reference user-approval-1
python3 -B ai/harness/execution_gate.py --authority "$AUTHORITY" < /absolute/request.json
```

승인은 HMAC 서명으로 검증하며 작업·실제 저장소·원격·동작·전체 인자·정책 해시에 묶인다. 커밋·푸시는 HEAD, 커밋은 전체 index 목록 해시도 포함한다. 문서·도구 출력·에이전트 자기 선언·테스트 통과를 승인으로 변환하지 않는다. 유효한 동일 범위는 1시간 동안 재사용하며 재질문하지 않는다. 대상·본문·작업·저장소·정책이 바뀌면 새 적합성 확인이 필요하다. 취소·만료·변조·누락은 실행하지 않는다.

**승인 경계의 한계:** 별도 사용자 터미널과 키는 중개 프로토콜이 에이전트에게 제공하지 않는 신뢰 영역이다. TTY 자체를 사람이 입력했다는 증명으로 보지 않는다. 같은 OS 계정의 직접 셸·Python import·UI 자동화로 승인 도구나 키를 조작하는 경로는 격리하지 않는다. 그 경로까지 허용한 악의적인 에이전트의 자기 승인을 완전히 막는다고 주장하지 않는다. 운영 연결 시 승인 도구·키 접근은 사람의 영역에 두어야 한다. 이번 합성 서명은 실제 사용자 승인으로 재사용하지 않는다.

## 요청과 재개

모든 요청은 다음 다섯 필드만 받는다. cwd는 정책의 실제 저장소 루트와 정확히 같아야 한다.

```json
{
  "id": "write-1",
  "task_id": "issue-33-example",
  "tool": "file.write",
  "cwd": "/absolute/development/repository",
  "args": {"path": "src/example.txt", "content": "합성 내용", "expected_sha256": null}
}
```

기존 파일은 현재 SHA-256을 선언하고 새 파일만 null을 사용한다. 기존 권한은 보존하고 새 파일은 0600이다. 삭제·디렉터리 생성·부분 patch·바이너리는 미지원이다. 절대/Windows 경로·`..`·역슬래시·비정규 경로를 거부한다. 보호 경로는 대소문자·Unicode 정규화 별칭도 보수적으로 검사한다. 부모/대상 symlink·대상 hardlink·특수 파일·staged 링크/서브모듈·충돌은 거부한다. POSIX 디렉터리 FD와 `O_NOFOLLOW`로 열고 같은 FD에서 원자 교체한다.

다른 도구의 args는 다음과 같다. 누락·추가 필드를 거부한다.

```json
{"paths":["src/example.txt"], "message":"fix: 합성 수정", "head":"현재 HEAD", "index_sha256":"현재 git ls-files --stage -z 출력 bytes의 SHA-256"}
```

```json
{"branch":"feat/33-execution-gate", "head":"현재 HEAD"}
```

```json
{"issue":123, "body":"사용자가 검토한 댓글"}
```

응답은 decision(allow/deny/approval_required), rule·한국어 reason, status·실제 exit_code로 구분한다. allow가 성공을 뜻하지 않는다. 종료 코드 0은 succeeded, 3은 승인 필요, 2는 거부·실패·불명확 결과다. 프로세스 종료 확인 시 성공/실패를 구분하고 timeout·전달 후 기록 실패는 unknown으로 남긴다.

브랜치는 `feat|fix|docs|test|refactor|chore` 타입과 소문자 kebab-case 요약을 사용하며, 이슈 번호가 있으면 앞자리 0 없는 양수로 적는다. 예: `feat/33-execution-gate`, `docs/git-conventions`. main·codex 접두사·중첩 경로·잘못된 이슈 번호는 이 저장소의 중개 푸시 범위에서 거부한다.

승인 필요 시 실행하지 않고 scope 해시를 반환한다. 승인 후 **같은 JSON을 재전달**하면 정책·서명·파일·HEAD/index·원격·실행 파일을 다시 확인한다. 승인 UI 대기 중 대상이 변해도 서명하지 않는다. 판정 오류·잘못된 선언·연동 실패에는 대상 도구를 전달하지 않는다.

요청 ID는 한 작업에서 유일하게 사용한다. 전달 전에 intent를 디스크에 기록하고 작업별 lock으로 직렬화한다. 저장 순서는 임시 파일 fsync → 원자 교체 → 부모 디렉터리 fsync이며, 모두 성공한 뒤에만 도구를 전달한다. 디렉터리 동기화가 실패하면 실행 전에는 미전달, 실행 결과 저장 단계에서는 unknown·자동 재시도 금지로 처리한다. 디렉터리 동기화를 지원하지 않는 파일시스템에서도 실패를 무시하지 않는다. 성공·실패·unknown은 같은 ID로 재실행하지 않고 `replayed: true`로 과거 결과만 반환한다. 같은 ID에 다른 인자는 거부한다. 승인 대기는 미전달이므로 재판정 가능하다. 전원 손실·중단으로 intent만 남은 unknown은 실제 대상을 확인한 뒤 새 작업 여부를 결정한다. 자동 재시도하지 않는다. 실제 전원 차단·파일시스템 복구·장치 캐시 영속성을 검증한 것은 아니며, 저장장치와 파일시스템의 동기화 보장을 전제로 한다. 사람이 파일·index·정책을 동시에 조작하는 모든 경쟁 상황을 격리하는 장치는 아니다.

사건 기록에는 run/task ID, 요청·정책 해시, 도구, 규칙·이유, 승인 참조, 결과·종료 코드·시각·처리시간만 남긴다. 파일 내용·전체 명령·본문·stdout/stderr는 저장하지 않는다. 정책·키·승인·journal·원본 JSON을 커밋하지 않는다. 초기 설정이 깨져 기록도 열 수 없으면 integration 거부 응답만 반환한다. 전달 후 사건 저장 실패는 `audit: unavailable`로 표시하며 실제 전달 결과를 미실행으로 바꾸지 않는다.

## 확인 범위와 남은 경로

현재 Codex가 CLI로 직접 전달한 합성 수정·거부·승인 필요 요청에서 파일 불변·게시 프로세스 미시작을 확인했다. 단위 판정이나 사후 diff만으로 차단을 증명했다고 표시하지 않는다. 회귀의 실제 Git·합성 프로세스와 에이전트 직접 호출은 [작업 기록](records/2026-10-15-issue33-execution-gate.md)에서 구분한다.

연결하지 않은 기본 exec_command·apply_patch·에디터·MCP·기존 셸의 stdin·Python 직접 실행·다른 바이너리·외부 API는 우회 경로다. 이번 구현 파일 작성·최종 개발 커밋 전체가 게이트로 통제됐다고 주장하지 않는다. 전역 설정·권한·프로젝트 훅을 자동 변경하지 않았다. 완전한 샌드박스·자율 개발 에이전트·제품 권한 대체는 제외한다.

```sh
python3 -B ai/tests/check.py
python3 -B -m unittest discover -s ai/tests -p 'test_*.py'
python3 -B ai/tests/measure_execution_gate.py
```

합성 비교는 같은 7개 작업의 위반 실행·정상 오차단·승인 필요 scope 수·처리시간을 비교한다. 테스트용 합성 승인 1개를 양쪽에 동일하게 준비하고 사람 응답 시간·모델/원격 지연은 제외한다. 외부 게시에는 합성 실행 파일만 사용한다. 실제 푸시·GitHub 쓰기의 성공 검증과 실제 사람이 승인 UI를 사용하는 검증은 미수행이다.
