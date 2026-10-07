# 작업 기록

- 작업 ID / 날짜 / 상태: #9 / 2026-10-08 / completed (이슈 범위 구현·관련 검증 완료, 미실행 범위와 기존 시간대 제약은 아래 명시)
- 요청·완료 기준: [이슈 #9](https://github.com/WookJaes/alf.io/issues/9). 참석자 CSV·Excel에 예약자 이메일 선택 항목 추가, 참석자 이메일·기존 필드·권한 유지. 구현·검증·기록·로컬 커밋까지 승인.
- 실행 모드: 현재 Codex 순차 구현·검증·자체 리뷰. 별도 에이전트 없음.
- 브랜치·기준: feat/9-reservation-email-export, 최신 origin/main 05e5ef8fcc9314503d660c5b09aa797022937f73. 시작 작업 트리 깨끗함.
- 환경: macOS, Corretto Java 25.0.4.1, Gradle Wrapper, Docker 29.5.2, PostgreSQL Testcontainers. 명령은 저장소 루트에서 실행.
- 계획: 기존 조회·필드 선택 흐름 확인 → 예약자 이메일 항목 추가 → CSV·Excel·권한·기존 선택 회귀 검증 → 요약 기록·자체 리뷰·커밋.
- 기록 정책: 원본 로그·결과 JSON을 커밋하지 않고 명령·결과·실패와 조치를 요약. 노션은 PR 생성 이후 기록.

## 변경과 검증

| 완료 기준 | 변경·확인 내용 | 검증 | 결과 |
|---|---|---|---|
| 예약자 이메일 내보내기 | 고정 필드 마지막에 Reservation E-Mail 추가. 기존 조회의 예약 이메일 활용 | CSV·Excel 실제 응답 파싱 | 예약자·참석자 이메일이 각 열에 구분됨 |
| 동일 예약의 다중 티켓 | 예약 1건에 합성 참석자 2명 준비 | CSV·Excel 각 행·예약 ID 비교 | 두 행에 동일 예약자 이메일 |
| 기존 항목 유지 | 기존 고정 열 순서 유지, 새 항목은 선택 시에만 출력 | 기존 전체 CSV·추가 Company 필드·이메일 단독 선택 | 기대 열·값 일치 |
| 관리자 선택 항목 | getAllFields에 새 항목 등록. 기존 화면은 API 목록으로 항목 생성 | 실제 필드 목록 API의 컨트롤러 통합 검증 | E-Mail과 Reservation E-Mail 모두 존재 |
| 권한 유지 | 기존 checkEventOwnership 선행 검사 유지 | CSV·Excel 비인가 사용자 | 접근 거부, 다운로드 응답 본문·첨부 헤더 미생성 |
| 사용 안내 | [참석자 내보내기 문서](../../attendee-export.md) 추가 | 문서·코드 대조 | 선택적 항목·각 이메일 의미 설명 |

### 실행 명령·실제 결과

```sh
./gradlew test --tests alfio.controller.api.admin.EventApiControllerIntegrationTest --tests alfio.controller.api.TestCheckRestApiStability -Dpgsql.version=16 --no-daemon
```

- 최종 관련 검증: 종료 코드 0, 14건 통과·실패 0·오류 0·스킵 0. 참석자 컨트롤러 13건(신규 7·기존 6), API 명세 검사 1건. 실제 Testcontainers DB·CSV·XLSX 응답 사용. 반복 실행을 신규 건수로 합산하지 않는다.
- 기존 예약 조회가 이메일을 포함하므로 새 DB 쿼리·스키마·의존성 불필요. 내보내기 요청 경로·파라미터와 기존 필드 선택은 유지한다. 필드 목록 응답과 선택된 파일 출력에 항목을 추가한다.
- 검증 대상 제품 코드: EventApiController.java. 테스트: EventApiControllerIntegrationTest.java. 최종 전체 빌드 전 import 정리 외 동작 변경 없음. 생성된 테스트 보고서는 build/test-results/test와 build/reports/tests/test에만 있고 커밋하지 않는다.

## 실패·리뷰·재개

- 환경 점검: 샌드박스에서 Docker 소켓 접근 거부. 승인된 확장 실행으로 Docker 29.5.2 정상 응답 확인. 제품 실패 아님.


| 실패 단계 | 결과·원인 | 조치·재검증 |
|---|---|---|
| 첫 관련 검증 | 종료 코드 1, 14건 중 5건 실패. 새 2명 합성 데이터의 외부 식별자 중복으로 예약 요청 거부, 기존 CSV 기대값에 새 열 누락 | 합성 식별자 분리·기대값 갱신 |
| 두 번째 관련 검증 | 종료 코드 1, 14건 중 3건 실패. 추가 필드의 additional_service_id에 null 사용해 NOT NULL 제약 위반; CSV 읽기에서 행 배열 옵션 누락 | 기존 추가 필드 준비 관례의 -1 사용, 기존 CSV 읽기 관례인 WRAP_AS_ARRAY 적용 |
| 세 번째 관련 검증 | 종료 코드 0, 14건 통과 | 위 테스트 준비·검증 코드 오류 해소. 제품 코드 변경 없이 재검증 |

- 자체 리뷰: diff·이슈 완료 조건·기존 관리자 필드 선택 흐름·조회·권한 검사·실제 파일 응답을 대조했다. 예약자 이메일은 참석자 이메일 대신 덮어쓰지 않고 별도 선택 항목으로 제공한다. 고정 항목·이탈리아 전자 청구 항목·추가 항목의 기존 생성 순서를 유지한다.
- 관련 기억: 이번 변경에는 이전 실패와 같은 제품 원인이 없으므로 기억 인덱스는 조회하지 않았다. 이전 API 명세 누락 사례를 고려해 관련 검증에 명세 검사를 포함했다.
- 최초 커밋 당시 미실행: 실제 브라우저 조작·프론트 단위 테스트·전체 E2E 완료 검증·원격 CI. 브라우저 검증은 아래 후속 절에서 완료했다. 프론트 소스 변경 없이 기존 동적 항목 목록을 사용한다. 화면 검증으로 주장하지 않고 항목 목록과 실제 다운로드 응답 검증으로 구분한다.
- 보안·데이터: 합성 이메일·행사·참석자 데이터만 사용. 실제 사용자 DB 변경·메일 발송·원본 로그 커밋 없음.
- 최종 제품 빌드 결과와 커밋 전 확인은 아래에 추가한다. 푸시·PR 생성·노션 기록은 이번 요청 범위에 포함되지 않는다.


### 전체 제품 빌드의 날짜 비교 실패·재검증

- 첫 전체 명령: `./gradlew build distribution jacocoTestReport -Dpgsql.version=17 --no-daemon`. 종료 코드 1, 856건 집계·실패 12·오류 0·스킵 2. 실패 12건은 기존 BaseReservationFlowTest의 중복 체크인 검사에서 BADGE_SCAN_ALREADY_DONE 대신 BADGE_SCAN_SUCCESS가 반환된 동일 증상이다. 내보내기 통합 13건과 API 명세 검사 1건은 통과했다.
- 코드 근거: TestUtil은 UTC 날짜의 10시로 시간을 고정하지만 CheckInManager의 감사 기록은 실제 new Date()를 사용한다. AuditingRepository는 timestamp의 날짜로 동일 일자 여부를 비교한다. 한국 시간 자정 이후 UTC와 로컬 날짜 차이로 기존 테스트가 영향을 받을 가능성을 확인했다.
- 조치: 제품·테스트 코드를 바꾸지 않고 `TZ=UTC`로 전체 명령 재실행. 시간대에 따른 결과 차이와 전체 빌드 결과는 다음 항목에 기록한다. 기존 체크인 시간 처리의 수정은 이번 이메일 내보내기 범위에서 수행하지 않는다.
- 하네스 개선 후보: 검증의 시간대·고정 시계·실시간 감사 기록 사용을 사전 점검에 포함. 이 문제로 제품 테스트 실패를 성공으로 계산하지 않는다.


### 최종 결과·커밋 전 확인

- UTC 재검증 명령: `TZ=UTC ./gradlew build distribution jacocoTestReport -Dpgsql.version=17 --no-daemon`. 종료 코드 0, 2분 15초. 856건 집계·통과 854·실패 0·오류 0·스킵 2. 스킵은 MigrationValidatorTest.testMigration과 NormalFlowE2ETest.testFlow이며 통과로 계산하지 않는다. test·build·distribution·jacocoTestReport 실제 실행, 프론트 빌드는 UP-TO-DATE이므로 새 프론트 빌드 성공으로 계산하지 않는다. 금지 API·라이선스 검사는 첫 전체 실행에서 수행됐고 최종 실행은 UP-TO-DATE다.
- 동일 제품·테스트 코드에서 실행 시간대만 변경해 체크인 실패 12건이 해소됐다. 시간대 영향은 재현됐으며 기존 시계 혼용 코드 자체를 고친 것은 아니다. 로컬 기본 시간대에서의 전체 테스트 제약은 남아 있다.
- 검증 대상 SHA-256: EventApiController.java `251ce2b42cf2e088ff01ab3072ef2e06123db94f56e4965493cd5e60bd1674dd`, EventApiControllerIntegrationTest.java `88457d6a080cad36723127e4c43286c4c0cf9c169cccb4ab589de5db6d6a1400`. 최종 검증 이후 제품·테스트·실행 설정 변경 없음. 기록 문서만 정리했다.
- 하네스 구조 검사: `python3 -B ai/tests/check.py` 종료 코드 0. 공백 검사 `git diff --check` 종료 코드 0. 문서 상대 링크·최종 4개 파일·비밀값 패턴과 diff 자체 리뷰 완료. 하네스 실행 로직 변경이 없어 하네스 도구 회귀는 반복하지 않았다.
- 처리: 사용자 승인에 따라 제품 코드·통합 테스트·사용 안내·이 기록을 하나의 로컬 Conventional Commit에 포함한다. 원본 로그·실제 고객 데이터·새 의존성·DB 마이그레이션 없음. 푸시·PR 생성·노션 갱신 없음.


## PR 생성 전 실제 브라우저 검증 — 2026-10-08

- 요청·대상: 사용자 요청에 따라 로컬 관리자 화면의 항목 표시·CSV·Excel 직접 다운로드를 검증했다. 제품 기준 커밋 9d717ab9f, feat/9-reservation-email-export, 시작 작업 트리 깨끗함. 제품·테스트 변경 없음.
- 환경·명령: 기존 검증 전용 PostgreSQL 16 컨테이너 alfio-issue7-browser-db(localhost:15437, alfio_issue7)를 재사용했다. `./gradlew -I /private/tmp/alfio-issue7-browser.gradle bootRun --no-daemon -x frontendBuild -x frontendPnpmInstall -x frontendAdminPnpmInstall`로 localhost:8087·dev/demo/disable-jobs 실행. 시작 성공, 앱은 실행 상태이므로 종료 코드 0 테스트로 표기하지 않는다. 프론트는 기존 빌드 결과 사용.
- 합성 데이터: 기존 issue7-local 합성 행사에 관리자 화면으로 예약 1건·참석자 2명을 추가하고 무료 예약을 Mark as Completed로 확정했다. 예약자와 두 참석자는 서로 다른 example.test 이메일을 사용했다. 예약 화면에서 COMPLETE·티켓 2장·Emails sent 0 확인. 기존 테스트 예약은 수정하지 않았고 실제 결제·외부 메일·사용자 DB 변경 없음.

| 화면·다운로드 검증 | 실제 결과 |
|---|---|
| 관리자 항목 표시 | Download → attendees' data 대화상자에서 E-Mail과 Reservation E-Mail이 별도 체크박스로 표시·선택됨 |
| CSV 직접 다운로드 | ReservationID·Full Name·E-Mail·Reservation E-Mail 선택 후 csv·download 버튼 클릭. issue7-local-export.csv 생성, 267바이트. 헤더와 티켓 2행 파싱·검증 |
| Excel 직접 다운로드 | 같은 선택으로 Excel·download 버튼 클릭. issue7-local-export.xlsx 생성, 2,839바이트. XLSX ZIP의 실제 worksheet XML 파싱·검증 |
| 이메일·예약 대응 | 두 파일의 값이 동일함. 참석자 이메일 2개는 각 행에 구분되고, 예약 ID·예약자 이메일은 두 행에 동일하게 출력됨 |
| 기존 선택 유지 | Deselect all → E-Mail만 선택해 CSV 재다운로드. 헤더 E-Mail 하나와 참석자 2행만 출력, 예약자 이메일 열 없음 |

- 파일 확인: Python 표준 csv·zipfile·xml.etree로 브라우저가 Downloads에 저장한 실제 파일을 읽고 헤더·행 수·이메일·예약 대응·CSV/Excel 일치를 assert로 확인했다. 두 검사 명령 모두 종료 코드 0. 자동 통합 테스트의 결과를 브라우저 다운로드 검증으로 대체하지 않았다.
- 도구 제약·조치: 인앱 브라우저의 download 이벤트 대기는 15초 타임아웃이었다. 실제 다운로드는 Downloads에 정상 저장돼 있었으므로 파일 생성 시각·내용을 확인했다. 제품 다운로드 실패로 계산하지 않음.
- 증거: 다운로드 파일 3개는 로컬 Downloads, 화면 캡처는 /private/tmp/alfio-issue9-export-screen.png에만 보관. 저장소에는 원본 파일·로그·화면 캡처를 추가하지 않고 이 요약만 기록한다.
- 자체 리뷰·범위: 실제 버튼 클릭 → 파일 저장 → 내용 확인까지 완료. 제품·테스트 변경이 없어 기존 자동 검증을 반복하지 않았다. 프론트 단위 테스트·전체 E2E 완료 검증·원격 CI는 여전히 미실행이다.
- 처리: 이전에 승인된 로컬 커밋 범위에서 이 후속 MD 기록을 별도 문서 커밋한다. 푸시·PR 생성·노션 갱신 없음.
