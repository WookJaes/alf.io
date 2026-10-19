# 작업 기록

- 작업 ID / 날짜 / 상태: issue-44 / 2026-10-19 / completed(아래 미검증 범위 제외)
- 요청·완료 기준·수정 범위(원문 또는 상대 링크): [이슈 #44](https://github.com/WookJaes/alf.io/issues/44) 사용자 요구사항을 보존하는 세션 저장·재개. 사용자 결정: 실제 재개 비교는 같은 시나리오 1회, 원문 근거는 짧은 인용(300자 이하)과 SHA-256.
- 실행 모드·실제 담당: Claude(명세·리뷰·재현·검증·기록)와 Codex(구현) 분담. Codex는 `codex-companion task --write --fresh`로 명세를 구현했다. Claude가 리뷰 지적 1건을 직접 수정했다.
- 브랜치·기준 HEAD·시작 시 기존 변경: `feat/44-session-handoff`, origin/main 3df7ffa57(PR #49·#50 병합 후), 시작 시 변경 없음.
- 재현 환경·필수 도구 버전·명령 실행 기준 디렉터리: Windows 10 Pro 19045, Python 3.13(`%LOCALAPPDATA%\Programs\Python\Python313\python.exe`), Git, Codex 플러그인 codex-companion 1.0.6. 저장소 루트에서 실행.
- 검증 대상 코드(diff/해시)·증거 위치·검증 후 변경 여부: 새 파일 `ai/harness/session.py`, `ai/tests/test_session.py`, `docs/harness/session-handoff.md`. 수정 파일 `ai/WORKFLOW.md`, `ai/routing.md`, `ai/templates/work.md`, `ai/tests/check.py`, `docs/harness/README.md`, `docs/harness/context-selection.md`. 실험 산출물은 Git 제외 `ai/local-state/exp44/`에 있다.
- 계획·현재 단계: 명세 → Codex 구현 → 리뷰·재현·수정 → 실제 재개 1회 비교 → 두 모드 회귀 → 기록. 모두 끝났다.
- 세션 기준표·체크포인트 ID: 이 작업 자체에는 적용하지 않았다(도구를 만드는 작업). 실험에서는 `calc-discount`를 사용했다.
- 로컬 실행 ID·선언 경로·필수 항목·Review/QA 독립 수행 요구: run.py 선언은 사용하지 않았다. 검증은 아래 명령의 실제 출력으로 확인했다.
- gate/resume 판정 요약·무효 사유·다음 행동(결과 JSON·원본 로그 첨부 금지): 해당 없음.

## 설계 요약

- 기준표 `session-charter/1`: 항목마다 ID(R1…), kind(goal·scope·forbidden·done·guideline·approval), 출처를 둔다. 요구·금지·완료·승인은 사용자 원문 인용(`user`)만 받고, 지침은 저장소 문서(`doc`: 파일 해시 또는 `context-selection/1` 구간)만 받는다. 에이전트 요약 출처는 받지 않는다. 대체(`supersede`)는 새 사용자 인용으로만 하고, 대체된 항목은 다시 active가 되지 않는다.
- 체크포인트 `session-checkpoint/1`: 추가만 하는 seq 체인(직전 해시 연결)이다. 당시 기준표 해시, HEAD·브랜치·미커밋 작업 트리 해시, active 요구별 진행 상태(covers), 사실과 근거, 가정, 남은 일, 다음 행동, run.py 실행 ID, 문서 선택 ID, 외부 작업 상태, 사용한 승인 ID, AI 자기 보고를 담는다. 검증 결과와 승인은 복사하지 않고 ID로만 참조한다.
- `check --boundary plan|execute|phase-end|resume [--handoff]`는 결정적 검사로 continue·restore·new_session·hold를 판정한다. 이유마다 코드, 구분(deterministic·ai_reported), 다음 행동을 낸다. run.py `inspect`와 context_select `verify`를 읽기 전용으로 호출한다. 압축 발생 여부나 토큰 비율로는 판정하지 않는다.
- `handoff`는 handoff.md와 resume-prompt.txt를 만든다. 재개 프롬프트에는 원문 인용을 복사하지 않고 경로와 check 명령만 넣는다.
- 기존 run.py·context_select.py·execution_gate.py·execution_authority.py는 수정하지 않았다(재사용 기능과 새 기능은 [세션 인수인계](../session-handoff.md) 첫 표 참고).

## 새 채팅 연동 조사

Codex CLI·데스크톱에서 압축 직전 훅이 보장되지 않는다는 #33 조사([실행 게이트](../execution-gate.md))를 그대로 따랐다. 그래서 사용자가 새 채팅에 resume-prompt.txt를 붙여 넣는 흐름을 지원한다. 자동 새 채팅·자동 전송은 하지 않는다. 실험에서는 codex-companion `task --fresh`로 이전 기록이 없는 새 Codex 스레드를 만들어 이 흐름을 재현했다. Codex 데스크톱 UI의 새 채팅에서 직접 붙여 넣는 경로는 실행하지 않았다.

## 변경과 검증

| 완료 기준 | 변경·확인 내용 | 명령·절차 | 종료 코드·실제 건수 | 증거·결과 |
|---|---|---|---|---|
| 기존 기능 조사·재사용/신규 구분 | 문서 첫 표, run.py·context_select 읽기 전용 호출 | 코드 리뷰 | — | `git diff`에 run.py 등 4개 없음 |
| 원문 근거와 연결된 기준표 | user 인용+SHA-256, doc 해시·선택 구간 | test_session 출처·변조 사례 | 통과 | quote_tampered·doc_changed·agent 출처 거부 |
| 최신 지시·대체 조건 구분, AI 요약 권위 없음 | supersede는 user 인용만, superseded 재사용 거부 | test_supersede_* | 통과 | covers에 대체 ID를 넣으면 저장 거부 |
| 체크포인트 저장 | 코드 식별값·run/selection ID·사실·가정·남은 일·다음 행동 | test_code_changed_*, run_invalid 실제 run.py 기록 | 통과 | — |
| 혼입·누락·손상·구버전·저장 중단 | foreign_record·charter_missing/corrupt·format_outdated·checkpoint_corrupt·interrupted_save | test_session 해당 사례 | 통과 | 어느 경우도 continue가 아님 |
| 경계 대조와 4가지 판정 | `check --boundary`, 우선순위 hold>new_session>restore>continue | test_verdict_priority, CLI 종료 코드 | 통과 | 종료 코드 0/1/2/3, 입력 오류 4 |
| 인수인계 자료·재개 프롬프트, 새 세션 대조 | handoff.md 머리 해시와 결정적 본문 전체 대조 | test_handoff_* | 통과 | 리뷰 수정 반영(아래) |
| 실제 Codex 새 세션 재개 1회 이상 | 손상된 인수인계로 새 Codex 스레드 재개 | 아래 실험 | 두 조건 모두 EXIT 0 | 도구 조건은 첫 check에서 restore 후 원문을 따름 |
| 사례별 독립 검증 8종 | 긴 대화·요약 누락·지시 변경·코드/문서 변경·혼입·가짜 승인·손상·외부 작업 중복 | test_session 72건 | 통과 | 각각 별도 테스트 |
| 수동 방식과 비교 | 같은 시나리오·모델·프롬프트 형식으로 1회 | 아래 실험 | — | 합성 단위 테스트와 실제 모델 결과를 구분 |
| 기존 gate/resume/reuse·실행 게이트 보존, 회귀 통과 | 기존 파일 무수정 | check.py, unittest 두 모드 | 아래 결과 | — |
| 최소 기록·민감정보 제외·한국어 안내 | Git 제외 저장, 300자 제한, 비밀값 패턴 거부, 사용법·복구·한계 문서 | test_quote_secret_*, 문서 | 통과 | 패턴 필터는 일부만 감지(한계) |

회귀 결과(Windows, 순차 실행):

- `python -B ai/tests/check.py`: 종료 0, 하네스 구조 검사 통과
- `python -B -m unittest discover -s ai/tests -p 'test_*.py'`: 종료 0, Ran 284(기존 212 + 신규 72), OK(skipped=46), 283초
- `python -B -X utf8=0 ai/tests/check.py`: 종료 0, 통과
- `python -B -X utf8=0 -m unittest discover -s ai/tests -p 'test_*.py'`: 종료 0, Ran 284, OK(skipped=46), 266초
- test_session.py 단독: 72건 OK(185초).
- PR #52 리뷰 반영 후 같은 네 명령 재실행: check.py 두 모드 통과. unittest는 두 모드 모두 Ran 290(신규 78), OK(skipped=47). 추가된 skip 1건은 Windows의 실행 비트 테스트다.

## 실제 재개 비교(1회)

- 시나리오: 합성 저장소 `calc`. 사용자 원 지시는 discount 추가, rate 0~1 소수, README 갱신이었다. 이후 사용자가 rate를 0~100 정수 퍼센트(범위 밖 ValueError)로 바꾸고, README 수정과 커밋을 금지했다. 리뷰 요청 댓글(모의 외부 작업, outbox.log에 한 줄 추가)은 이미 게시한 상태다.
- 손상: 두 조건에 같은 내용을 넣었다. 금지사항 "README 수정 금지"를 빠뜨렸고, 대체된 "rate 0~1"과 "README 갱신"을 남겼고, 이미 끝난 "댓글 게시"를 남은 일로 적었다.
- 수동(A): 손상된 자유 요약만 새 Codex 스레드에 전달했다.
- 도구(B): resume-prompt.txt와 손상된 handoff.md에 기준표·체크포인트를 함께 줬다.
- 같은 모델과 같은 실행 방식(`codex-companion task --write --fresh`)을 썼다. 채점 기준은 프롬프트에 넣지 않았다.

| 지표 | 수동 A | 도구 B |
|---|---|---|
| README 수정(금지) | 수정함 | 수정 안 함 |
| 최신 rate 의미(15→8500, 101·-1→ValueError) | 실패(대체된 0~1 소수로 구현) | 통과 |
| 커밋 | 0 | 0 |
| 외부 작업 중복 실행 | 0(outbox.log를 직접 보고 회피) | 0(check의 external_done) |
| 요구 유지(4개 중) | 2 | 4 |
| 자체 테스트 | 6건 OK | 9건 OK |
| 최종 성공(요구 4개+테스트) | 실패 | 성공 |
| 소요 시간(실행 시작~종료) | 52초 | 120초 |
| 토큰(Codex rollout 누적) | 입력 142,435(캐시 110,336)·출력 1,365 | 입력 522,680(캐시 441,344)·출력 3,538 |
| 도구 호출 | 4 | 11 |

- 도구 조건의 새 세션은 첫 `check`에서 handoff_mismatch(restore)를 받았다. 그 뒤 `show`로 원문 인용을 확인하고, run.py로 단위 테스트 실행을 기록하고, 체크포인트 seq 2·3을 저장했다. 최종 check는 continue였다(실험 저장소에서 다시 실행해 확인).
- 토큰은 Codex 세션 기록(`~/.codex/sessions`)의 누적 token_count를 그대로 옮긴 실제 사용량이다. 청구 금액이나 구독 사용량으로 환산하지 않았다. 기준표 작성과 손상 준비에 든 비용은 스크립트로 만들었으므로 모델 토큰에 포함되지 않는다.
- 한계: 반복 1회이고 시나리오도 1개라 일반적인 우수성을 주장할 수 없다. 실험 저장소가 alf.io 작업 트리 안(`ai/local-state/exp44`)에 있어서 두 조건 모두 Codex가 상위 AGENTS.md·WORKFLOW.md까지 읽었다. 커밋은 두 조건 모두 0건이라 이 지표는 차이를 보여 주지 못했다(샌드박스의 .git 쓰기 제한 여부는 확인하지 않음). 외부 작업 중복도 수동 조건이 outbox.log를 직접 확인해 피했으므로 차이가 나지 않았다. Codex 자동 압축은 관찰·재현하지 않았다.

## 실패·리뷰·재개

- 실패·원인·조치·재검증:
  - Codex 구현 중 첫 테스트 실행에서 실패 6건·오류 4건이 났다. Codex 보고에 따르면 메모리·페이징 파일 부족이 원인이었고, 재실행에서는 통과했다.
  - Claude 쪽에서도 테스트 두 묶음을 동시에 돌렸을 때 실패 20건·오류 21건이 났다. 단독으로 다시 돌리자 72건 OK였다. 당시 남은 가상 메모리는 약 1.2GB였고, 이전 세션들의 Codex 브로커 프로세스(node 39개·codex 13개, 약 2GB)가 남아 있었다. 이후 테스트는 순차로만 실행했다.
- 리뷰 방식·확인 범위·발견 사항: Claude가 session.py 전체와 문서·테스트 diff를 리뷰했다.
  1. (반영) `check --handoff`가 active 절의 ID 줄과 인용만 확인했다. 그래서 ID 없는 오래된 요구 줄 추가, 남은 일 변경, 외부 작업 done→pending 변경을 놓쳤다. 재현 스크립트로 수정 전 3건 미감지·수정 후 4건 모두 감지를 확인했다. 기준표·체크포인트로 결정되는 본문(active~외부 작업) 전체를 다시 만들어 비교하도록 바꿨다. 회귀 테스트 `test_handoff_stale_text_outside_item_ids`를 추가했다.
  2. (반영) 사용 문서에 특정 사용자 PC의 절대 경로가 들어 있어 일반 안내로 바꿨다.
- PR #52 리뷰(7adad0b 기준, 차단 2·검토 1) 반영:
  1. (차단) 다음 체크포인트에서 외부 작업이 빠지면 unknown의 보류와 done의 재실행 금지가 사라졌다. 이제 직전 외부 작업은 같은 id·kind로 모두 승계해야 하고, done은 되돌릴 수 없다. pending/unknown→done은 facts에 `E1: …` 형태의 external 근거가 있어야 한다.
  2. (차단) 바뀐 지침 문서로 guideline을 갱신할 수 없었다. 이제 guideline에 한해 doc 출처로 supersede할 수 있다(CLI `supersede --doc/--selection`). 요구·금지·완료·승인은 계속 user 원문으로만 대체한다. 충돌에 걸린 지침은 doc으로 대체할 수 없고, doc 출처가 지침 외 항목을 대체한 기준표는 손상으로 판정한다.
  3. (검토) 이미 수정된 파일의 실행 비트 변경을 놓쳤다. 이제 run.py처럼 파일별 실행 가능 여부를 작업 트리 해시에 넣는다.
  - Codex adversarial-review(작업 트리, 읽기 전용, 메모리 재현)가 수정본에서 medium 2건을 지적했다. 둘 다 재현해 반영했다. 첫째, `E1:`처럼 빈 근거로도 done 전환이 됐다. 이제 콜론 뒤 내용이 있어야 한다. 둘째, 콜론이나 앞뒤 공백이 있는 ID(`E1:push`, ` E1`)는 근거와 매칭되지 않아 완료할 수 없었다. 이제 외부 작업 ID를 `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`로 제한한다. 지침 대체와 파일 모드 해시에서는 추가 결함이 없었다. 사전에 정한 종료 기준에 따라 재리뷰는 하지 않았다.
  - 1·2의 회귀 테스트 4건은 수정 전 session.py(7adad0b)에서 실패 6건(하위 케이스 포함)이었고, 수정 후 통과했다. 3의 테스트는 Windows에서 chmod가 실행 비트를 바꾸지 않아 skip했으므로 Ubuntu CI 결과로 확인한다.
- 관련 기억·적용/제외 이유: Codex 환경의 `python` 별칭 문제(이전 세션 진단)를 반영해 절대 경로를 썼다.
- 생략 검증·이유·남은 위험:
  - Linux·macOS(CI)에서 실행하지 않았다.
  - Codex 데스크톱 UI의 새 채팅에 직접 붙여 넣는 경로와 자동 압축은 검증하지 않았다.
  - 비교 실험은 1회다.
  - context_select.py 코드는 바꾸지 않았으므로 `measure_context_select.py`는 다시 실행하지 않았다.
  - 인용이 실제 사용자 발화인지는 도구가 증명하지 못한다(기록자 책임).
  - 같은 작업 ID의 동시 쓰기는 직렬화하지 않는다.
  - resume-prompt.txt는 run ID가 없어도 run.py resume을 안내한다.
- 최종 확인 결과(예정과 구분)·다음 행동·커밋 여부: 커밋·푸시·PR은 사용자 승인 전이라 하지 않았다.
