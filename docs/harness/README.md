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
python3 ai/tests/check.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s ai/tests -p 'test_*.py' -v
```

제품 변경은 작업에 맞는 테스트·화면 확인을 별도로 수행한다. 하네스 검사 성공은 제품 검증 성공을 의미하지 않는다. 작업 기록에서 실제 결과와 생략 범위를 구분한다.
