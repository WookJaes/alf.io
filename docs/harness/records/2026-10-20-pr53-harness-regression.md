# PR #53 문서 검색 회귀 테스트 보완

- 작업 ID / 날짜 / 상태: pr53-harness / 2026-10-20 / completed
- 요청: 실패한 CI를 수정하고 로컬 커밋까지 수행한다. 푸시·PR 본문 변경·댓글 게시 제외.
- 범위: `ai/tests/test_context_select.py`의 규칙 문서 우선순위 회귀와 이 기록. 제품 코드·검색 알고리즘·작업 기록 검색 설정은 변경하지 않는다.
- 실행: 현재 Codex가 구현·검증·QA·리뷰를 순차 자체 수행. 독립 수행 요구 없음.
- 초기 Git 상태: `fix/48-static-map-xss`, 추적·스테이징·미추적 변경 없음. 기존 PR 브랜치를 이어서 사용한다.
- 명령 기준: 저장소 루트. macOS, Python 3.9.6 및 3.14.6. 새 의존성 없음.
- 로컬 실행 ID: `pr53-harness-verify`. 선언은 Git 제외 상태인 `ai/local-state/pr53-harness-config/plan.json`에 저장한다. 원본 로그·환경 파일·검증 상태는 커밋하지 않는다.
- 관련 기억: `ai/memory/index.md`에 등록된 공통 기억 없음.

## 원인과 수정

실제 저장소 전체 문서를 대상으로 PR 본문 보존 규칙이 상위 3개에 포함되는지 검사하던 테스트가 실패했다. 작업 기록 추가로 검색 점수의 문서 빈도와 평균 길이가 달라져 다른 안내 문서가 세 번째로 선택됐다. 규칙 문서보다 기록이 먼저 선택된 실패는 아니었다.

임시 디렉터리에 PR 본문 보존 규칙, 리뷰·대댓글 양식 및 합성 작업 기록을 구성한다. 합성 기록의 검색 점수가 규칙보다 높다는 것도 확인한다. 선택 한도가 3개이면 규칙·양식이 선택되고 기록이 제외되며, 4개이면 기록이 실제로 마지막에 선택되는지 검사한다. 실제 저장소 문서 수나 순위에 대한 가정과 빈 기록 목록에 대한 무의미한 비교를 제거한다. 기존 규칙 우선 동작과 검사 강도는 유지한다.

## 변경과 검증

| 구분 | 검증 대상·명령 또는 절차 | 실제 결과 |
|---|---|---|
| 수정 전 | `PYTHONPATH=ai/tests python3 -B -m unittest test_context_select.ContextTests.test_repository_rule_document_outranks_records -v` | 1건 실행, 1건 실패. 예상 규칙이 상위 3개에서 누락됨 |
| 수정 후 집중 회귀 | `PYTHONPATH=ai/tests python3 -B -m unittest test_context_select.ContextTests.test_pr_rule_document_outranks_records -v` | 1건 실행, 실패·오류·스킵 0. 내부 한도 3·4 두 조건 확인 |
| 구조 검사 | `python3 -B ai/tests/check.py` | 종료 0, 구조 검사 통과 |
| 전체 회귀 | `python3 -B -m unittest discover -s ai/tests -p 'test_*.py' -v` (각 Python 실행 파일로 수행) | 각 버전 종료 0. 발견 290건 중 실제 287건 통과, 실패·오류 0, Windows 전용 3건 스킵. 합계 실제 574건이며 스킵 6건은 통과 수에서 제외 |
| 자체 QA | 기록 후순위 동작을 메모리에서 제거하면 새 테스트가 실패하는지 확인 | 집중 테스트 1건의 예상 실패 감지. 설정 복원 후 1건 통과. 저장소 설정 변경 없음 |
| 자체 리뷰·게이트 | diff·민감정보·검증 근거 확인 후 `python3 -B ai/harness/run.py gate pr53-harness-verify` | 종료 0. 구조·회귀 2개·자체 QA·자체 리뷰 총 5개 필수 항목 통과 |

## 한계와 완료 상태

- 로컬 macOS 검사이며 원격 CI의 Python 3.13·Ubuntu·Windows 결과를 대신하지 않는다. 원격 재검사는 이후 푸시 시 수행된다.
- 이번 변경은 Python 테스트와 작업 기록뿐이다. 지도 API의 Java·Chrome·외부 지도 API 검증을 새로 실행했다고 주장하지 않는다.
- 필수 로컬 게이트와 자체 리뷰를 완료했으며 이 기록과 회귀 테스트를 하나의 로컬 커밋으로 묶는다. 푸시는 수행하지 않는다. 커밋 대상 문서에는 로컬 절대 경로·커밋 식별자·실제 자격 증명·원본 오류 덤프를 기록하지 않는다.
