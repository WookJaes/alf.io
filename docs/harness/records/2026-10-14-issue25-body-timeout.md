# 이슈 25 AI 응답 본문 시간 제한 수정

- 작업 ID / 날짜 / 상태: issue25-body-timeout / 2026-10-14 / completed
- 요청: PR #31 리뷰의 본문 시간 제한 문제를 수정·검증하고 커밋까지만 한다. 푸시·외부 댓글·PR 본문 수정은 하지 않는다.
- 브랜치·기준 HEAD·초기 상태: feat/25-attendee-assistant, 73f3bae60d8245013c131a9c9efcbb6806df9d2b, 작업 트리 변경 없음.
- 실행 모드·담당: 현재 Codex 자체 구현·검증·Review, 하위 에이전트 없음.
- 환경·실행 위치: macOS, Java 25, Gradle Wrapper 9.7.1, Docker PostgreSQL 16.13·Chrome, 저장소 루트.
- 완료 기준: 응답 본문까지 전체 HTTP 기한, 시간 초과 시 HTTP future·구독 취소, 504/AI_TIMEOUT, 비교 보존·DB 잠금 해제·재시도 확인.
- 로컬 실행 ID·선언: issue25-body-timeout-final, ai/local-state/issue25-body-timeout/plan.json.

## 변경

동기 send 대신 sendAsync의 전체 응답 완료 future를 요청 시작 시각 기준 남은 시간 안에 기다린다. 시간 초과·스레드 중단·수신 오류에서는 요청 future와 본문 구독을 취소한다. 늦게 도착한 구독도 취소하며 본문 콜백과 취소를 동기화한다. 본문 크기 64KiB 제한·개인정보 최소 입력·안전한 오류 매핑은 유지한다. 새 의존성·DB/API 변경 없음.

회귀는 헤더·첫 바이트를 먼저 보내고 나머지 본문을 지연하는 합성 서버, 완료되지 않는 요청과 늦은 구독의 취소를 검증한다. 실제 화면 E2E에 본문 지연 실패를 추가하고 결과 재조회·DB 잠금 null·후속 정상 생성까지 확인한다. 외부 OpenAI·실제 키는 사용하지 않는다.

## 검증

- 계획: 관련 Java 회귀·실제 앱/격리 DB/브라우저 명단 E2E와 자체 Review.
- 증거: ai/local-state/issue25-body-timeout/의 실행별 JUnit XML 및 자체 Review.
- 커밋 범위: 수정 소스·회귀/E2E·이 작업 기록. 원격 푸시는 수행하지 않는다.

## 이번 실행 결과

- 회귀: `./gradlew test --tests 'alfio.manager.attendee.*Test' --tests alfio.controller.api.admin.AttendeeReconciliationApiControllerTest --console=plain --no-daemon --no-build-cache -Dpgsql.version=16.13 -I ai/local-state/issue25-body-timeout/reports.gradle -Dharness.reports=ai/local-state/issue25-body-timeout/regression-xml` 종료0, 실제42건·실패0·오류0·스킵0.
- 실제 화면 E2E: `./gradlew e2eValidation --tests alfio.e2e.AttendeeReconciliationE2ETest --console=plain --no-daemon --no-build-cache -Dpgsql.version=16.13 -I ai/local-state/issue25-body-timeout/reports.gradle -Dharness.reports=ai/local-state/issue25-body-timeout/e2e-xml` 종료0, 실제1건·실패0·오류0·스킵0.
- 합계 실제43건. `:test`·`:e2eValidation` 모두 실제 실행. 명령 시작/종료 시각과 XML 체크섬은 Git 제외 로컬 하네스 영수증에 연결했다.
- 본문 지연 회귀: 헤더·첫 바이트 먼저 전송, 나머지 본문2500ms 지연, 제한500ms. JUnit 해당 테스트 소요507ms, AI_TIMEOUT/504 및 원본 오류 원인 미노출 확인.
- 자원 해제 회귀: 완료되지 않는 HTTP future 취소 및 현재/늦은 본문 subscription cancel 직접 확인.
- 실제 앱/DB/화면: 본문3500ms 지연·서버 제한1초 조건에서 timeout 안내, 저장 비교 GET200, DB ai_locked_until null, 후속 정상 초안 생성 성공.
- 관련 실패·권한·크기·잘못된 AI 응답·암호화·호출 제한 회귀도 통과. 전체 제품 테스트를 수행했다고 확대하지 않는다.
- 라이선스 검사: licenseMain/licenseTest를 --rerun-tasks로 실행해 종료0. 플러그인은 성공 결과 줄을 UP-TO-DATE로 표시하므로 별도 테스트 건수로 집계하지 않는다.
- 자체 Review: 전체 기한·구독 취소 경쟁·예외 매핑·회귀/E2E 근거 대조, 차단 문제 없음. 근거 ai/local-state/issue25-body-timeout/review.md.
- 비밀정보·공백 검사: 신규 diff 비밀키 패턴 미검출, git diff --check 종료0.
- 미실행: 외부 OpenAI 호출·원격 CI·Windows 재실행. 테스트는 합성 키·합성 명단·로컬 모의 서버·격리 DB만 사용한다.
- 검증 후 제품 변경: 없음. 결과 문서만 갱신한다.
- gate: python3 -B ai/harness/run.py gate issue25-body-timeout-final 종료0, 회귀·E2E 실제 실행 및 자체 Review 모두 통과.
- 커밋: 사용자 승인 범위의 위 3개 소스/테스트와 이 기록만 포함한다. 푸시·PR 본문 변경·외부 댓글 등록은 수행하지 않는다.
