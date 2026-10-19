# 이슈 #48 지도 이미지 URL 응답 XSS 수정 기록

- 날짜·상태: 2026-10-19, completed.
- 범위: 이슈 #48, 보안 알림 #8. 이슈 #47의 이미지 다운로드 SSRF 변경은 제외한다.
- 실행 모드: 현재 Codex의 순차 구현·검증·자체 QA·자체 리뷰.
- 초기 Git 상태: main, 사용자 미커밋 변경 없음. 원격 main을 조회한 뒤 fix/48-static-map-xss로 분기했다.
- 기준: 작업 시작 시 최신 main의 지도 API·URL 생성기·MVC 응답 변환기. 식별 정보는 Git 제외 로컬 검증 상태에서 관리한다.
- 실행 위치: 저장소 루트. 합성 설정·좌표와 루프백만 사용한다.
- 참조 지침: AGENTS.md, ai/WORKFLOW.md, ai/routing.md, ai/roles/verification.md, ai/roles/review.md, docs/conventions/git.md, docs/harness/local-validation.md, docs/testing/automatic-validation.md.
- 환경: macOS, Corretto Java 25.0.4.1, Google Chrome 155.0.8059.80. 브라우저 실제 세션 버전·실행 여부는 아래 결과에서 구분한다.
- 완료 기준: 현재 코드 재현 진단, 좌표/응답 보안 수정, MVC 회귀·실제 앱 인증/헤더·Chrome 검증, 문서 및 필수 로컬 게이트·리뷰. 모두 완료한 경우에만 로컬 커밋한다.
- 계획: 회귀 선작성 및 수정 전 실패 → MVC/Chrome·전체 앱 관측 → API 한정 수정 → 동일 입력 재검증 → 필수 게이트·자체 리뷰 → 기록 금지 정보 검사·명시적 스테이징·커밋.

## 수정 전 진단

이슈 최신 본문을 GitHub에서 조회했다. 기존 증거는 실제 컨트롤러·응답 변환기로 만든 응답을 별도 루프백 서버에서 여는 분리 재현이며, 전체 앱 인증·보안 헤더를 포함하지 않는다. 이번 작업에서 새 실행으로 확인한다.

제품 MvcConfiguration은 JSON 변환기 다음 모든 미디어 타입을 지원하는 문자열 변환기를 등록한다. 테스트 ControllerConfiguration은 문자열 변환기가 먼저이므로 이번 분리 검사는 제품 MvcConfiguration의 실제 등록 순서를 사용한다. 기존 관리자 LocationService.getMapUrl은 HTTP 응답의 res.data 문자열을 반환한다.

## 변경과 검증

### 수정 내용

- `src/main/java/alfio/controller/api/admin/LocationApiController.java`: 관리자 지도 URL 경로에만 숫자 문법·길이·유한값·위도 ±90/경도 ±180 검증을 추가했다. BigDecimal 비교로 double 반올림 때문에 경계 밖 값이 허용되는 경우도 차단한다. 유효 값은 정규화한 숫자 문자열만 URL 생성기에 전달한다.
- 잘못된 좌표·HTML·누락은 HTTP 400, 본문 `Invalid map coordinates`, `text/plain`으로 처리한다. 이 오류는 공통 예외 처리기로 던지지 않는다. URL 생성 중 내부 오류는 HTTP 500, 고정 본문 `Unable to generate map URL`, `text/plain`으로 처리해 원본 메시지를 반환하지 않는다.
- 지원 응답 형식은 `application/json`과 `text/plain`이다. JSON은 URL 문자열이며 객체로 변경하지 않았다. 기존 관리자 Accept(`application/json, text/plain, */*`)와 기본·wildcard 요청을 유지한다. 응답 형식 협상은 이 API 안에서만 처리하며 구체적인 q=0 제외도 존중한다.
- HTML 전용 등 지원하지 않는 형식은 빈 HTTP 406 `text/plain`으로 직접 반환한다. `sendError`를 사용하면 전체 앱에서 HTML 오류 페이지가 생성되므로 직접 응답한다.
- `src/test/java/alfio/controller/api/admin/LocationApiControllerTest.java`: 제품 MVC 변환기 순서로 좌표·응답·내부 오류·제공자·미디어 타입 회귀를 추가했다.
- `src/test/java/alfio/e2e/MapImageSecurityE2ETest.java`: 별도 MVC 응답 브리지와 실제 앱 인증·응답 헤더·Chrome 탐색·기존 Angular 지도 서비스 및 이미지 템플릿 연결을 구분해 검사한다. 진단 모드는 수정 전 앱 측정에만 사용하며 최종 게이트에는 사용하지 않는다.
- `src/test/java/alfio/e2e/ValidationEnvironment.java`: 지도 검증 전용 제공자 선택 경로를 추가했다. 기존 startApp 기본 NONE은 유지하고 지도 검증에서만 GOOGLE/HERE를 지정한다. 실제 관리자 설정 조회 및 URL 호스트로 적용 여부를 확인한다.
- `build.gradle.kts`: 기존 opt-in e2eValidation 대상에 지도 보안 검사를 포함했다.
- `LocationDescriptor`, 제품 기본 지도 설정, 전역 문자열 변환기, 공통 예외 처리기 및 이슈 #47 SSRF 코드는 변경하지 않았다. 새 의존성·공개 응답 객체 변경·DB 마이그레이션은 없다.

