# 체크인 시계·행사 날짜 판정 일관성 수정

- 작업 ID / 날짜 / 상태: #13 / 2026-10-08 / completed
- 범위·완료 기준: 체크인 감사 기록에 ClockProvider 적용, 행사 날짜의 시작·종료 범위로 같은 날 스캔 판정. 시간대별 동일 결과·자정 경계·다음 날짜 허용을 회귀 검증한다.
- 실행 모드: 현재 Codex 순차 구현·검증·자체 리뷰. 별도 에이전트 없음.
- 브랜치·기준: fix/13-checkin-time-consistency / 7e67079d9941bf366d6574df428eec7c01e97a79. 기존 재현 MD를 보존하고 먼저 eeab89379로 커밋했다. 수정 검증 대상은 그 이후 제품 코드·회귀 테스트 diff다.
- 환경·명령 기준: 저장소 루트, macOS arm64·Corretto Java 25.0.4.1·Gradle 9.7.1·Docker 29.5.2·Testcontainers PostgreSQL 17.
- 계획·최종 단계: 감사 기록 시계·행사 날짜 조회 수정 → 고정 시각 회귀 테스트 → Honolulu·한국 시간대 관련 검사 → UTC 전체 회귀·빌드 → 기록·자체 리뷰·커밋 준비 완료.

## 변경과 검증

두 원인을 함께 수정했다. CheckInManager의 온라인·일반·뱃지·수동 체크인·취소 감사 기록에서 new Date() 대신 ClockProvider로 생성한 시각을 사용한다. 같은 동작의 scanAudit와 auditing은 한 번 읽은 동일 시각을 사용한다.

AuditingRepository의 날짜 절삭 비교를 행사 시간대의 당일 자정 이상·다음 날 자정 미만 범위로 바꿨다. 다음 날짜의 자정을 별도로 계산하므로 일광절약시간 전환일의 23/25시간에도 맞는다. 감사 기록의 기존 Date·timestamp 저장 형식과 같은 Date 매개변수로 조회 경계를 바인딩한다. HTTP API·DB 스키마·프론트 코드·의존성 변경과 기존 데이터 재작성은 없다.

| 완료 기준 | 변경·확인 | 실행 결과 |
|---|---|---|
| 시계 혼용 제거 | 체크인 감사 기록 5개 경로를 ClockProvider로 통일 | 기존 예약·체크인 통합 흐름 및 수동·취소 고정 시각 검사 통과 |
| 행사 날짜 경계 | UTC·Seoul·Honolulu·Zurich의 날짜 경계 및 Zurich 일광절약시간 시작/종료일 | 새 통합 회귀 6건, 당일 자정 포함·이전 순간 제외·다음 자정 제외·다음 날짜 포함·무관한 감사 유형 제외 확인 |
| 두 감사 기록의 동일 시각 | 실제 현재 시각과 다른 2024-01-01T15:00:00Z 고정 시계로 수동 체크인·취소 | 새 단위 회귀 2건, scanAudit와 auditing의 시각 일치 |
| 기존 실패 조건 해소 | PostgreSQL 17·Pacific/Honolulu | 관련 64건 통과·실패 0·스킵 0, 종료 0 |
| 한국 시간대 결과 | PostgreSQL 17·Asia/Seoul | 같은 관련 64건 통과·실패 0·스킵 0, 종료 0 |
| UTC 결과·전체 회귀 | PostgreSQL 17·UTC 전체 제품 검사 | 872건 중 870건 통과·실패 0·스킵 2, 종료 0. 관련 64건 포함 |
| 빌드·품질·문서 | 전체 CI 명령·하네스 구조·공백 확인 | build·distribution·jacocoTestReport 성공. 금지 API 검사 실제 실행·통과. 하네스 구조 및 git diff --check 종료 0 |

관련 테스트(각 시간대에서 실제 강제 실행):

