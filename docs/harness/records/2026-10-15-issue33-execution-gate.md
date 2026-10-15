# 이슈 #33 실행 전 게이트

- 작업 ID / 날짜 / 상태: issue33 / 2026-10-15 / completed (명시적으로 연결한 v1 CLI 범위)
- 요청: [이슈 #33](https://github.com/WookJaes/alf.io/issues/33)의 실행 전 범위·사용자 승인 확인. 사용자 지시로 구현·검증·커밋까지, 푸시·PR·외부 댓글·전역 권한 변경 제외.
- 선행 확인: #25·#26 closed/completed, PR #31·#32 merged. #32 최신 HEAD `1683bf3afe37e5c26629190afc65f36520754791`의 CI 9개 success·이미지 작업 2개 skipped 확인. 별도 Docker 부하 task 미검증과 과거 완료 게이트 docker-db 미실행은 남아 있었다. 처음에는 읽기 전용 조사만 수행했고 이후 사용자의 “그냥 진행해” 지시로 해당 한계를 #26 후속으로 두고 착수했다. #26 검증을 새로 수행하거나 통과로 바꾸지 않았다.
- 실제 개발 경로: 현재 macOS의 `Documents/GitHub/팀플/alf.io` checkout. 과거 Windows 개발 경로나 리뷰 임시 사본을 사용하지 않았다. 브랜치 `codex/33-execution-gate`, 기준 HEAD `d55564a18c66cc6dea4792f4f5359b98d62807b6`, 시작 변경 없음. GitHub compare에서 원격 main과 동일함 확인.
- 실행 모드: 현재 Codex가 구현·QA·Review 순차 수행. 별도 에이전트·별도 CLI 모델 실행 없음. 자체 검토를 독립 검토로 표시하지 않는다.
- 환경: Python3.9.6 / Git2.50.1 / Codex CLI0.144.6, hooks stable true. 데스크톱 앱 버전 미확인. 새 의존성·제품 코드·DB·API 변경 없음.
- 계획: 공식 연동 조사 → 판정·전달을 소유하는 중개 CLI → 신뢰 영역의 정책/승인 → 실제 파일/Git/합성 게시 회귀 → 현재 Codex 직접 호출 → 동일 합성 작업 비교 → 자체 Review·완료 게이트·커밋.
- 검증 실행 ID: issue33-final. 선언·수동 근거는 ignored `ai/local-state/issue33/`. 소스 해시·결과는 기존 run.py의 snapshot/영수증으로 연결한다.

## 구현과 근거

| 완료 기준 | 변경·확인 |
|---|---|
| 실행 전 전달 | `execution_gate.py`: 구조화 요청만 지원, deny/approval_required는 invoke 미호출 |
| 정책·승인 | `execution_authority.py`: 외부 정책 고정·서명, 별도 사용자 터미널 경로. 일반 요청에 승인 생성 도구 없음 |
| 경로 | 허용/보호 경로·정규화·symlink/hardlink·디렉터리 FD·원자 교체·staged 대상 확인 |
| 실제 대상 | 고정 cwd/origin/바이너리 해시, HEAD/index, push URL, 이슈 댓글 endpoint·전체 인자 검사 |
| 결과·재개 | 전달 전 durable intent·lock, ID별 한 번 전달, 승인 대기 재검사, success/failure/unknown 구분 |
| 기존 흐름 | run.py 변경 없음. 기존 run/gate/resume/reuse 회귀와 구조 검사를 수행할 예정 |
| 안내 | [사용 안내](../execution-gate.md), WORKFLOW·README 연결, 유효하지 않은 정책 예시 자리표시자 명시 |

## 현재 Codex 직접 호출 검증

단위 회귀와 별도로 이 대화의 현재 Codex가 `exec_command → execution_gate.py JSON stdin`을 직접 호출했다. 테스트 정책·키는 `/private/tmp/alfio-issue33-live/authority`에 합성 용도로만 만들었고 사용자 운영 승인이라고 표시하지 않는다. 개발 저장소 안 ignored `ai/local-state/issue33/live`의 합성 파일만 사용했다. gh는 외부 요청 대신 시작 표식만 만드는 합성 실행 파일로 고정했다.

- live-normal: file.write allow/succeeded·종료0. 합성 파일 before → after. 동일 ID/인자의 재호출은 replayed true, 중복 전달 없음.
- live-outside: scope deny/not_started·종료2. 범위 밖 sentinel은 untouched 유지.
- live-protected: AGENTS.md protected deny/not_started·종료2. 해시 `973693d338e5bfb6e04a6bd3ce23916e467c06e91424c2d06168499f7f38b982` 유지.
- live-publish: approval_required/not_started·종료3. 실제 GitHub 요청 없음.
- live-shell: unsupported deny/not_started·종료2. 복합 명령 문자열을 셸에 전달하지 않음.
- 별도 읽기 전용 확인: 실제 내용·보호 파일 해시·journal의 live-normal 1건만 존재·publisher-started 파일 부재를 assert하고 종료0.

위 검증은 연결한 중개 CLI 경로에서 실제 현재 에이전트의 요청을 통제한 결과다. 모든 기본 에이전트 도구에 전역 사전 훅을 설치했다는 뜻이 아니다. 기본 셸·에디터·MCP·Python 직접 실행·승인 도구 직접 조작은 여전히 우회 가능하다.

## 실패·검증·리뷰

- 첫 신규 회귀 30건 중 13건 오류: 동적 모듈 로딩 뒤 approve 내부 import가 누락되어 테스트가 실패했다. ident를 모듈 로딩 시 가져오도록 수정했다. 대상 프로세스가 시작됐다는 검증으로 계산하지 않았다.
- 다음 회귀 30건 중 1건 실패: 전달 후 journal 저장 실패를 실제 성공으로 반환할 수 있는 경로를 발견했다. unknown 및 자동 재시도 금지로 수정했고 다음 30건은 모두 통과했다. 이후 staged 링크·push 범위·한국어 판정 안내를 추가했으므로 최종 회귀는 별도로 수행한다.
- 최종 결과·합성 비교·자체 리뷰·완료 판정은 아래에 실제 실행 후 기록한다.
- 미검증: 실제 원격 push/GitHub 게시 성공, 사람의 실제 승인 UI 입력, 데스크톱 훅의 실제 활성화·전체 도구 가로채기, Windows 중개, worktree, 악의적인 같은 OS 계정/동시 파일 조작 격리. 제품 Java/DB/화면은 변경이 없어 재실행하지 않는다.


## 최종 검증과 자체 리뷰

- 최종 코드에서 `run issue33-final structure` 종료0, `regression` 종료0·실제105건·실패0·오류0·skipped0. 기존 결과 하네스70건과 신규 실행 중개35건을 포함한다. Windows에서는 신규 POSIX 중개35건을 명시적으로 skip하며 기존 회귀는 유지한다. Windows 원격 CI를 이번에 실행했다고 주장하지 않는다.
- 신규 회귀는 실제 임시 Git 커밋·파일 교체·합성 gh/푸시 프로세스, 정확한 argv/host/endpoint/body/cwd, 동일 ID 동시 호출의 한 번 전달, 프로세스 실패7·timeout unknown, 기록 실패 전/후 분리, 승인 누락·만료·범위 변경·위조, 정책·실행 파일 변경·누락, staged symlink와 일반 링크 우회, 재개 중 변경을 확인했다.
- 자체 리뷰에서 승인 전 Git 대상 검사에 `write-tree`를 쓰면 객체 생성 부작용이 생길 수 있음을 발견했다. 읽기 전용 `ls-files --stage -z` 결과 bytes의 SHA-256으로 바꾸고 거부된 요청에서 .git 파일 전체 내용이 불변인 회귀를 추가했다. fsmonitor·외부 diff도 검사 경로에서 비활성화했다. 위105건은 이 수정 뒤 결과다.
- 최종 코드의 Git probe 보완 뒤 현재 Codex가 live-final-normal과 live-final-publish를 직접 재검증했다. 정상 교체 allow/succeeded·종료0, 승인 누락 approval_required/not_started·종료3. 최종 읽기 전용 assertion 종료0: 정상 파일 final 내용, 범위 밖 sentinel/AGENTS 해시 불변, publisher-started 부재, journal에는 각기 다른 정상 요청2건만 존재. 앞서 같은 ID replay의 중복 미전달 근거는 유지한다.
- `comparison` 자동 검사 종료0. 별도 보고용 시간 측정1회를 ignored comparison-summary.json으로 보존했다. 초기 측정을 최종 코드 시간으로 재사용하지 않았다. 동일 합성7건(정상2·위반5)의 결과는 아래와 같다.

| 지표 | 적용 전 | 적용 후 |
|---|---:|---:|
| 위반 실행 | 5 | 0 |
| 정상 오차단 | 0 | 0 |
| 신규 승인 필요 응답/서로 다른 scope | 0 | 2 |
| 사전에 준비한 합성 승인 | 1 | 1 |
| 전체 요청 처리시간 ms | 415.054 | 456.776 |

- 약41.7ms 증가한 단일 로컬 표본이다. 실제 사람이 응답한 승인 횟수는 양쪽0이며, 승인 필요 응답2건과 혼동하지 않는다. 합성 승인1개는 시작 전에 준비했고 사람 대기·모델·실제 원격 지연은 제외했다. 처리량·장기 분포·보안 완전성을 이 수치로 주장하지 않는다.
- 자체 Review: 변경9개 파일 범위, 정책·승인 외부 경계, 일반 요청의 권한 확대 금지, argv/실제 대상·예외·미지원 차단, 요청별 전달·기록 실패, 민감값 미저장과 안내를 대조했다. PR 본문 편집 도구 없음. 완료 게이트가 외부 승인으로 변환되는 경로 없음. 미해결 차단 지적 없음. 실제 사용자 승인 UI·외부 게시·OS 격리는 위 미검증 범위로 유지한다.
- 필수 구조·회귀·비교와 직접 호출 QA·자체 Review를 기존 완료 게이트에 연결한다. 제품 공개 API·DB·의존성·기존 run.py는 변경하지 않았으며 제품 Java/DB/화면 검사를 불필요하게 반복하지 않았다.
- 최종 개발 커밋은 사용자가 명시적으로 승인한 직접 Git 경로에서 수행한다. 이 게이트 자체는 보호 파일이라 일반 작업 정책으로 자기 코드를 커밋하도록 권한을 넓히지 않는다. 푸시·PR·외부 댓글·전역 도구 권한 변경 없음.

- 최종 `gate issue33-final`·`resume issue33-final` 모두 종료0: structure/regression/comparison=passed(executed), QA/Review=passed(manual). 검증 뒤 변경은 코드 식별에서 제외되는 이 작업 기록 요약뿐이다. 커밋 뒤 HEAD 변경은 기존 완료 게이트의 보수적 재검증 조건임을 유지한다. 커밋 전105건과 완료 판정을 커밋 후 새 실행으로 표시하지 않는다.
