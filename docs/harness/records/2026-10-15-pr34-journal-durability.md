# PR #34 journal 디렉터리 동기화 보완

- 작업 ID / 날짜 / 상태: pr34-journal-durability / 2026-10-15 / completed (로컬 수정·검증, 원격 미반영)
- 요청: PR #34 최신 리뷰 확인·수정·검증 후 커밋까지만. 푸시·리뷰 답글·Resolve·PR 본문 수정·전역 설정 변경 제외.
- 시작 상태: 실제 개발 checkout의 `feat/33-execution-gate`, HEAD `ec1d40e2bd4028d85fe728ebca8459db636d7e25`, 미커밋 변경 없음. GitHub PR 최신 HEAD와 일치 확인.
- 대상: [journal 교체 뒤 부모 디렉터리 동기화 지적](https://github.com/WookJaes/alf.io/pull/34#discussion_r4280980564), P2 미해결1건. 리뷰 전체와 인라인 스레드를 확인했다.
- 담당: 현재 Codex 단독 수정·QA·자체 리뷰. 새 독립 리뷰를 수행했다고 표시하지 않는다.
- 범위: 실행 게이트 save·해당 회귀·사용 안내·이 기록. 기존 run.py·제품 API·DB·새 의존성 변경 없음.
- 원인: 임시 파일 fsync 후 replace만 하고 부모 디렉터리 항목은 동기화하지 않아, 파일시스템 복구 시 intent 교체의 영속성을 기대할 근거가 부족했다. [fsync(2)](https://www.man7.org/linux/man-pages/man2/fsync.2.html)의 파일/디렉터리 동기화 구분을 확인했다.
- 계획: save에 교체 후 부모 디렉터리 fsync·FD 정리 추가 → 실제 동기화 순서·전달 전 실패·결과 저장 실패의 회귀 → 구조/전체 하네스 회귀 → 자체 QA/Review·gate/resume → 단일 새 커밋.
- 로컬 실행 ID: pr34-journal-durability-final. 선언·수동 근거는 ignored `ai/local-state/pr34-journal-durability/`에 둔다. 필수 구조·회귀·자체 QA/Review, 독립 수행 필수 아님.
- 검증 한계: 실제 전원 차단·파일시스템 복구·하드웨어 캐시 영속성은 미검증. 실제 GitHub 게시 없이 합성 파일·프로세스만 사용한다. Windows 중개·원격 인증·사용자 승인 TTY 등 기존 미검증 범위는 유지한다.

## 변경과 검증

실제 수정·실행 결과·종료 코드·건수·실패가 있으면 아래에 기록한다. 앞선113건 통과나 기존 원격 CI를 이번 수정의 신규 검증으로 취급하지 않는다.


## 최종 실행 결과

- save는 임시 파일 데이터 fsync → os.replace → 부모 디렉터리 FD fsync 순서로 저장하고 마지막 단계까지 성공해야 반환한다. 부모 디렉터리 열기/동기화 오류를 무시하지 않으며 FD는 finally에서 닫는다. 기존 전달 전 예외 경로는 deny/not_started, 전달 후 결과 저장 예외는 unknown/재시도 금지로 유지했다.
- 표적 회귀: `python3 -B -m unittest discover -s ai/tests -p test_execution_gate.py`, 종료0, 실제46건·실패0·오류0·스킵0. 최종 코드의 한국어 주석·사용 안내 반영 뒤 전체 회귀도 별도로 수행했다.
- 전체 회귀: `run pr34-journal-durability-final regression`, 종료0, 실제116건·실패0·오류0·스킵0. 기존 결과 하네스70건과 실행 중개46건을 포함한다. 앞선113건과 구분한다.
- 구조 검사: `run pr34-journal-durability-final structure`, 종료0. 링크·지침 구조 정상. `git diff --check` 종료0.
- 새 회귀1: 실제 파일·디렉터리 FD와 os.replace/fsync를 관찰하고 실제 합성 게시 프로세스를 실행했다. fsync_file → replace → fsync_directory → invoke → fsync_file → replace → fsync_directory 순서 확인, 정상 JSON 저장·게시 표식1회.
- 새 회귀2: 디렉터리 fsync 오류를 주입했다. 정상 파일 교체 요청과 승인된 합성 게시 요청 모두 deny/not_started, invoke 미호출, 실제 대상 파일 불변·게시 표식 부재. 오류가 나도 디렉터리 FD가 닫힌 것을 확인했다.
- 새 회귀3: intent의 디렉터리 동기화는 성공시키고, 실제 합성 게시 후 결과 저장의 디렉터리 동기화만 실패시켰다. 최초 응답 unknown/delivery_uncertain_do_not_retry, 같은 ID의 다음 요청은 replayed이며 합성 프로세스 시작 표식은1회 유지.
- 위 테스트는 실제 디렉터리 fsync 호출과 오류 주입·현재 OS의 재전달 방지를 확인한다. 실제 전원 차단·크래시 복구 후 저장장치 상태·하드웨어 캐시 보장은 검증하지 않았다. 결과 JSON은 교체 후 현재 OS에서 보일 수 있어도 동기화 실패를 성공으로 보고하지 않는다.
- 자체 QA/Review: 리뷰 지적1건과 save·호출자의 예외 처리·3개 신규 회귀·사용 안내를 대조했다. 동기화 순서 보완과 실패 시 미전달/자동 재시도 금지가 충족됨을 확인했다. 새로운 독립 검토나 실제 외부 게시를 수행했다고 표시하지 않는다.
- 민감정보: 합성 데이터·표식·고정 오류 문구만 추가. 승인 키·토큰·원본 JSON/출력은 커밋 대상이 아니다. 제품 코드·DB·공개 API·의존성·기존 run.py 미변경.
- 커밋은 사용자 요청대로4개 파일의 새 커밋만 수행한다. PR 본문·외부 댓글·Resolve·푸시·전역 권한 변경 없음. PR 원격 HEAD 및 원격 CI는 아직 수정 전 커밋을 가리킨다.

- 최종 gate/resume 모두 종료0: structure/regression=passed(executed), QA/Review=passed(manual). 검증 후 추가한 변경은 snapshot 제외 작업 기록뿐이다. 커밋 뒤 HEAD 변경은 기존 게이트의 보수적 무효화 조건으로 유지한다.
