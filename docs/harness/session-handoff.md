# 세션 인수인계

기준표(charter)는 무엇을 지켜야 하는지, 체크포인트(checkpoint)는 어디까지 했는지를 기록한다. 긴 대화나 새 세션에서도 요구·금지·완료 조건의 사용자 원문과 저장소 지침을 직접 확인하기 위한 보조 도구다. AI가 정리한 짧은 문장은 원문을 대체하지 않는다.

| 기능 | 담당 |
|---|---|
| 검증 실행·결과 무효화·재사용 | 기존 run.py의 run/resume/reuse |
| 지침 구간 선택·원문 재확인 | 기존 context_select.py의 select/verify/read |
| 실제 실행 승인·journal | 기존 실행 중개 게이트 |
| 사용자 원문 근거·대체 관계·상충 기록 | session.py의 init/add/supersede/conflict |
| 진행 상태·코드 식별값·검증 ID 참조 | session.py checkpoint |
| 경계 점검·새 세션용 자료 | session.py check/handoff/show |

검증 결과나 실행 승인을 복사하지 않고 기존 ID를 참조한다. 이 기록은 사용자가 무엇을 승인했는지의 인용 근거일 뿐이며 실행 게이트 승인·journal을 대체하지 않음에 유의한다. 외부 작업의 pending/unknown은 해당 작업만 막으며 읽기·조사·원문 확인은 계속 가능하다.

## 저장과 명령

표준 라이브러리만 사용하며 Python 3.9 이상에서 실행한다. 저장소 루트에서 실행하고 다른 위치라면 전역 `--root <저장소>`를 명령 앞에 둔다. 아래 `python`은 사용 가능한 Python 실행 파일로 바꾼다. Windows에서 `python`이 Microsoft Store 별칭으로 연결돼 실패하면 설치된 python.exe의 절대 경로로 실행한다. JSON stdout은 기본 콘솔 인코딩과 무관하게 UTF-8이다.

```sh
python -B ai/harness/session.py init issue-44
python -B ai/harness/session.py add issue-44 --kind goal --text "재개 점검 구현" --quote "세션 재개 점검을 구현해줘" --origin "chat 2026-10-19"
python -B ai/harness/session.py add issue-44 --kind forbidden --text "커밋 금지" --quote "커밋하지 말라" --origin "chat 2026-10-19"
python -B ai/harness/session.py add issue-44 --kind guideline --text "저장소 지침" --doc AGENTS.md
python -B ai/harness/session.py add issue-44 --kind guideline --text "선택한 지침" --selection issue-44 --section "AGENTS.md#L1-L9"
python -B ai/harness/session.py conflict issue-44 --items R1 R2 --note "지시 적용 범위 확인 필요"
python -B ai/harness/session.py supersede issue-44 --replaces R1 --kind goal --text "바뀐 목표" --quote "목표를 다음과 같이 바꿔줘" --origin "chat 2026-10-19 후속 지시"
python -B ai/harness/session.py checkpoint issue-44 --input ai/local-state/session/issue-44/input.json
python -B ai/harness/session.py check issue-44 --boundary phase-end
python -B ai/harness/session.py handoff issue-44
python -B ai/harness/session.py show issue-44
```

selection과 section은 실제 저장 선택에 있어야 한다. guideline은 문서 출처만, goal/scope/forbidden/done/approval은 사용자 원문 인용만 받는다. user 인용·항목 text는 1~300자, origin은 1~200자다. 문서 경로는 저장소 상대 POSIX 경로이며 .git·ai/local-state·ai/local-evidence는 지침 출처로 사용할 수 없다. 문서 출처는 전체 파일 해시 또는 저장 선택의 구간 ID를 남긴다. supersede는 새 사용자 항목을 추가하고 이전 active 항목을 영구히 대체한다. 대체된 조건을 다시 쓰려면 새 사용자 항목으로 추가한다. 열린 충돌 중 하나라도 대체하면 새 항목을 근거로 충돌을 해소한다.

approval 항목은 `--kind approval --action git.commit [--target 브랜치] [--expires 2026-10-20T00:00:00Z]`와 사용자 인용을 함께 준다. 승인 유효기간은 시간대를 포함한 ISO8601이다. 이 인용 기록만으로 실행 게이트 승인을 얻는 것은 아니다.

