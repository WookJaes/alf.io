# 이슈 #56 macOS·Windows 개발·검증 안내

- 날짜 / 상태: 2026-10-20 / completed (작업 단위 3)
- [이슈 #56](https://github.com/WookJaes/alf.io/issues/56)의 Python 실행 안내와 공개 프론트 개발 문서만 수정했다. 현재 Codex의 순차 구현·자체 검증·리뷰이며 독립 에이전트 수행이 아니다.
- 기준 브랜치: `docs/56-non-deployment-fork`, 작업 단위 2 커밋 `215c03bcd` 이후. 사용자 변경 없음. 초기 상태·지침은 [포크 운영 안내 기록](2026-10-20-issue56-fork-guidance.md)에 있다.
- 확인 자료: `scripts/ci/test_workflows.py`의 PythonCITest·Bash 호출, `.github/workflows/ci-validation-check.yml`, `harness-check.yml`, `frontend/public/package.json`·`angular.json`, Gradle frontend task 정의, PR #55의 [재현 기록](https://github.com/WookJaes/alf.io/pull/55#discussion_r4321016028)과 [후속 논의](https://github.com/WookJaes/alf.io/pull/55#discussion_r4321166389).
- 변경: macOS 명령·Windows Git Bash 임시 python3 준비·실제 실행 파일 확인·세션 종료 정리 안내. 내부 Bash PATH 요구와 외부 python/py 명령 변경의 한계, CI와 로컬 실행 범위 차이를 명시했다. 공개 프론트 README의 원본 CI 배지·낡은 --prod 안내를 현행 task·빌드 명령으로 정리했다. 루트 README는 상세 검증 문서로 연결한다.
- 앞선 화면 검증 기록의 Angular CLI 버전을 실제 설치 조회값 **17.3.17**로 정정했다(앞선 기록의 17.0.9는 package.json 선언을 사용한 오기). 빌드를 다시 실행했다는 뜻은 아니다. pnpm 실행기가 이번 작업 중 생성한 미추적 store만 정리했으며 의존성·잠금 파일·사용자 데이터는 변경하지 않았다.

## 실제 실행·기존 근거

실행 기준은 저장소 루트, macOS / Python 3.9.6 / Git 2.50.1 / Bash 3.2.57이다. 외부 셸과 내부 Bash의 `sys.executable`은 `/Library/Developer/CommandLineTools/usr/bin/python3`, `command -v python3`는 `/usr/bin/python3`로 확인했다.

Windows 호스트가 없어 이번에는 **Windows에서 직접 실행하지 않았다**. PR #55의 Windows 10 / Python 3.13.15 / Git Bash 보고는 기존 근거로만 인용했다. 정상 python3 PATH 준비 후 scripts/ci 14건 성공은 당시 리뷰 보고이며 이번에 작성한 절대 경로 wrapper·전체 하네스·Windows UTF-8 비활성화 회귀를 새로 검증한 결과가 아니다. Windows에서 실제 실행 완료라고 표시하지 않는다.

## 검증 결과

검증 대상은 이 커밋의 diff, 로컬 선언은 `ai/local-state/issue56/plan3.json`, 실행 ID는 `issue56-3`이다. 구조·회귀·자체 리뷰를 완료하고 gate 종료 0을 확인했다. 독립 Review/QA 요구는 없으며 자체 검토했다. 원본 stdout/stderr·상태 JSON은 커밋하지 않는다.

제품 Java 빌드·DB·E2E·실제 앱 화면·Windows·Hugo 렌더는 이번 단위에서 실행하지 않는다. 링크·문서 변경이므로 Python 회귀와 소스 대조를 수행하며 양쪽 프론트 빌드 근거는 [화면 링크 기록](2026-10-20-issue56-visible-links.md)의 실제 실행 결과다. Docker·Gradle 배포 코드·CI·테스트 실행 코드는 그대로 유지한다. 원격 푸시·PR·이슈 변경·외부 댓글·배포는 하지 않는다.


| 실행 명령·절차 | 실제 결과 |
|---|---|
| `python3 -B ai/tests/check.py` | 종료 0, 구조 검사 통과 |
| `python3 -B -m unittest discover -s scripts/validation -p 'test_*.py'` | 종료 0, 실제 6건 통과, 실패·오류·스킵 0 |
| `python3 -B -m unittest discover -s scripts/ci -p 'test_*.py'` | 종료 0, 실제 14건 통과, 실패·오류·스킵 0 |
| `python3 -B -m unittest discover -s ai/tests -p 'test_*.py'` | 종료 0, 총 290건 중 실제 287건 통과, 실패·오류 0, Windows 전용 3건 스킵 |
| macOS·Windows 예시 코드 블록 `bash -n` | 각 종료 0, 구문 확인이며 Windows 실행 아님 |
| macOS의 공백 포함 절대 경로 합성 wrapper → 새 Bash → Python 실행 | 종료 0, 실행 파일·버전 확인 및 임시 wrapper 정리. Windows의 cygpath 단계는 실행하지 않음 |
| 상대 문서 링크 존재·현행 task·package 명령 대조 | 종료 0 / 자체 검토 완료 |
| `issue56-3` review begin → record → gate | gate 종료 0 |

하네스의 스킵 3건은 `test_context_select.py`의 Windows 파일명 별칭·정션 2건과 `test_session.py`의 Windows 정션 1건이다. 스킵을 실제 통과 건수에 포함하지 않았다. 기존 Windows 근거와 합성 경로 검사는 직접 Windows 검증을 대신하지 않는다.

회귀 6건·14건을 처음 실행한 뒤 이번 작업이 만든 미추적 pnpm store를 정리하자 코드 스냅샷이 달라져 하네스가 기존 결과를 무효로 판정했다. 정리 후 structure·해당 두 회귀를 다시 실행해 현재 대상의 영수증을 확보했다. 287건 하네스 회귀는 정리 후 실행한 결과다. 문서 내용·테스트 기대값·코드 로직을 바꿔 통과시킨 것은 아니다.

최종 diff·제외 범위를 자체 리뷰했고 요청 파일만 명시적으로 스테이징하여 staged diff·공백 오류를 확인한 뒤 로컬 커밋한다. 원본 링크 보존 기준은 앞선 두 기록에 있으며 이 작업에서도 기술 참고·정책·과거 이력은 일괄 치환하지 않았다.