### 실행 환경·검증 방식

- Java 25.0.4.1, Gradle Wrapper 9.7.1, Docker Engine 29.5.2, 전용 PostgreSQL 16.13 컨테이너.
- 실제 실행한 Google Chrome **155.0.8059.80**, ChromeDriver **155.0.8059.84**, Selenium, `--headless=new`, 임시 Chrome 프로필. Codex 인앱 브라우저는 사용하지 않았다.
- 브리지 검사는 제품 컨트롤러·제품 MvcConfiguration 변환기로 만든 응답을 `http://127.0.0.1:<임시포트>/`에서 그대로 제공한다. 인증 필터·DB·CSP는 없으며 전체 앱 검증으로 계산하지 않는다.
- 전체 앱 검사는 현재 소스에서 빌드한 boot JAR, 실제 Spring Security 폼 로그인과 Chrome 세션, 실제 응답 헤더, 전용 합성 DB를 사용한다. 수정 전 JAR는 제품 변경 전에 생성·보존했으며 수정 전 전체 앱 재검증에만 사용했다.
- 실제 사용자·운영 데이터·기존 Chrome 프로필을 사용하지 않았다. 본문·쿠키·인증 정보·원본 오류 덤프를 기록하지 않는다. 합성 지도 키를 사용하고 Chrome의 외부 지도 호스트 요청은 차단한다.
- 입력은 정상 `37.5`/`127.0`, 경계 ±90/±180, 빈 값·누락, 비숫자, NaN·Infinity·과도한 지수·범위 초과·반올림 경계 초과·URL 구문 입력, 실행 시 문서 제목만 `XSS_LOCAL_MARKER`로 바꾸는 HTML이다. 개인정보 접근이나 외부 전송은 없다.
- 정상·HTML 입력마다 `Accept: text/html`, 일반 Chrome 탐색 헤더, 기존 관리자 Accept, `text/plain`을 적용한다. 명시적 `application/json`, wildcard 및 q=0 조합은 MVC 회귀에서도 검사한다.
- Selenium의 CDP 153 근접 버전 경고는 발생했지만 실제 Chrome/ChromeDriver는 위 버전이며 브라우저 명령의 성공 여부는 각 검사에서 확인한다. 의존성 업데이트는 수행하지 않았다.

### 수정 전 새 실행 결과

| 대상 | 실제 실행 건수·종료 코드 | 확인 결과 |
|---|---|---|
| 선작성 MVC 회귀 | 41건, 실패 29·오류 0·스킵 0, 종료 1 | 잘못된 좌표·HTML 수용, HTML 응답 허용, 원본 내부 오류 메시지 노출 확인 |
| 초기 MVC 브리지 및 실제 앱 Chrome 검사 | 각 실행 2건, 3회 모두 실패 1건, 종료 1 | 브리지 스크립트 실행 확인. 실제 앱 측정 중 검증 스크립트의 digest/렌더링 대기와 NONE 빈 응답의 헤더 기대를 보정 |
| 보존한 수정 전 앱과 최신 MVC를 함께 검사한 진단 | 2건 중 전체 앱 1건 통과, MVC 1건 실패, 종료 1 | 전체 앱은 GOOGLE/HERE 실행·NONE 무실행. MVC는 이미 수정된 소스를 사용했으므로 수정 전 기대와 불일치; 이 혼합 실행을 통과로 계산하지 않음 |
| 보존한 수정 전 앱만 분리한 최종 진단 | 1건 통과·실패 0·오류 0·스킵 0, 종료 0 | GOOGLE/HERE/NONE을 모두 실제 적용·조회하고 인증된 경로의 상태·형식·본문·Chrome 실행 및 기존 지도 서비스/템플릿 연결 확인 |