```sh
TZ=Pacific/Honolulu ./gradlew test --rerun -Dpgsql.version=17 --tests 'alfio.repository.AuditingRepositoryIntegrationTest' --tests 'alfio.manager.CheckInManagerTest' --tests 'alfio.manager.CheckInManagerIntegrationTest' --tests 'alfio.controller.api.v2.user.reservation.*IntegrationTest' -x frontendBuild -x frontendPnpmInstall -x frontendAdminPnpmInstall --no-daemon
TZ=Asia/Seoul ./gradlew test --rerun -Dpgsql.version=17 --tests 'alfio.repository.AuditingRepositoryIntegrationTest' --tests 'alfio.manager.CheckInManagerTest' --tests 'alfio.manager.CheckInManagerIntegrationTest' --tests 'alfio.controller.api.v2.user.reservation.*IntegrationTest' -x frontendBuild -x frontendPnpmInstall -x frontendAdminPnpmInstall --no-daemon
```

전체 제품 검증:

```sh
TZ=UTC ./gradlew build distribution jacocoTestReport -Dpgsql.version=17 --no-daemon
python3 -B ai/tests/check.py
git diff --check
```

- 관련 64건은 기존 예약 API 53건·기존 체크인 통합 1건·체크인 단위 4건(기존 2·신규 2)·신규 감사 조회 통합 6건이다. 신규 테스트는 총 8건이며 시간대별 반복 실행·전체 검사와 중복 합산하지 않는다.
- 수정 전 동일 main 제품 코드의 Honolulu 관련 53건은 통과 41·실패 12였다. 수정 후 같은 기존 53건이 모두 통과했고 새 회귀도 통과했다. 이전 실제 실패 재현과 고정 시각·날짜 경계 검증을 함께 근거로 사용한다.
- 전체 명령에서 test·build·distribution·jacocoTestReport 실제 실행을 확인했다. 프론트 production 빌드는 UP-TO-DATE였으므로 새 프론트 빌드 성공으로 계산하지 않는다. 배포 패키지 생성은 성공했지만 외부 배포는 수행하지 않았다.

## 실패·리뷰·재개

- 테스트 작성 실패: 새 단위 테스트의 PaymentProxy·ScanAudit·ScanAuditRepository import 경로를 잘못 지정해 첫 compileTestJava가 실패했다. 실제 패키지로 고친 뒤 컴파일·관련 64건·전체 회귀를 통과했다. 이 최초 실행은 테스트 0건이며 제품 테스트 실패로 집계하지 않는다. 원본 로그는 저장소에 추가하지 않는다.
- 제품 재검증: 수정 후 테스트 실패 없음. 테스트 기대 상태·기존 행사 정책·실행 시간대를 완화하지 않았다. Honolulu와 한국 시간대에서도 통과했으므로 UTC 강제로 우회한 결과가 아니다.
- 자체 리뷰: 감사 기록 시계가 누락 없이 변경됐는지, 날짜 범위가 시작 포함·끝 제외인지, 다음 날짜와 일광절약시간에 맞는지, 기존 예약·감사 유형 필터와 저장 형식을 유지하는지 확인했다. 제품 검증 후 코드 변경 없음.
- 미실행·제약: PostgreSQL 15/16은 이번 로컬 검증에서 미실행. MigrationValidatorTest·NormalFlowE2ETest는 전용 환경 조건으로 전체 검사에서 2건 스킵됐다. 화면 문제가 아니므로 브라우저·실제 결제·외부 메일 검증은 수행하지 않았다. Testcontainers의 합성 데이터만 사용했다.
- 데이터 검토: 새 코드·기록에 실제 개인정보·비밀번호·API 키·토큰·원본 로그·결과 JSON을 추가하지 않았다.
- 종료 상태: 관련·전체 테스트 종료. 로컬 앱·프론트 서버를 시작하지 않았다. Docker는 기존 사용자 실행 환경을 유지했다.
- 최종 결과·다음 행동: 수정 코드·회귀 테스트·요약 기록을 사용자 요청에 따라 커밋한다. 푸시·PR 생성·노션 수정은 수행하지 않는다.
