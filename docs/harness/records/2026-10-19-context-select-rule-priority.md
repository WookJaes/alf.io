# 문서 선택기 원 규칙 우선 순위

- 작업 ID / 날짜 / 상태: context-rule-priority / 2026-10-19 / completed. 사용자 승인으로 커밋·푸시·PR 진행.
- 요청·완료 기준·수정 범위: 이슈 #43 첫 버전([작업 기록](2026-10-19-issue43-context-selection.md))의 남은 위험인 "작업 기록이 원 규칙 문서보다 높은 순위". `select --goal "PR 리뷰 양식과 PR 본문 수정 금지 규칙"`에서 [리뷰 역할](../../../ai/roles/review.md)의 "PR 본문 보존" 구간이 선택돼야 한다. 필수 지침 보장·범위 검사·무효화 규칙은 유지한다. 수정 범위는 [선택기](../../../ai/harness/context_select.py), [설정](../../../ai/context-config.json), [사용 문서](../context-selection.md), 회귀 테스트. 제품 코드·공개 API·DB 변경 없음, 새 의존성 없음.
- 실행 모드·실제 담당: Claude Code가 설계·구현·검증, Codex(openai/codex-plugin-cc 1.0.6 adversarial-review)가 작업 트리 diff 독립 리뷰. 방식 선택은 사용자 확인을 받았다.
- 방식 선택: 설정 `history_paths`의 이력 문서를 원 규칙·안내 후보 뒤에 두는 묶음 정렬. 점수 배율(예: ×0.5)은 질의마다 역전 여부가 달라져 쓰지 않았다. 기본 검색 제외 방식은 과거 작업을 묻는 질의가 `--paths` 없이는 no_evidence가 되어 택하지 않았다.
- 브랜치·기준 HEAD·시작 시 기존 변경: `feat/context-select-rule-priority`, origin/main 2c2c307(PR #49 병합), 작업 트리 깨끗함.
- 재현 환경·명령 실행 기준 디렉터리: Windows 10 Pro 19045, Python 3.13(`%LOCALAPPDATA%\Programs\Python\Python313`), `PYTHONIOENCODING=utf-8`, 저장소 루트.
- 검증 대상 코드: 위 브랜치의 미커밋 변경. 검증 후 코드 변경 없음.

## 변경과 검증

| 완료 기준 | 변경·확인 내용 | 명령·절차 | 종료 코드·실제 건수 | 증거·결과 |
|---|---|---|---|---|
| 원 규칙이 이력보다 우선 | `history_paths`(기본 `docs/harness/records`) 묶음 정렬, 25% 기준은 원 문서 묶음 최고점·이력은 전체 최고점, 1차 이력은 원 문서가 못 덮은 질의어를 새로 덮을 때만 먼저, 나머지 이력은 2차 탐색 뒤 남은 자리만, 2차 이력 후보는 남은 질의어 일치분만, reason에 `이력 문서(후순위)`, VERSION 6 | 실제 저장소 `select --task smoke --goal "PR 리뷰 양식과 PR 본문 수정 금지 규칙" --no-text` | 0, ready | 변경 전: 검색 8구간 중 records 4개(1~3위), review.md L23-L28은 6위. 변경 후: records 0개, review.md L23-L28은 2위. `--max-sections 3`에서도 선택 |
| 이력만 일치하는 질의 | `--paths docs/harness/records`로 과거 기록 검색 | `select --goal "체크인 시간 재현 기록" --paths docs/harness/records --max-sections 3` | 0, ready | 2026-10-08-checkin-time-reproduction.md 1위 |
| 이전 설정 호환 | `history_paths`는 생략 가능(빈 목록), 잘못된 경로·타입 거부, 설정 해시 포함 | 회귀 | 통과 | 생략 시 기존 순위, 변경 시 stale |
| 회귀 테스트 | 합성 10건 + 실제 저장소 1건 + 잘못된 설정 2사례 | 변경 전 코드로 같은 테스트 실행 | 실패 3·오류 2(첫 커밋 기준) | 변경 전 동작을 실제로 잡아냄. 2차 탐색 테스트는 첫 커밋 4cf11ff에서 2건 실패 |
| 하네스 검사·전체 회귀 | — | `python -B ai/tests/check.py`, `python -B -m unittest discover -s ai/tests -p 'test_*.py'` | 0 / 0, 212건 OK(skip 46, Codex 지적 3건 반영 후) | skip은 기존 POSIX 전용·링크 권한 테스트 |
| UTF-8 모드 끔 | — | 위 두 명령에 `-X utf8=0` | 0 / 0, 212건 OK(skip 46) | — |
| 합성 평가 재실행 | — | `measure_context_select.py --sizes 100 1000 --seed 43 --out ai/local-state/context-eval-rule-priority4`(지적 3건 반영 후) | 0 | 아래 표 |

### 합성 선택기 평가(모델 호출 없음, 12작업)

| 문서 수 | 방식 | 평균 근거 재현율 | 반환 원문 문자 | 처리 시간 ms |
|---|---|---:|---:|---:|
| 100 | 기준 | 0.9091 | 14,941 | 280.475 |
| 100 | 선택기 | 0.8636 | 14,080 | 2,294.349 |
| 1,000 | 기준 | 0.9091 | 16,853 | 2,575.349 |
| 1,000 | 선택기 | 0.8182 | 7,606 | 21,819.202 |

필수 포함·상태 일치는 두 규모 모두 12/12다. 재현율·반환 문자는 #43 첫 버전과 같고 시간 차이는 실행 간 변동 범위다. 합성 코퍼스에는 `docs/harness/records` 문서가 없어 이번 순위 변경을 측정하지 않는다. 이 평가는 회귀가 없다는 확인일 뿐이다.

## 실패·리뷰·재개

- 실패·원인·조치·재검증: Codex 리뷰(medium) 지적을 메모리 합성 사례(`alpha beta`, 원 규칙 alpha 6.17·beta 1.30, 이력 0.50)로 재현했다. 첫 커밋에서는 1차 탐색의 이력 문서가 남은 자리를 차지해, 2차 탐색에서 찾은 beta 원 규칙이 `max_sections 2`에서는 insufficient_evidence, 예산 2,000자에서는 budget_exceeded로 빠졌다. 수정 전 main은 두 경우 모두 ready였다. 이력 채우기를 2차 탐색 뒤로 미루자 구간 수 경우는 ready가 됐지만, 예산 경우는 묶음별 25% 기준 때문에 후보가 된 이력 문서가 예산에 걸려 budget_exceeded가 남았다. 이력 문서에 전체 최고점 기준을 적용해 이력 후보가 수정 전보다 늘지 않게 했고, 두 경우 모두 ready로 돌아왔다. Codex 3차 리뷰(medium)는 이력을 미루면서 1차 이력이 덮던 질의어에도 2차 탐색이 생기는 문제를 지적했다(AGENTS.md 1,9xx자, 예산 2,000자, `alpha beta`). main·4cf11ff는 ready·followup_rounds 0, 수정분은 같은 구간을 고르고도 2차 탐색의 이력 other.md가 예산에 걸려 budget_exceeded·sections_scored 4→8이었다. 2차 탐색 여부를 들어갈 1차 이력까지 포함한 커버리지로 판단하고(상태 변경 없는 시험 선택), 2차 이력 후보는 그 뒤에도 남은 질의어와 일치하는 것만 쓰도록 고쳐 ready·0·4로 돌아왔다. Codex 4차 리뷰(medium, 브랜치 1cd18d7)는 그 시험 선택의 한계를 재현했다(`alpha beta gamma`, max_sections 3, 이력 beta 1건, gamma 원 문서 2건). main은 alpha·이력 beta·g1로 ready였지만, 1cd18d7은 시험 선택에서 이력이 beta를 덮는다고 보고 gamma만 2차 탐색한 뒤 g1·g2가 자리를 채워 이력이 빠지고 insufficient_evidence였다. 시험 선택을 없애고, 원 문서가 덮지 못한 질의어를 새로 덮는 1차 이력만 2차 탐색 전에 넣도록 바꿨다. 4개 재현 사례 모두 main과 상태·커버리지가 같고, 실제 저장소 질의의 records 0구간도 유지된다.
- 리뷰 방식·확인 범위·발견 사항: 자체 리뷰. 필수 구간·`--paths` 범위 검사·구버전·상충·verify 코드는 바꾸지 않았다. Codex 첫 리뷰(작업 트리 범위)는 Codex 쪽 PowerShell·`python` 실행 실패 뒤 13분간 출력 없이 멈춰 클라이언트를 종료했다. 두 번째 리뷰(`--scope branch --base origin/main`)는 needs-attention·지적 1건(medium, 위 2차 탐색 자리 소진)이었고 재현 후 반영했다. 반영분에 대한 세 번째 리뷰(작업 트리)도 needs-attention·지적 1건(medium, 불필요한 2차 이력 후보)이었고 재현 후 반영했다. 1cd18d7 푸시 후 네 번째 리뷰(브랜치)도 needs-attention·지적 1건(medium, 시험 선택 커버리지 무효화)이었고 재현 후 반영했다. Codex 샌드박스는 읽기 전용이라 임시 디렉터리를 만들지 못해 전체 unittest가 163건 오류로 끝났다(환경 제약, 이 세션에서는 통과). Codex 환경에서는 `python`이 Microsoft Store 실행 별칭으로 연결돼 전체 unittest를 실행하지 못했다.
- 생략 검증·이유·남은 위험: `run.py` 선언·gate는 사용하지 않고 명령을 직접 실행했다. Linux·macOS 실행은 CI에 맡긴다. 묶음 정렬이라 과거 작업을 찾는 질의도 기본 범위에서는 약하게 일치한 원 문서가 먼저 온다(예: "체크인 시간 재현 기록"은 기본 범위에서 records가 빠짐). 이때는 `--paths docs/harness/records`를 사용한다. 실제 저장소 회귀 테스트는 문서 내용이 바뀌면 함께 갱신해야 한다.
- 최종 확인 결과·다음 행동·커밋 여부: 완료 기준 충족. 사용자 승인으로 커밋·푸시·PR을 진행했다.
