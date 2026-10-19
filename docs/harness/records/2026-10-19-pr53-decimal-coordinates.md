# PR #53 리뷰 반영: 작은 좌표의 소수 표기 유지

- 날짜·상태: 2026-10-19~2026-10-20, completed.
- 요청: PR #53의 새 리뷰를 확인하고 필요한 수정·검증·로컬 커밋까지만 수행한다. 푸시·댓글 게시·PR 본문 수정은 수행하지 않는다.
- 범위: 작은 위도·경도가 지도 URL에서 지수 표기로 바뀌는 P3 검토 항목. Codecov의 커버리지 안내는 제품 결함 지적으로 해석하지 않는다.
- 초기 Git 상태: fix/48-static-map-xss, 미커밋 사용자 변경 없음. PR 최신 HEAD와 로컬 HEAD 일치 확인. 식별 정보는 Git 제외 도구 상태에서만 관리한다.
- 담당: 현재 Codex 순차 구현·자체 QA·자체 리뷰. 별도 에이전트·독립 수행은 요구하지 않았다.
- 지침: AGENTS.md, ai/WORKFLOW.md, docs/conventions/git.md, docs/harness/local-validation.md, ai/roles/review.md. 기존 수정 기록은 docs/harness/records/2026-10-19-issue48-static-map-xss.md.
- 환경: 저장소 루트, Java 25·Gradle Wrapper, Google Chrome 및 격리 PostgreSQL. 합성 지도 키·루프백을 사용하고 실제 지도 네트워크는 차단한다.
- 계획: MVC 회귀 선작성 → 수정 전 실제 실패 확인 → 검증된 숫자의 출력만 변경 → 관련 회귀·Chrome·API 명세·컴파일·정적 검사 → 자체 QA/리뷰·게이트·문서 금지 정보 확인 → 단일 로컬 커밋.
- 필수 실행 ID: pr53-decimal-verify. 선언: ai/local-state/pr53-decimal-config/plan.json. compile·regression·contract·chrome·forbidden·structure·license·qa·review를 필수로 선언한다. 기존 실행 상태를 덮어쓰거나 과거 결과를 새 실행으로 기록하지 않는다.

## 재현·수정·검증

### 수정 전 재현

제품 MvcConfiguration의 실제 변환기 순서를 사용하는 MVC 회귀를 선작성하고 수정 전 컨트롤러에서 실행했다. 신규 10건 중 7건 실패, 오류 0·스킵 0, 명령 종료 1이었다. `0.0005` → `5.0E-4`, `-0.0009` → `-9.0E-4`, `-0.0005` → `-5.0E-4`, `1e-7` → `1.0E-7` 등 실제 URL의 지수 표기를 확인했다. 기존 리뷰의 단일 표현식 실행과 구분되는 실제 컨트롤러 응답 재현이다.

### 수정 방향

- `LocationApiController.normalizeCoordinate()`의 마지막 출력만 `BigDecimal.valueOf(value).toPlainString()`으로 변경한다. 기존 숫자 문법·길이·유한값·정확한 범위 검증과 400/406/500 처리, 응답 문자열 계약은 유지한다.
- 사용자가 입력한 BigDecimal을 직접 소수 문자열로 확장하지 않는다. 검증된 유한 double 값만 변환하므로 매우 큰 음의 지수 입력도 double의 제한된 크기로 출력한다.
- 고정 소수 자릿수 반올림은 추가하지 않는다. 기존 `1.0`·`2.0` 정상 URL 기대값도 유지한다. 소수 표기에 붙는 끝자리 0은 수치에 영향을 주지 않는다.
- 회귀는 GOOGLE/HERE와 JSON/일반 텍스트에서 신규 10개 좌표의 소수 표기·수치 동등성·URL 중복 좌표 일치·출력 길이를 검사한다. 최소 subnormal double과 `1e-999999999` underflow 입력도 포함한다.
- 기존 실제 앱 Chrome 검사에는 작은 좌표 5종과 본초 자오선 부근 합성 좌표의 기존 관리자 서비스·제품 이미지 템플릿 연결 검사를 추가한다. 공통 LocationDescriptor와 제품 지도 설정은 변경하지 않는다.
- 새 의존성·공개 API 명세 변경·DB 마이그레이션은 없다.

### 실행 명령

저장소 루트에서 실행하며 보고서·환경 요약·실행 상태는 Git 제외 경로에만 둔다.

```sh
# 수정 전: 신규 회귀 10건, 실패 7건 확인
./gradlew test --tests 'alfio.controller.api.admin.LocationApiControllerTest.smallCoordinatesUseBoundedPlainDecimalUrls' --no-build-cache --console=plain -I ai/local-state/pr53-decimal-config/junit-output.gradle -Dharness.reports=ai/local-state/pr53-decimal-config/before-unit
# 수정 후: 필수 항목은 선언된 명령으로 새 실행
python3 -B ai/harness/run.py run pr53-decimal-verify compile
python3 -B ai/harness/run.py run pr53-decimal-verify regression
python3 -B ai/harness/run.py run pr53-decimal-verify contract
python3 -B ai/harness/run.py run pr53-decimal-verify chrome
python3 -B ai/harness/run.py run pr53-decimal-verify forbidden
python3 -B ai/harness/run.py run pr53-decimal-verify structure
```

### 수정 후 결과

첫 관련 회귀 61건은 통과했으나 OpenAPI 검사는 Docker 중지로 앱 초기화에 실패했다(1건 실패, 명령 종료 1). 제품 명세 차이가 원인은 아니며, Docker Desktop을 기동해 재검증했다. 소수 표기 검사를 수정 전 진단 모드에 강제하지 않도록 E2E 조건도 자체 점검했으며, 이후 코드를 기준으로 컴파일·회귀·QA를 다시 수행했다. 최종 재실행 결과는 다음과 같다.

