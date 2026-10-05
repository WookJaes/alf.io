# 하네스 GitHub Actions 자동 검사

- 작업 ID / 날짜 / 상태: ISSUE-3 / 2026-10-05 / incomplete (로컬 구현·검증 완료, 실제 Actions 검증 대기)
- 요청·완료 기준·수정 범위: [이슈 #3](https://github.com/WookJaes/alf.io/issues/3). 전용 워크플로우와 사용 안내 추가, 기존 제품 CI 보존.
- 실행 모드·실제 담당: 현재 Codex가 구현·검증·리뷰를 순차 수행.
- 브랜치·기준 HEAD·시작 시 기존 변경: `chore/3-harness-actions`, `origin/main`의 `3d7afee82`; 시작 브랜치 `chore/1-harness-setup`, 기존 변경 없음.
- 재현 환경·필수 도구 버전·명령 실행 기준 디렉터리: macOS / Python 3.9.6 / 저장소 루트. Actions 대상은 Python 3.13, ubuntu-latest·windows-latest.
- 검증 대상 코드(diff/해시)·증거 위치·검증 후 변경 여부: 기준 HEAD 대비 [신규 워크플로우](../../../.github/workflows/harness-check.yml)와 [사용 안내](../README.md)의 변경. 아래 검증 후 이 기록만 갱신했으며 최종 구조·회귀 검사를 재실행해 통과했다.
- 계획·현재 단계: 워크플로우·안내 작성, 로컬 구조·회귀·실패 전파 검증, 자체 리뷰 완료. 사용자가 커밋을 승인해 로컬 커밋 진행. 푸시·PR과 실제 Actions 검증은 후속 승인 범위에서 진행 필요.

## 변경과 검증

| 완료 기준 | 변경·확인 내용 | 명령·절차 | 종료 코드·실제 건수 | 증거·결과 |
|---|---|---|---|---|
| PR·main 푸시와 자체 변경 감지 | 두 이벤트에 동일한 경로 필터와 main 푸시 조건 추가 | Ruby YAML 파싱 후 이벤트·경로·OS 행렬·Windows 조건·실패 무시 설정 확인 | 0 / 정적 검사 1회 | 통과. YAML 1.1 파서의 `on` 키는 `true`로 읽히므로 이를 고려해 확인 |
| 전체 구조 검사 | 실제 저장소의 문서·링크·기억 검증 | `python3 -B ai/tests/check.py` | 0 / 검사 1회 | 로컬 통과 |
| 전체 회귀 검사 | CP949 모사 2개·실제 기억 양식 포함 | `python3 -B -m unittest discover -s ai/tests -p 'test_*.py' -v` | 0 / 테스트 14개 | `Ran 14 tests`, `OK` |
| UTF-8 모드 비활성 로컬 확인 | `sys.flags.utf8_mode == 0` 확인 후 구조·회귀 실행 | 위 두 명령에 `-X utf8=0` 추가 | 각각 0 / 구조 1회·테스트 14개 | macOS 기본 파일 인코딩 UTF-8, 모드 0. 실제 Windows 결과가 아님 |
| Linux·Windows 자동 실행 | Python 3.13, OS 행렬, Windows 추가 3단계 | 실제 Actions | 미실행 | 커밋·푸시·PR 후 확인 필요 |
| 오류의 종료 코드 전파 | 임시 복사본에 깨진 링크·실패 테스트 주입 | 아래 실패 검증 절차 | 각 1 / 링크 실패 2회·15개 중 테스트 실패 1개씩 2회 | 기대한 실패 확인. Actions 체크 실패는 원격 확인 필요 |
| 기존 제품 CI 보존 | 기존 워크플로우·제품 코드 변경 없음 | 전체 변경 파일과 diff 검토, `git diff --check` | 0 / diff 검사 1회 | 제품 CI 변경 없음 |

### 임시 실패 검증 절차

Python `TemporaryDirectory`에 `AGENTS.md`, `ai/`, `docs/harness/`, `docs/conventions/`, `.github/workflows/`를 복사하고 복사본 루트에서 실행했다. 기존 저장소에는 실패 데이터를 넣지 않았다.

1. 복사본 `AGENTS.md`에 `missing-issue-3-proof.md`를 대상으로 하는 Markdown 링크를 추가하고 `python3 -B ai/tests/check.py` 및 `python3 -B -X utf8=0 ai/tests/check.py`를 실행했다. 둘 다 누락 링크를 출력하고 종료 코드 1을 반환했다.
2. 링크를 원상 복구하고 `ai/tests/test_temporary_failure.py`에 `unittest.TestCase`의 테스트 하나를 추가해 `self.fail("issue-3 temporary failure propagation")`을 호출했다. 정상 모드와 `-X utf8=0`으로 전체 discover 명령을 실행했다. 각각 `Ran 15 tests`, `FAILED (failures=1)`, 종료 코드 1을 확인했다.
3. 임시 테스트를 제거하고 임시 디렉터리를 정리했다. 최종 저장소에는 임시 깨진 링크와 실패 테스트가 없다.

## 실패·리뷰·재개

- 실패·원인·조치·재검증: 최초 fetch는 `.git/FETCH_HEAD` 쓰기 권한으로 실패. 승인된 권한 확장으로 fetch와 브랜치 생성을 완료.
- 기록 작성 후 재검증에서 인라인 코드 안에 적은 임시 링크 예제를 검사기가 실제 링크로 인식해 구조 검사와 회귀 1개가 실패했다. 링크 예제를 일반 설명으로 수정하고 정상·UTF-8 비활성 검사를 재실행했다. 검사기 동작 변경은 범위 밖이므로 하지 않았다.
- 리뷰 방식·확인 범위·발견 사항: 자체 리뷰 완료. 경로 필터, 최소 읽기 권한, Python 버전 고정, 전체 테스트 discover, Windows 조건과 모드 0 확인, 단계별 종료 코드 전파를 확인했다. `fail-fast: false`로 한 OS 실패가 다른 OS 검증을 취소하지 않는다. 새 Python 패키지 의존성·제품 CI 수정 없음.
- 관련 기억·적용/제외 이유: 기존 CP949 모사·실제 기억 양식 회귀 테스트를 그대로 전체 실행.
- 생략 검증·이유·남은 위험: 실제 Linux·Windows Actions는 아직 미실행. 제품 코드 변경이 없어 제품 빌드·DB 검증은 대상 밖.
- 최종 확인 결과·다음 행동·커밋 여부: 로컬 검증 완료. 실제 Actions 실행·체크 실패 전파·PR 결과 기록은 미완료이므로 이슈 전체 완료로 표시하지 않는다. 사용자 승인에 따라 `chore: 하네스 GitHub Actions 검사 추가`로 로컬 커밋한다. 푸시는 아직 승인되지 않았으며, 후속 승인 후 PR에서 환경별 성공 결과와 임시 실패 실행 결과를 기록해야 한다.