수정 전 GOOGLE/HERE는 정상·HTML 입력 모두 HTTP 200이었다. HTML Accept와 Chrome 탐색은 `text/html`이며 HTML 태그를 그대로 포함했다. 기존 관리자 Accept는 JSON 문자열, text/plain 요청은 일반 텍스트였지만 HTML 입력을 거절하지 않았다. 실제 앱 지도 응답에는 CSP가 없었고, 비어 있지 않은 응답의 `X-Content-Type-Options: nosniff`만으로는 HTML 실행을 막지 못했다. NONE은 빈 URL이므로 스크립트가 없었으며 빈 일반 텍스트 응답의 헤더가 생략되는 경우를 구분했다.

### 수정 과정에서 발견한 차이와 조치

1. MVC의 기본 406 처리는 안전해 보였지만 전체 앱에서 HTML 오류 페이지로 바뀌었다. 수정 후 Chrome 예비 검사 2건 중 1건이 실패해 이 차이를 발견했다. 해당 경로에서 빈 406을 직접 반환하도록 변경했다.
2. 두 번째 매핑을 이용한 406 fallback은 일반 브라우저·wildcard의 우선순위에 영향을 줬다. Chrome 예비 검사 2건 중 1건, 관련 단위 검사 50건 중 1건이 실패했다. fallback을 제거하고 API 내부에서 JSON/일반 텍스트만 선택하도록 변경했다.
3. MediaType의 품질 우선 비교 때문에 명시적 q=0 제외가 wildcard에 덮이는 문제는 관련 단위 검사 51건 중 1건 실패로 확인했다. 품질을 제외한 구체성을 먼저 비교하도록 보정한 뒤 51건 전부 통과했다. 이 예비 실행과 별도로 최종 게이트는 새 보고서로 실제 실행한다.
4. 정상 응답 계약을 확인하는 기존 OpenAPI 안정성 검사 1건이 선택적 Accept 헤더의 명세 추가 때문에 실패했다. 실제 호출은 하위 호환이었으나 불필요한 명세 차이를 없애기 위해 HttpServletRequest에서 헤더를 읽도록 바꿨다. 기준 명세 파일은 수정하지 않았다. 이 변경 후 필수 검증은 새 실행 ID로 모두 다시 수행한다.
5. 라이선스 명령은 종료 0이어도 플러그인이 UP-TO-DATE를 출력해 자동 게이트가 거부했다. 상세 출력으로 변경 Java 4개 각각의 실제 Header OK를 새로 확인한 뒤 수동 QA 항목으로 선언했다. 선언 변경에 따라 새 실행 ID에서 컴파일·회귀·Chrome·명세·금지 API·구조 검사를 다시 수행한다. 상태 표시를 테스트 통과로 계산하지 않는다.
6. 최초 수정 뒤 관련 단위 검사 50건도 통과했으나 전체 앱에서 확인한 위 차이가 남아 있었으므로 그 시점에는 완료·커밋하지 않았다.

### 명령·근거

모든 명령은 저장소 루트에서 실행했다. 로컬 실행 상태·브라우저/회귀 보고서는 Git 제외 `ai/local-state/` 아래에 보관하고 원본 로그 및 임시 JAR는 커밋하지 않는다.