| 검증 대상 | 실제 결과 |
|---|---|
| 제품·테스트 컴파일 | compileJava·compileTestJava 강제 재실행, 종료 0 |
| MVC 보안·작은 좌표 회귀 및 기존 생성기 | 56건 + 5건 = 61건, 실패 0·오류 0·스킵 0, 종료 0 |
| 기존 OpenAPI 명세 | 1건, 실패 0·오류 0·스킵 0, 종료 0. 기준 명세 변경 없음 |
| Google Chrome 브리지·실제 앱 | 2건, Chrome 세션 4개, 실패 0·오류 0·스킵 0, 종료 0 |
| 금지 API·문서 구조 | forbiddenApisMain·forbiddenApisTest 실제 재실행 및 ai/tests/check.py, 각각 종료 0 |
| 라이선스 | 명령 종료 0, 변경 Java 3개 각각 실제 Header OK 확인. 플러그인의 UP-TO-DATE 표시는 자동 통과 근거에서 제외 |

최종 실제 테스트는 총 64건이다. 보고서의 새 testcase와 대상 Gradle 작업의 실제 실행을 확인했으며 캐시·의존 작업 생략을 테스트 통과로 계산하지 않았다.

| 입력·조건 | 수정 전 | 수정 후 |
|---|---|---|
| 0.0005·-0.0009·1e-7 | URL에 지수 표기 포함, 신규 회귀 실패 | JSON·일반 텍스트 양쪽에서 소수 표기 및 수치 동등성 확인 |
| 최소 subnormal double | 지수 표기 출력 | 좌표별 330자 이하·전체 URL 2,000자 미만의 소수 표기 확인 |
| 매우 큰 음의 지수 | double underflow로 0.0 | 0.0 유지. 원본 지수 크기만큼 문자열을 확장하지 않음 |
| 일반 좌표·GOOGLE/HERE/NONE | 기존 URL 문자열 또는 빈 문자열 | 기존 1.0/2.0 형식·문자열 계약과 NONE 빈 문자열 유지 |
| 잘못된 좌표·HTML·지원하지 않는 Accept | 기존 보안 차단 적용 | 400·406 및 브리지/실제 Chrome 스크립트 무실행 유지 |

현재 Codex 자체 QA·리뷰를 완료했고 필수 9개 항목과 gate/resume 모두 통과했다(종료 0). 스테이징된 4개 파일의 diff·공백과 한국어 기록의 금지 정보·임시 산출물 포함 여부를 직접 검토하고 패턴 검사도 통과했다. 커밋은 이번 제품 출력 수정·회귀·Chrome 검사·작업 기록의 4개 파일만 포함하며 메시지는 `fix: 지도 URL 좌표의 소수 표기 유지`를 사용한다. 원격 푸시·PR 댓글·PR 본문 수정은 수행하지 않는다. 원본 로그·검증 상태·환경·테스트 산출물은 커밋하지 않는다. 계정·인증 정보·로컬 절대 경로·커밋 식별 정보는 기록하지 않는다.

## 최종 검증 범위와 한계

- 실제 Google Chrome 155.0.8059.80, ChromeDriver 155.0.8059.84, Selenium `--headless=new`를 사용했다. Codex 인앱 브라우저는 사용하지 않았다.
- 실제 폼 로그인·DB·응답 헤더가 포함된 앱 경로에서 GOOGLE/HERE/NONE을 조회해 설정 적용 여부를 확인했다. 추가한 작은 좌표 5종은 제공자 3개에서 총 15개 응답으로 확인했으며 JUnit 건수에 중복 합산하지 않았다.
- 본초 자오선 부근 합성 좌표 `51.4779`/`-0.0005`를 실제 Angular LocationService에 전달하고 제품 이미지 템플릿의 src가 소수 표기 URL과 같은지 검사했다. 일반 정상 좌표·경계·잘못된 입력·400/406·XSS 무실행 검사도 유지한다.
- 합성 지도 키와 외부 지도 네트워크 차단을 사용하므로 실제 Google/HERE의 픽셀 수신·키 유효성·할당량·외부 서비스 응답은 검증하지 않는다. 리뷰가 지적한 외부 지도 실패 가능성을 실제 외부 서비스 장애로 확정하지 않는다.
- 공통 URL 생성기·공개 행사 조회는 변경하지 않아 공개 행사·온라인 행사 전체 경로 재검증은 하지 않는다. 전체 제품 빌드·전체 예약 E2E·DB 업그레이드·원격 CI 재실행도 이번 로컬 수정 검증으로 주장하지 않는다.
- PR 본문·리뷰 댓글·원격 브랜치는 변경하지 않는다. 로컬 검증 상태·원본 로그·환경 파일·XML·JAR는 커밋하지 않는다.

## 완료 확인 절차

```sh
python3 -B ai/harness/run.py begin pr53-decimal-verify license
./gradlew licenseMain licenseTest --info --console=plain --no-build-cache --rerun-tasks
# 실제 파일별 Header OK 근거를 연결한 수동 license·qa·review record 후
python3 -B ai/harness/run.py gate pr53-decimal-verify
python3 -B ai/harness/run.py resume pr53-decimal-verify
# 이번 4개 파일만 명시적으로 스테이징한 뒤
python3 -B ai/local-state/pr53-decimal-config/staged-check.py
git diff --cached
git diff --cached --check
```
