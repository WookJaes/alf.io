# 작업 기록

- 작업 ID / 날짜 / 상태: #7 / 2026-10-07 / completed (구매 한도 안내 수정·관련 재검증 완료, 아래 미실행 범위 명시), 원격 미병합
- 요청·범위: [이슈 #7](https://github.com/WookJaes/alf.io/issues/7). 공개 카테고리 직접 링크의 선택적 `qty`, 기존 오류 처리·접근 제어 유지, 회귀 테스트와 안내 갱신. 사용자 승인 범위는 원본 로그를 제외한 현재 변경의 로컬 커밋까지다.
- 실행 모드: 현재 Codex의 순차 구현·검증·자체 리뷰. 별도 에이전트·독립 리뷰 없음.
- 브랜치·기준: `feat/7-reservation-quantity`, `de5a6b5f943553c5da5e737969da613df9f8a2db`. 최초 구현 시작 시 작업 트리 깨끗함. 사용자가 이전 커밋을 취소한 뒤 구현 변경은 스테이징된 상태로 유지되어 있었다.
- 환경·실행 위치: 저장소 루트, macOS, Corretto Java 25.0.4.1, Gradle Wrapper 9.7.1, Docker 29.5.2, PostgreSQL 16 Testcontainers.
- 검증 대상: 아래 제품 코드·테스트의 현재 변경. 기록 정리 전에 이전 검증 결과에 남긴 파일별 해시와 현재 11개 파일의 일치를 확인했다. 이후 제품 코드·테스트는 수정하지 않았으며 기록 파일만 정리했다.
- 기록 방식: 검증 명령·결과·실패 원인·조치를 이 문서에 요약한다. 원본 로그·오류 발췌·테스트 결과 JSON은 저장소에 보관하지 않는다. PR 생성 이후 노션에 PR·CI 링크와 요약을 반영한다.

## 변경과 검증

| 완료 기준 | 변경·확인 내용 | 결과 |
|---|---|---|
| 수량 전달 | IndexController의 두 직접 링크 경로에서 qty를 예약 API·PromoCodeRequestManager로 전달 | 쿼리 인코딩·기존 경로·소셜 미리보기 회귀 확인 |
| 다중 예약·기본 동작 | 공개 카테고리의 생략·1·3·5장 예약 | DB에서 하나의 예약과 지정 티켓 수량 확인 |
| 오류 시 예약 미생성 | 잘못된 수량 8건, 행사·카테고리 한도 2건, 재고 부족 1건 | 기존 오류 처리 유지, 예약·점유 티켓 0건 확인 |
| 코드 호환성 | 제한 카테고리·특별 가격 코드의 기존 1장 예약, 할인·없는 코드의 기존 이동 | 호환성 3건과 기존 전체 예약 흐름 1건 통과 |
| 안내 | 관리자 HTML의 qty=N 사용 안내와 [사용 문서](../../direct-reservation-links.md) | 실제 관리자 카테고리 화면에서 qty=N 안내 표시 확인 |

### 실행 명령·실제 결과

```sh
./gradlew test --rerun --tests alfio.controller.IndexControllerTest --tests alfio.controller.api.v2.user.reservation.ReservationFlowIntegrationTest -Dpgsql.version=16 --no-daemon
python3 -B ai/tests/check.py
python3 -B -m unittest discover -s ai/tests -p 'test_*.py' -v
```

- 이전 최종 제품 검증: 2026-10-07 실행, 종료 코드 0. 28건 실행·통과, 실패·오류·스킵 0건. PostgreSQL 통합 19건, 컨트롤러 9건(중첩 클래스 6건 포함). 테스트 태스크 실제 실행, 컴파일 UP-TO-DATE는 새 컴파일로 계산하지 않음.
- 신규 제품 테스트 19건(통합 18·컨트롤러 1), 기존 9건. 기본 PostgreSQL 10에서도 같은 28건 통과. 반복 실행을 신규 테스트 수로 합산하지 않음.
- 이전 하네스 검증: 구조 검사 종료 코드 0, 기존 회귀 14건 통과·종료 코드 0.
- 이번 기록 정리: 제품 테스트를 다시 실행하지 않음. 제품·테스트 파일이 이전 검증 대상과 동일하기 때문. 문서 링크·공백·로그 파일 제외 여부는 별도로 확인한다.
- 원본 결과는 테스트 실행 시 Gradle의 `build/test-results/test/`와 `build/reports/tests/test/`에서 확인한다. 저장소에는 복사하지 않는다. 원격 CI 결과는 아직 없으며 PR 생성 이후 링크를 연결한다.

## 실패·원인·조치

| 단계 | 확인한 실패 | 원인·조치 | 재검증 |
|---|---|---|---|
| 첫 테스트 | 25건 중 15건 실패, 종료 코드 1 | 테스트 SQL의 테이블·컬럼 오참조. 실제 tickets_reservation·event_id_fk로 수정 | 새 실행에서 해당 실패 해소 |
| 두 번째 테스트 | 28건 중 2건 실패, 종료 코드 1 | 특별 가격 코드가 WAITING 상태. 기존 generatePendingCodes 준비 단계 추가 | PostgreSQL 10·16에서 각각 28건 통과 |

- 환경 준비: Docker 미실행 상태를 확인해 시작했고, 테스트 기본 PostgreSQL 10과 CI 15/16/17의 차이를 확인해 16을 명시했다.
- 자체 리뷰: 완료 조건·diff·회귀 결과 대조. 기본 수량·URL 인코딩·예약 전 파싱·기존 한도·재고 검증·코드 접근 제어 유지 확인. 독립 리뷰는 미실행.
- 생략 검증: 전체 E2E 자동화·전체 제품 테스트·PostgreSQL 15/17·원격 CI. 관련 HTTP·PostgreSQL 통합 테스트 외에 아래 실제 브라우저 검증을 수행했다.
- 하네스 개선 후보: Docker·DB 버전 사전 점검, 실행 중 코드 변경에 따른 검증 무효화, 최종 코드와 검증 대상 일치 확인. 이번 작업에서는 하네스 자동 강제 기능을 추가하지 않음.
- 기록 정책: 이번 정리에서는 노션을 수정하지 않는다. 기존 노션에는 취소된 커밋과 제거된 증거 파일을 가리키는 기록이 남아 있으므로 PR 생성 후 정정한다.
- 성과 측정: 사람 검토·수정 시간, 전체 처리 시간, 생산성 개선율은 미측정. 실제 테스트 결과와 발견한 개선 후보만 기록한다.
- 최종 처리: 구현·테스트·사용 문서와 이 요약 기록을 로컬 커밋에 포함한다. 원본 로그·결과 JSON은 제외한다. 푸시·PR 생성은 수행하지 않으며 노션은 PR 생성 이후 갱신한다.

## PR 준비를 위한 로컬 브라우저 확인 — 2026-10-07

- 대상 코드: 로컬 커밋 `be17b3253`. 제품 코드 변경 없이 실행. 별도 PostgreSQL 16 컨테이너 `alfio-issue7-browser-db`(localhost:15437)와 앱 localhost:8087, 프로필 dev·demo·disable-jobs 사용. 기존 사용자 DB를 변경하지 않았다.
- 실행: 임시 Gradle 초기화 스크립트로 datasource URL·server.port·프로필만 덮어쓰고 `./gradlew -I /private/tmp/alfio-issue7-browser.gradle bootRun --no-daemon -x frontendBuild -x frontendPnpmInstall -x frontendAdminPnpmInstall` 실행. 기존 public 프론트 빌드 결과 사용. 앱 시작 확인, 서버는 검증 중 계속 실행했으므로 종료 코드를 테스트 성공값으로 표기하지 않음.
- 합성 데이터: 무료 행사 `issue7-local`, 공개 카테고리 코드 PUBLIC, 티켓 20장. 데모 계정·합성 로고 사용. 외부 메일·실제 결제 없음. demo 모드는 게시를 금지하므로 localhost 전용 합성 DB의 행사 상태만 PUBLIC으로 설정했다.

| 확인 항목 | 실제 화면·DB 결과 |
|---|---|
| 관리자 안내 | 카테고리 편집 화면에 `?qty=N`, `?qty=3`, 제한 카테고리는 1장이라는 설명 표시 |
| qty=3 | 예약 book 페이지로 이동, 참석자 티켓 입력 영역 3개. DB 예약 1건·티켓 3장 |
| qty 생략 | 예약 book 페이지로 이동, 참석자 티켓 입력 영역 1개. DB에 1장 예약 추가 |
| qty=0 | 행사 페이지의 Select at least one ticket 안내. 예약 수 1건·점유 티켓 3장으로 기존 수 유지 |
| qty=6 | 기본 한도 5 초과로 행사 오류 페이지 이동. 예약 수 2건·점유 티켓 4장 유지. 문구의 숫자 인자는 `{{0}}`으로 노출됨 |
| qty=21 | 합성 행사의 한도를 100으로 설정해 재고 검증. Not enough tickets 안내. 예약 수 2건·점유 티켓 4장 유지 |

- 추가 발견: 기존 직접 링크 오류 리다이렉트는 오류 코드만 전달하고 인자를 전달하지 않는다. 프론트 EventDisplayComponent도 쿼리 errors에서 code만 복원하여 구매 한도 숫자가 `{{0}}`으로 표시된다. 당시 예약 차단은 정상이지만 숫자 표시는 미해결이었다. 아래 구매 한도 안내 수정·재검증에서 해결했다.
- 환경 준비 실패·조치: 검증 DB의 MAPS_PROVIDER를 소문자 none으로 넣어 enum 파싱 오류 발생 → NONE으로 수정. SVG 로고 선택 후 미반영 → 합성 PNG로 변경해 행사 저장 성공. 제품 코드 수정 없음.
- PR 초안: 노션 기록 문장을 제거하고 실제 브라우저 검증 결과·미해결 한도 문구·원격 CI 미실행을 반영했다. 실제 PR은 아직 생성하지 않았다.
- 보관: 원본 로그·오류 발췌·결과 JSON을 저장소에 추가하지 않음. 브라우저 검증 당시에는 커밋하지 않았고, 이후 사용자 승인에 따라 이 MD 기록만 별도 로컬 커밋한다. 푸시·PR 생성·노션 수정은 수행하지 않음.

## 구매 한도 안내 수정·재검증 — 2026-10-07

- 요청·범위: 브라우저 검증에서 발견한 구매 한도 숫자 `{{0}}` 표시를 수정하고 재검증·기록까지 함께 커밋. 기준 커밋 `f1e4f3b21`, 브랜치 유지. 새 의존성·DB 마이그레이션 없음.
- 원인·수정: EventApiV2Controller가 STEP_1_OVER_MAXIMUM의 첫 인자를 선택적 maxTickets 쿼리로 전달한다. EventDisplayComponent는 해당 오류에서만 숫자 형식의 maxTickets를 번역 인자 0으로 복원한다. 기존 errors 쿼리와 다른 오류 동작은 유지한다.
- 회귀 보강: 행사·카테고리 한도 각각 2 설정에서 3장 요청 시 오류 코드와 maxTickets=2 전달, 예약·점유 티켓 0건을 확인한다. 쿼리 순서에 의존하지 않고 각 파라미터를 검사한다.

| 검증 | 실제 결과 |
|---|---|
| PostgreSQL 16 회귀·통합 | 아래 Gradle 명령 종료 코드 0. 28건 통과(통합 19·컨트롤러 9), 실패·오류·스킵 0. 변경 Java·테스트 컴파일 및 test 태스크 실제 실행 |
| 공개 프론트 production 빌드 | 아래 Angular build 명령 종료 코드 0. 빌드 해시 3ccec411054e8011, 수정된 public 번들 생성·로컬 앱 복사 확인 |
| 실제 브라우저: 한도 5, qty=6 | 오류 URL에 maxTickets=5, 안내의 최대 수량 (5) 표시. {{0}} 미노출 |
| 실제 브라우저: 한도 2, qty=3 | 합성 행사 설정 변경 후 maxTickets=2, 최대 수량 (2) 표시. 고정 문자열이 아닌 설정 값 사용 확인 |
| 기존 오류: qty=0 | 기존 Select at least one ticket 표시, maxTickets 쿼리 없음 |
| 오류 요청의 DB 영향 | 합성 DB 예약 2건·점유 티켓 4장으로 기존 수 유지. 이후 구매 한도 5로 복원 |

### 실제 명령

저장소 루트:

```sh
./gradlew test --rerun --tests alfio.controller.IndexControllerTest --tests alfio.controller.api.v2.user.reservation.ReservationFlowIntegrationTest -Dpgsql.version=16 --no-daemon
```

frontend/public:

```sh
../../.gradle/nodejs/node-v22.22.3-darwin-arm64/bin/node node_modules/@angular/cli/bin/ng.js build --aot=true --configuration production
```

- 화면 검증: 이전 검증의 독립 DB·합성 행사를 재사용하고 로컬 앱을 재시작했다. dev·demo·disable-jobs, localhost:8087. 새 번들의 index 변환·copyFrontendDev 실행을 확인했다. 결제·외부 메시지 없음.
- 테스트 실행 실패·제약: Angular 단위 테스트 실행은 karma-coverage 모듈 부재로 종료 코드 127·테스트 0건. 새 의존성을 추가하지 않는 임시 Karma 설정으로 재시도했으나 tsconfig.spec의 strict 설정과 앱의 완화된 설정 차이로 기존 파일의 컴파일 오류가 발생해 종료 코드 1·테스트 0건. 시도한 임시 프론트 spec은 최종 변경에서 제외했다. 자동 프론트 단위 테스트 통과로 계산하지 않으며 이번 인자 전달·표시는 백엔드 회귀, production 빌드와 실제 브라우저로 검증했다. 프론트 테스트 환경 정비는 별도 후속 범위다.
- 자체 리뷰: 오류 코드별 인자 적용, 숫자 형식 확인, 기존 errors 호환성, 쿼리 순서에 독립적인 테스트, 오류 시 예약 미생성 확인. 최종 제품 코드·실행 설정은 위 검증 이후 변경하지 않음. 독립 리뷰 미실행.
- 남은 범위: 전체 제품 테스트·PostgreSQL 15/17·전체 E2E 자동화·원격 CI 미실행. 하네스 구조·공백 검사도 기록 정리 후 확인한다.
- 최종 처리: 사용자 승인대로 백엔드·프론트·회귀 테스트·이 기록을 함께 로컬 커밋한다. 원본 로그·결과 JSON은 저장소에 추가하지 않으며 PR 생성·푸시·노션 갱신 없음.

## PR #8 CI 실패 대응 — 2026-10-07

- 요청·범위: PR의 실패 원인을 수정·검증·기록하고 커밋·푸시한다. 기준 커밋 47f821869. PR 본문은 수정하지 않는다.
- 실패: [push 실행](https://github.com/WookJaes/alf.io/actions/runs/37635014036)과 [PR 실행](https://github.com/WookJaes/alf.io/actions/runs/37635044749)의 build(17)에서 TestCheckRestApiStability 실패. 각각 테스트 849건 집계 중 실패 1·스킵 2, Gradle 종료 코드 1. 행렬의 build(15/16)는 fail-fast로 취소됐으며 해당 환경의 실패로 단정하지 않는다. CodeQL·하네스 Linux/Windows는 성공.
- 원인: 선택적 qty를 API에 추가했으나 src/test/resources/api/descriptor.json의 기준 명세에 반영하지 않았다. 검사 결과는 하위 호환으로 판단했지만 검사기는 isDifferent이면 실패한다. 최초 로컬 관련 테스트 선정에서 API 명세 검사를 놓쳤다.
- 수정: 기준 명세의 GET /api/v2/public/event/{eventName}/code/{code}에 qty(query·선택적·string)만 추가. 다른 명세 항목은 구조 비교로 변경 없음을 확인. 검사 로직·실패 조건·제품 코드 변경 없음. API 변경 작업의 필수 검증에 TestCheckRestApiStability와 전체 CI 명령을 연결하는 하네스 개선 후보로 기록한다.
- 실제 재검증 명령: `./gradlew build distribution jacocoTestReport -Dpgsql.version=17 --no-daemon` (저장소 루트, 기존 Java·Gradle·Docker 환경). 종료 코드 0, BUILD SUCCESSFUL, 2분 43초. build·distribution·jacocoTestReport 성공. 테스트 태스크 실제 실행, 캐시 컴파일은 새 컴파일로 계산하지 않음.
- 실제 건수: 전체 849건 집계, 통과 847건·실패 0·오류 0·스킵 2건. TestCheckRestApiStability 1건 통과. 스킵 항목은 NormalFlowE2ETest와 MigrationValidatorTest의 환경 의존 검사이며 통과로 계산하지 않음. API 명세 검사 포함 전체 제품 검증을 이번 실행에서 보완했다.
- 자체 리뷰·확인: JSON 구조 비교에서 의도한 선택적 qty 외 변경 없음. 하네스 구조·diff 공백 검사 통과. 사용자 승인대로 기준 명세와 이 기록만 커밋·푸시한다. 수정 후 원격 CI 결과는 후속 확인이며 PR 본문은 보존한다.
- 기록 방식: 원본 로그는 추가하지 않고 실제 명령·결과·원인·조치를 요약한다.