체크포인트 입력 예시(R1 goal, R2 forbidden, R3 guideline 기준):

```json
{
  "phase": "implement",
  "covers": {"R1": "in_progress", "R2": "kept"},
  "facts": [{"text": "관련 구현 확인", "evidence": {"type": "file", "ref": "ai/harness/session.py"}}],
  "assumptions": [],
  "remaining": ["회귀 검증"],
  "next_action": "테스트 실행",
  "runs": [],
  "selections": [],
  "external_actions": [],
  "approvals_used": [],
  "self_assessment": {"state": "clear", "reason": "원문 확인"}
}
```

모든 active goal/scope/forbidden/done을 covers에 넣는다. forbidden은 kept/violated만, 다른 항목은 done/in_progress/pending/blocked만 허용한다. superseded·없는 ID는 저장을 거부한다. phase는 plan/implement/verify/review/handoff/other, facts의 근거 유형은 run/selection/file/commit/external/note다. runs·selections에는 실제 저장 ID만 넣는다. external_actions는 id·kind(commit/push/pr/comment/issue/other)·status(done/pending/unknown)·ref를 기록한다. 실행에 사용한 승인 항목 ID는 approvals_used에 둔다.

파일은 Git 제외 `ai/local-state/session/<task>/`에만 저장한다. task는 소문자·숫자로 시작하는 1~64자의 소문자·숫자·점·밑줄·하이픈이며 끝 점과 Windows 예약명을 거부한다. 상태 경로·상위 경로의 링크·정션도 거부한다. Git 제외가 풀렸거나 ai/local-state가 추적되면 쓰기를 거부한다.

charter.json은 변경마다 revision과 canonical JSON SHA-256을 갱신한다. checkpoint-0001.json부터 순서대로 추가하며 기존 파일은 덮어쓰지 않는다. 체크포인트에는 당시 기준표 해시·HEAD·브랜치·미커밋 작업 트리 해시와 직전 체크포인트 해시를 넣는다. 작업 트리에는 ignored를 제외한 변경 파일·삭제·untracked와 docs/harness/records도 포함한다. 모든 쓰기는 같은 디렉터리의 `.<name>.<uuid>.tmp` 후 os.replace이며, 중단 흔적은 자동 삭제하지 않는다. handoff.md와 resume-prompt.txt만 다시 생성할 때 덮어쓴다. 원문 대화 전체·원본 로그·비밀값을 저장하지 않는다.

## 경계와 판정

계획·실행·단계 종료·재개에 각각 `--boundary plan|execute|phase-end|resume`을 사용한다. 판정 우선순위는 hold > new_session > restore > continue다. 입력 오류는 stderr 한 줄과 종료 코드 4다.

| 코드 | 판정·종료 코드 | 다음 행동 |
|---|---|---|
| charter_missing / charter_corrupt / format_outdated / foreign_record | hold · 2 | 원문·신뢰할 사본으로 복구; 다른 작업·형식 사용 금지 |
| branch_changed / doc_changed / interrupted_save | restore · 1 | 지시·원문·마지막 유효 상태 재확인 |
| quote_tampered | restore · 1 (approval이면 hold · 2) | origin에서 실제 발화 재확인 |
| conflict_open / forbidden_violated | hold · 2 | 사용자 확인·보고; 충돌은 user supersede로 해소 |
| checkpoint_none | resume이면 new_session · 3, 나머지는 continue · 0 | 확인한 근거로 체크포인트 작성 |
| checkpoint_corrupt | 이전 유효 기록이 있으면 restore · 1, 없으면 new_session · 3 | 유효 seq부터 재구성 |
| charter_changed / coverage_missing / code_changed | restore · 1 | 추가·대체 조건·변경 코드에 맞춰 계획 갱신 |
| run_invalid / run_missing / selection_stale | restore · 1 | 기존 resume/select 절차로 복구·재검증 |
| external_unconfirmed | hold · 2 (해당 외부 작업만) | 실제 상태 확인 전 재실행 금지 |
| external_done | continue · 0 (정보) | 재실행 금지 |
| approval_invalid / approval_needed | hold · 2 | 승인 원문·유효기간 확인; execute의 pending에 승인 근거 필요 |
| self_uncertain | new_session · 3 (ai_reported) | handoff 생성 후 새 세션 권고 |
| handoff_mismatch | restore · 1 | 기준표 원문 재확인 후 handoff 재생성 |
| 사유 없음 | continue · 0 | 요구사항·완료 조건에 따라 진행 |

