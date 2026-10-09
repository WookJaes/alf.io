# 하네스 Gradle 실행 판정·다중 JUnit 집계 작업 기록

- 작업 ID / 날짜 / 상태: issue19 · 2026-10-10 · in_progress
- 요청·완료 기준·수정 범위: [이슈 #19](https://github.com/WookJaes/alf.io/issues/19). 대상 Gradle 작업 판정과 다중 XML 집계만 수정한다. 제품 코드·E2E·DB 환경·기존 실험·노션은 제외한다.
- 실행 모드·실제 담당: 현재 Codex 순차 구현·자체 Review·QA. 하위 에이전트 없음, 독립 수행 아님.
- 브랜치·기준 HEAD·시작 시 기존 변경: `fix/19-harness-gradle-junit`, `04d34e1de7788f394522761df59f2fdd5b191187`. 시작 main/작업 트리 깨끗함. fetch로 최신 origin/main과 일치 확인 후 분기.
- 재현 환경·필수 도구 버전·명령 실행 기준 디렉터리: macOS arm64, Python 3.9.6, Corretto Java 25.0.4.1, 저장소 루트. 기존 Gradle Wrapper 사용.
- 검증 대상 코드·증거 위치·검증 후 변경 여부: 아래 재현은 출발 HEAD의 `ai/harness/run.py`, 구현 변경 전이다. 실행 영수증·최소 요약·합성 자원은 Git 제외 `ai/local-state/issue19-*`에만 보관한다. 원본 로그·결과 JSON은 커밋하지 않는다.
- 계획·현재 단계: 재현 → 기록만 커밋 → 단일 보고서 Gradle 판정 수정·검증·커밋 → 다중 집계·실제 Gradle·최종 게이트·커밋.
- 로컬 실행 ID·선언 경로·필수 항목·Review/QA 독립 수행 요구: 재현 `issue19-before`, `ai/local-state/issue19-before/plan.json`, product 필수. 최종 선언은 구현 후 별도 ID 사용, Review/QA `independent: false`.
- gate/resume 판정 요약: 수정 전 product failed, 실제 종료 0인데 캐시 표시 때문에 거부. 최종 게이트는 아직 미실행.

## 수정 전 재현 (구현 수정 전에 기록)

| 완료 기준 | 변경·확인 내용 | 명령·절차 | 종료 코드·실제 건수 | 증거·결과 |
|---|---|---|---|---|
| 캐시 표시 재현 | 임시 Git 저장소에서 실제 unittest 1건; 출력만 합성 Gradle 형식 | `python3 -B ai/local-state/issue19-pre/reproduce.py` → `Run.execute/inspect` | 재현 스크립트 0; 각 자식 명령 0, 실행 1·실패/오류/스킵 0 | 표시 없음 passed. compileJava UP-TO-DATE, processTestResources NO-SOURCE, compileJava FROM-CACHE 각각 failed. 기대는 모두 passed |
| 실제 Gradle 재현 | TemplateResourceTest·ValidatorTest를 새 XML 경로로 실행; 합성 출력 아님 | `Run.execute('product')`, 명령은 아래 참조 | 실제 Gradle 0, XML 2개·전체 68건; 선택된 ValidatorTest만 66건 | 대상 :test 실행, 다른 작업 UP-TO-DATE 포함. 단일 보고서 66건 정상에도 하네스 failed·gate 거부 |
| 기존 보고서 확인 | 기존 `build/test-results/test/TEST-*.xml`, migrationValidation 확인; 새 테스트 아님 | 재현 스크립트의 XML 실제 testcase 집계 | test XML 152개, 실행 870·스킵 3·실패/오류 0; migrationValidation 1개·실행 1 | 과거 로컬 결과를 새 통과로 세지 않음 |
| 다중 연결 제한 | 합성 정상 XML 1건 + 실패 XML 1건을 명령에서 함께 생성 | report=good.xml로 실행 후 reports 배열만 선언하여 validate_plan | 명령 0, 하네스는 선택 파일 1건 passed; 배열 선언은 Invalid | 전체 연결 미지원. 단일 파일 선언 범위의 통과이며 전체 suite 성공을 증명하지 못함; 실패 파일 누락 시 전체 통과로 해석하면 잘못됨 |

실제 Gradle 재현 명령(하네스는 Python 전달 래퍼를 실행하며 래퍼는 아래 명령의 출력과 종료 코드를 전달):

```sh
./gradlew test --tests alfio.util.TemplateResourceTest --tests alfio.util.ValidatorTest --console=plain --no-build-cache -I ai/local-state/issue19-pre/reports.gradle -Dissue19.reports=<저장소>/ai/local-state/issue19-pre/xml
```

임시 init script는 :test의 JUnit 출력만 새 Git 제외 경로로 지정하고 HTML 출력을 끈다. 제품 빌드 설정은 수정하지 않았다. 원본 XML을 읽어 실제 건수를 대조했다. 두 클래스 중 TemplateResourceTest는 2건, ValidatorTest는 66건이다.

원인: `execute`는 출력 어디든 캐시 문자열이 있으면 `cached=True`로 처리한다. `validate_plan`은 문자열 report만 요구하고 `counts_junit`은 그 한 파일만 센다. 별도 테스트 작업 상태나 보고서 집합 근거가 없다.

## 실패·리뷰·재개

- 테스트 준비: 자원 디렉터리와 같은 ID로 init을 시도하여 기존 ID 보호에 거부됨(명령 1, 제품 테스트 미실행). 자원은 보존하고 새 ID `issue19-before`로 재실행하여 위 실제 결과 확보.
- 초기 네트워크/파일 권한: sandbox 내 gh 조회와 fetch 실패. 승인된 범위의 확장 실행으로 조회·fetch 성공. 작업 차단 없음.
- 생략 검증: 제품 화면 변경 없음으로 브라우저 제외. DB·E2E 변경 없음으로 추가 실행 제외.
- 커밋 계획: 사용자 지정 총 3개 커밋. 이 단계는 이 기록 파일만 커밋하며 구현은 아직 변경하지 않았다. 푸시·PR·외부 게시 없음.

## 2단계: Gradle 실행 판정 수정·재검증

- 변경: test 항목의 `gradle_tasks` 전체 경로 배열을 검증하고, 출력 줄을 스트리밍하여 대상별 상태를 보관한다. 다른 작업 상태는 제외한다. 대상 누락·중복·미실행·스킵·실패·알 수 없는 상태를 거부한다. 대상 상태는 영수증과 gate/resume에서도 확인한다. 종료 코드·새 단일 XML·실제 건수를 함께 요구한다. 일반 build/static 판정과 CLI 인자는 유지한다.
- 사용 안내: 대상 선언·plain 콘솔·거부 이유를 추가했다. 이 커밋에는 다중 보고서 집계를 포함하지 않았다.
- 검증 대상: 첫 커밋 `11fce8817` HEAD 위의 2단계 작업 트리. `issue19-single`에서 구조·회귀·product·자체 QA/Review 모두 유효, gate/resume 각 0.
- 명령: `python3 -B ai/local-state/issue19-pre/verify.py single issue19-single` (하네스 init/run 연결). 구조 검사 `python3 -B ai/tests/check.py` 0; 회귀 `python3 -B -m unittest discover -s ai/tests -p 'test_*.py'` 0, 54건·실패/오류/스킵 0.
- 실제 제품 명령: 재현과 같은 Gradle 명령에서 `--tests alfio.util.TemplateResourceTest`만 선택, 새 출력 `ai/local-state/issue19-single-xml`. 종료 0, :test EXECUTED, 단일 XML 1개·실행 2건·실패/오류/스킵 0. 하네스와 원본 testcase 대조 일치.
- QA/Review: begin 이후 XML·최소 결과·diff와 범위 대조, 현재 Codex 자체 수행으로 record. 독립 수행 아님. 대상 작업 캐시/스킵/상태 확인 불가 및 정상 단일 XML·기존 unittest/CLI 회귀 확인.
- 준비/도구 실패: py_compile은 호스트 Python의 외부 캐시 쓰기 권한 때문에 미실행; unittest의 모듈 import/실행으로 문법 확인. 테스트 삽입용 stdin 스크립트 인코딩 실패 후 UTF-8을 명시하여 수정·54건 재검증 완료. 제품·하네스 테스트 실패 없음.
- 커밋 경계: 2단계 gate는 `11fce8817`과 해당 미커밋 내용의 검증이다. 다음 커밋으로 HEAD가 바뀌면 이 gate를 새 HEAD의 통과로 주장하지 않는다. 3단계에서 새 실행으로 갱신한다.