```sh
# 수정 전: 선작성 보안 회귀 실패 확인
./gradlew test --tests alfio.controller.api.admin.LocationApiControllerTest --console=plain --no-build-cache -Pverbose
# 수정 전: 로컬 전용 실행 설정으로 브리지·전체 앱을 각각 진단
./gradlew mapSecurityValidation -I ai/local-state/issue48/map-validation.gradle -Dmap.validation.baseline=true -Dharness.reports=<새로운-수정전-보고서-상대경로> --console=plain --no-build-cache -Pverbose
# 보존한 수정 전 JAR로 실제 앱만 재확인
./gradlew mapSecurityValidation --tests alfio.e2e.MapImageSecurityE2ETest.authenticatedApplicationAndLegacyMapBindingInChrome -I ai/local-state/issue48/map-validation.gradle -Dmap.validation.baseline=true -Dharness.reports=<새로운-수정전-전체앱-보고서-상대경로> --console=plain --no-build-cache -Pverbose
# 수정 후: 관련 회귀 예비 실행
./gradlew test --tests alfio.controller.api.admin.LocationApiControllerTest --tests alfio.model.modification.support.LocationDescriptorTest --console=plain --no-build-cache
# 수정 후: 필수 로컬 검증 선언·실행
python3 -B ai/harness/run.py init issue48-security-complete ai/local-state/issue48/plan.json
python3 -B ai/harness/run.py run issue48-security-complete compile
python3 -B ai/harness/run.py run issue48-security-complete regression
python3 -B ai/harness/run.py run issue48-security-complete chrome
python3 -B ai/harness/run.py run issue48-security-complete contract
python3 -B ai/harness/run.py run issue48-security-complete forbidden
python3 -B ai/harness/run.py begin issue48-security-complete license
./gradlew licenseMain licenseTest --info --console=plain --no-build-cache --rerun-tasks
python3 -B ai/harness/run.py record issue48-security-complete license --evidence ai/local-state/issue48/license-evidence.md --scope '변경 Java 4개 실제 라이선스 헤더 검사' --reporter '현재 Codex 자체 확인' --summary '각 파일 Header OK 확인'
python3 -B ai/harness/run.py run issue48-security-complete structure
# 자체 QA·리뷰 begin/record 후 완료 판정
python3 -B ai/harness/run.py gate issue48-security-complete
python3 -B ai/harness/run.py resume issue48-security-complete
# 명시적 6개 파일 스테이징 후 최종 확인
python3 -B ai/local-state/issue48/staged-doc-check.py
git diff --cached
git diff --cached --check
```

로컬 전용 mapSecurityValidation은 제품 boot JAR와 테스트 클래스 경로를 사용하는 Gradle Test 작업이다. 수정 전 진단만 보존한 JAR를 선택하며, 수정 후에는 bootJar에 의존해 현재 소스로 빌드한다. 검증 설정은 제품 기본 설정을 바꾸지 않는다. 저장소에 남기는 동일 검사는 `./gradlew e2eValidation --tests 'alfio.e2e.MapImageSecurityE2ETest' --no-build-cache -Pverbose`로 실행할 수 있다.

필수 게이트 실행 ID는 `issue48-security-complete`, 선언은 `ai/local-state/issue48/plan.json`이다. 필수 항목은 compile·regression·chrome·contract·forbidden·license·structure·qa·review이며 QA와 리뷰는 현재 Codex 자체 수행, 독립 수행 요구는 없다. 세션 기준표는 issue48, 체크포인트는 단계 경계에서 갱신한다. 검증 도구의 자동 식별 정보는 제외 상태에만 유지한다.

### 최종 결과

| 최종 검사 | 실제 실행·결과 |
|---|---|
| 제품·테스트 컴파일 | compileJava·compileTestJava 실제 재실행, 종료 0. 캐시 성공으로 계산하지 않음 |
| 지도 API 회귀·기존 URL 생성기 | 46건 + 5건 = 51건, 실패 0·오류 0·스킵 0, 종료 0 |
| Chrome 분리 브리지·전체 앱 | 2건, Chrome 세션 4개(브리지 1 + 제공자 3), 실패 0·오류 0·스킵 0, 종료 0 |
| 기존 OpenAPI 안정성 | 1건, 실패 0·오류 0·스킵 0, 종료 0. 기준 명세 변경 없음 |
| 금지 API | forbiddenApisMain·forbiddenApisTest 실제 재실행, 종료 0 |
| 라이선스 | 명령 종료 0, 변경 Java 4개 각각 실제 Header OK. 플러그인 상태 표시와 구분해 자체 확인 |
| 문서 구조 | ai/tests/check.py 종료 0 |

실제 테스트는 최종 실행에서 총 54건이며 테스트 대상 작업의 새 실행·새 XML을 확인했다. 빌드 의존 작업의 캐시 재사용은 테스트 수행으로 계산하지 않았다. 전체 앱에서는 제공자별 정상·HTML 입력 × 네 가지 Accept의 24개 응답과, 제공자별 잘못된 값·누락·경계 조합의 51개 응답을 확인했다. 이 요청 조합을 별도의 JUnit 테스트 건수로 중복 계산하지 않았다. 실제 로그인·제공자 설정 조회·기존 Angular 서비스 및 이미지 템플릿 연결·Chrome 문서 탐색도 확인했다.