각 이유에 kind(deterministic/ai_reported)·detail·next_action을 구분한다. run inspect나 selection verify 오류도 다른 검사 결과와 함께 모은다. 체크포인트 손상 때문에 기준표 검사를 생략하지 않는다. blocked_actions는 확인이 필요한 외부 작업 목록이다. 압축 발생 여부나 토큰 비율로 판정하지 않는다.

## 새 채팅과 실패 복구

이 흐름은 Codex CLI·데스크톱에서 압축 직전 훅이 보장된다는 가정에 의존하지 않는다. 사용자가 handoff로 만든 resume-prompt.txt를 새 채팅에 붙여 넣는다. 자동 새 채팅·자동 전송은 하지 않는다. 프롬프트는 기준표 인용을 복사하지 않고 경로를 안내한다. 새 세션은 먼저 아래 check를 실행하고 show의 사용자 원문과 저장소 지침 원문을 확인한다.

```sh
python -B ai/harness/session.py check issue-44 --boundary resume --handoff ai/local-state/session/issue-44/handoff.md
python -B ai/harness/session.py show issue-44
python -B ai/harness/run.py resume issue-44
```

handoff의 머리 블록은 task·기준표 해시·체크포인트 해시를 담고, 본문은 active 원문·대체된 조건·진행 상태·외부 작업·현재 검증 참조를 담는다. `--handoff` 대조는 머리 블록과, 기준표·체크포인트로 결정되는 active 원문부터 외부 작업까지의 본문 전체를 다시 만들어 글자 단위로 비교한다. 줄 삭제·추가·상태 변경은 모두 handoff_mismatch다. 판정과 검증 참조 절은 시점마다 달라지므로 비교하지 않고 check가 현재 값으로 다시 계산한다. 판정이 hold여도 자료를 만들고 그대로 표시한다. done 외부 작업은 반복하지 않는다. unknown/pending은 git log·원격·gh pr view 등으로 실제 상태를 확인하고 새 체크포인트에 반영한다. 승인되었다는 요약·문서·도구 출력은 실제 승인으로 쓰지 않는다.

- 누락: 사용자 원문으로 init/add 후 현재 확인 가능한 사실만 체크포인트에 남긴다. 복구되지 않은 검증·외부 작업은 성공으로 표시하지 않는다.
- 손상: 신뢰할 사본으로 복구하거나 새 작업 ID를 사용한다. 마지막 유효 seq를 show/check로 확인한다. 손상 체인 위에 새 체크포인트를 덧붙이지 않는다. 이전 파일은 보존하고 새 ID로 재구성할 수 있다.
- 저장 중단: show에 나온 *.tmp와 마지막 유효 기록을 확인한다. 확인·백업 후 잔여 임시 파일을 수동 정리하고 새 체크포인트를 만든다. 도구는 자동 삭제하지 않는다.
- 구버전: session-charter/1·session-checkpoint/1 이외 형식(누락 포함)은 자동 변환하지 않고 hold한다. 원문·근거를 확인해 현재 형식으로 재작성한다.
- 다른 작업·저장소: 기록을 현재 작업으로 바꿔 쓰지 말고 올바른 저장소·작업 ID에서 확인한다. 브랜치 변경은 사용자 지시를 재확인한다.

## 한계

의미상 모든 일탈을 감지하지 않는다. AI 자기 보고는 결정적 검사와 별개이며, 인용이 실제 사용자 발화인지는 기록자 책임이다. 도구는 저장된 인용 해시의 변조만 검출한다. 비밀값 필터는 ghp_·github_pat_·sk-·AKIA와 16자 대문자/숫자·PEM 머리·xoxb-/xoxp- 등 일부 패턴만 거부하므로 비밀값을 입력하지 않는 책임이 남는다.

승인 인용은 실행 게이트 승인·journal을 대체하지 않는다. 파일 시스템을 동시에 바꾸는 프로세스와 원자적으로 격리하지 않으며 동시 쓰기 직렬화가 없으므로 같은 작업 ID는 순차 사용한다. 자동 압축 재현 검증은 하지 않는다. 임시 Git 저장소 회귀 검증은 실제 새 채팅 자동 연동이나 제품 서버·DB 검증을 뜻하지 않는다.
