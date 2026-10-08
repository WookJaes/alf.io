# 체크인 시간 의존성 최신 main 재현

- 작업 ID / 날짜 / 상태: 체크인 시간 재현 / 2026-10-08 / completed (최신 main 실패 재현 완료, 수정 미수행)
- 요청·범위: 최신 main에서 기존 중복 체크인 실패의 시간대 의존성을 재현하고 이슈 초안의 근거를 갱신한다. 제품 수정·커밋·푸시·이슈 생성·노션 수정 없음.
- 실행 모드: 현재 Codex 순차 실행·진단·자체 대조. 별도 에이전트 없음.
- 브랜치·검증 코드: main / 7e67079d9941bf366d6574df428eec7c01e97a79. 원격 최신 확인, 시작 작업 트리 깨끗함.
- 환경: 저장소 루트, Corretto Java 25.0.4.1·Gradle 9.7.1·Docker 29.5.2·Testcontainers PostgreSQL 17. 기존 로컬 행사 DB·브라우저 서버는 사용하지 않았다.
- 검증 대상: 동일 최신 main의 예약 API 통합 테스트 53건. test --rerun으로 각 조건에서 실제 실행했으며 UP-TO-DATE를 통과로 계산하지 않는다.

## 실행 결과

| 실행 시간대 | 실제 건수 | 통과 | 실패 | 스킵 | 종료 코드 |
|---|---:|---:|---:|---:|---:|
| Asia/Seoul | 53 | 53 | 0 | 0 | 0 |
| UTC | 53 | 53 | 0 | 0 | 0 |
| Pacific/Honolulu | 53 | 41 | 12 | 0 | 1 |

```sh
TZ=Asia/Seoul ./gradlew test --rerun -Dpgsql.version=17 --tests 'alfio.controller.api.v2.user.reservation.*IntegrationTest' -x frontendBuild -x frontendPnpmInstall -x frontendAdminPnpmInstall --no-daemon
TZ=UTC ./gradlew test --rerun -Dpgsql.version=17 --tests 'alfio.controller.api.v2.user.reservation.*IntegrationTest' -x frontendBuild -x frontendPnpmInstall -x frontendAdminPnpmInstall --no-daemon
TZ=Pacific/Honolulu ./gradlew test --rerun -Dpgsql.version=17 --tests 'alfio.controller.api.v2.user.reservation.*IntegrationTest' -x frontendBuild -x frontendPnpmInstall -x frontendAdminPnpmInstall --no-daemon
```

- 실행 중 날짜 확인: UTC 2026-10-08 07:23, Pacific/Honolulu 2026-10-07 21:23. 한국·UTC는 같은 날짜였고 Honolulu는 하루 전이었다. 날짜 경계가 바뀌면 같은 시간대에서 항상 실패한다고 주장하지 않는다.
- 실패 12건 모두 BaseReservationFlowTest의 동일 중복 스캔 검사에서 BADGE_SCAN_ALREADY_DONE을 기대했으나 BADGE_SCAN_SUCCESS를 반환했다. 일반·하이브리드·사용자 인증·할인·세금·Stripe 모의 응답·사용자 정의 결제·재시도·메타데이터 예약 흐름에 걸쳐 동일 증상이다. 합성 테스트 데이터와 Stripe 모의 응답을 사용했다.
- 현재 시각의 Asia/Seoul·UTC 통과는 문제 수정의 근거가 아니다. 현지 날짜가 다른 조건에서 동일 코드가 실패해 최신 main에서도 시간대·날짜 경계 의존성을 확인했다.
- 앞선 전체 테스트 856건 중 실패 12건은 과거 결과다. 이번에는 관련 묶음 53건의 결과로 구분한다. 시간대별 반복 실행을 고유 테스트 159건으로 합산하지 않는다.

## 원인 근거·리뷰·다음 행동

- TestUtil: UTC 오늘 날짜의 10시로 시계를 고정하고 Europe/Zurich 시간대를 부여한다.
- CheckInManager: scanAudit는 ClockProvider의 고정 시각을 쓰지만 auditing의 CHECK_IN·BADGE_SCAN 등은 실제 new Date()를 사용한다.
- AuditingRepository: 기준 시각과 저장된 event_time을 timestamp의 날짜로 비교한다. 시계 혼용과 시간대에 따른 날짜 변환을 수정 시 함께 확인해야 한다.
- 자체 리뷰: 동일 커밋·테스트 선택·PostgreSQL 버전에서 실행 시간대만 변경했다. 종료 코드·JUnit 건수·실패 지점·기대/실제 상태를 대조했다. 현재 main의 미해결 재현이라는 결론과 범위를 확인했다.
- 제약: 이번에는 제품 수정·자정 경계의 결정적인 신규 회귀 테스트 구현·전체 제품 재검증을 수행하지 않았다. 다음 작업에서 같은 날 중복 금지·다음 행사 날짜 허용과 자정 경계를 고정 시각으로 검증해야 한다. 화면 문제가 아니므로 브라우저·스크린샷 검증은 수행하지 않았다.
- 종료 상태: 세 Gradle 테스트 실행 종료. 로컬 앱 서버·프론트 개발 서버를 시작하지 않았다. Docker는 시작 전 실행 중이던 사용자 환경을 유지했다.
- 데이터·기록: 비밀값·실제 고객 데이터·원본 로그·결과 JSON을 저장소에 추가하지 않았다. 요약 MD만 추가했으며 커밋·푸시·이슈 등록·노션 수정 없음.