| 비교 항목 | 수정 전 | 수정 후 |
|---|---|---|
| GOOGLE/HERE의 HTML 전용 요청 | 200 text/html, 입력 태그 포함. 별도 브리지에서 스크립트 실행 | 빈 406 text/plain, 입력 태그 없음. 같은 Chrome 입력 무실행 |
| GOOGLE/HERE 실제 앱의 Chrome 탐색 | 200 text/html, 실제 로그인 상태에서 스크립트 실행 | 400 text/plain, 고정 오류 메시지, 스크립트 무실행 |
| 기존 관리자 Accept의 정상 좌표 | 200 JSON URL 문자열 | 200 JSON URL 문자열 유지, 실제 Angular 서비스·템플릿 src 연결 통과 |
| 잘못된 좌표·HTML·NaN·무한대·범위 초과·누락 | MVC에서 잘못된 입력 수용; 공통 예외 경로는 원본 메시지 노출 | 지원 형식 요청에서 400 text/plain, 입력 반사 없음. 내부 오류는 고정 500 text/plain |
| 정상 경계·GOOGLE/HERE/NONE | 제공자별 URL 또는 빈 URL | 경계 포함 200, GOOGLE/HERE URL·NONE 빈 문자열 유지. NONE의 HTML 입력도 400 |
| 검증 한계 | 기존 이슈 증거는 분리 브리지였음 | 이번에는 분리 검사와 실제 앱을 각각 수행. 지도 픽셀·실제 외부 API 성공은 제외 |

현재 Codex의 자체 QA·최종 diff 리뷰를 완료했고 필수 gate와 resume 모두 통과했다(종료 0). 스테이징 6개 파일과 한국어 MD의 기록 금지 정보·임시 산출물 포함 여부를 직접 검토하고 자동 패턴 검사도 통과했다. git diff --cached 및 git diff --cached --check로 최종 내용을 확인했다. 전체 일반 제품 빌드·배포 산출물 생성·전체 예약 E2E·DB 업그레이드 검사는 프론트·DB·공통 생성기 변경이 없으므로 수행하지 않았으며, 이번 검증 통과로 표시하지 않는다.

이 기록과 제품 코드·회귀 테스트·검증 환경·E2E 연결의 6개 파일을 하나의 로컬 커밋으로 묶는다. 커밋 메시지는 `fix: 지도 이미지 URL 응답의 XSS 차단`이며 원격 작업은 수행하지 않는다.

## 로컬 상태 ID 충돌

기존 Git 제외 검증 상태와 실행 ID가 충돌했다. init은 덮어쓰기를 거절했으나 후속 begin이 기존 ID의 QA 시작 표식 하나를 갱신했다. 이 잘못된 시작 표식과 기존 실행 결과는 이번 검증에 사용하지 않는다. 이후 새 전용 ID로 분리하고 실패 시 후속 명령을 중단하도록 실행했다. 기존 제품·사용자 추적 파일 및 다른 검증 결과는 변경하지 않았다.

## 미검증 범위·남은 위험

- 합성 지도 키와 외부 지도 네트워크 차단을 사용했다. 외부 지도 API의 실제 성공·지도 픽셀 수신·유효 키·할당량·실서비스 가용성은 검증하지 않았다. 검증한 것은 정상 URL 생성, 실제 관리자 서비스의 문자열 처리, 제품 이미지 템플릿의 src 연결이다.
- 공통 LocationDescriptor와 공개 행사 조회는 수정하지 않았다. 기존 생성기 테스트는 관련 회귀로 실행하며, 공개 행사·온라인 행사·좌표 누락 데이터의 전체 앱 경로 재검증은 공통 로직 변경이 없으므로 이번 변경의 필수 범위에 포함하지 않는다. 관리자 지도 API의 좌표 누락은 별도로 HTTP 400을 검증한다.
- 실제 보안 알림의 원격 재분석·종료, 원격 푸시·PR·이슈 댓글은 수행하지 않는다.
- 다른 API의 기존 공통 예외 처리·문자열 변환기 문제는 이 범위에서 변경하지 않는다.
