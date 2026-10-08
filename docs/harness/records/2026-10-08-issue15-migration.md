# 이슈 #15 DB 마이그레이션 검증

- 작업 ID / 날짜 / 상태: issue15-migration / 2026-10-08 / completed
- 요청·완료 기준·수정 범위: [이슈 #15](https://github.com/WookJaes/alf.io/issues/15)의 이전 앱 초기화 → 합성 행사·예약·티켓 준비 → 이전 앱 종료 → 현재 코드 앱의 동일 DB 업그레이드 및 상태·데이터·관계·주요 공개 조회 검증.
- 실행 모드·실제 담당: 현재 Codex에서 순차 자체 구현·검증·리뷰. 하위 에이전트 없음.
- 브랜치·기준 HEAD·시작 시 기존 변경: chore/15-local-validation / E2E 커밋 955c3aee419108a861ff8940d976f78b3d64760e / 깨끗함.
- 재현 환경·명령 실행 기준 디렉터리: 저장소 루트, macOS 27.0.1 arm64, Java 25.0.4.1, Gradle 9.7.1, Docker Engine 29.5.2, Chrome 154.0.8037.98, Python 3.
- 검증 대상 코드·증거 위치: 해당 커밋의 migrationValidation·MigrationValidatorTest·스크립트·전용 설정. 원본 로그는 /private/tmp, 결과 XML은 build/test-results/migrationValidation. 원본 로그·덤프·JSON·비밀값·개인정보는 커밋하지 않음.
- 계획·현재 단계: 버전 선정 → 전용 업그레이드 실실행 → 관련 일반 검사 → 자체 리뷰·커밋.

## 기준 버전 선정

- 이전 공식 릴리스: [2.0-M5-2606](https://github.com/alfio-event/alf.io/releases/tag/2.0-M5-2606), 태그가 가리키는 커밋 `2b4759f9136cf5c6f5cb7784c30c9a09da217151`(annotated tag 객체와 구분).
- 최종 이전 앱: 위 고정 커밋의 원본 소스를 git archive로 임시 추출하고 원본 Wrapper 8.14.4·Java 17로 bootJar 빌드. 내부 alfio.version=2.0-M5-2606을 assert하며 빌드 JAR SHA-256을 출력하고 실행 직전 다시 검사한다. 이전 소스에는 패치·새 의존성·변경을 적용하지 않는다.
- 선정 근거: 이전 릴리스 소스의 CI가 Java 17·PostgreSQL 10/15/16을 명시한다. 현재 포크 CI는 Java 25·PostgreSQL 15/16/17을 지원 조합으로 검사하므로 PostgreSQL 16이 겹친다. 이전 릴리스의 Spring Boot 3.5.14/Flyway 11.7.2 DB를 현재 Spring Boot 4.1.1/Flyway 12.11.0 코드로 업그레이드하는 경로를 검증한다. 이전 릴리스 소스는 Java 17로 빌드하며 JAR 기동은 Java 25에서 수행한다.
- PostgreSQL: `postgres:16.13`, digest `sha256:5d143123fdf80462d1778cd4f24b9f7ca13c87174bca19141fb194c5a1ebca59`. 기존 E2E에서 실기동 확인한 정확한 마이너 버전으로 고정한다. [공식 지원 정책](https://www.postgresql.org/support/versioning/)에서 16의 지원 종료는 2028-11-09. 최신 마이너 선택 검사가 아니라 고정된 앱 업그레이드 조합 검사이며 PostgreSQL major upgrade는 하지 않는다.
- 이전 릴리스와 현재 main 간 버전 SQL/Java 마이그레이션 정의의 추가 변경은 없다. 새 제품 마이그레이션을 억지로 추가하지 않는다. 현재 정의의 validate·pending 0·기존 이력 보존과 행사 data migration COMPLETE/current_version을 별도로 검사한다.

## 변경과 검증

| 완료 기준 | 변경·확인 내용 | 명령·절차 | 종료 코드·실제 건수 | 증거·결과 |
|---|---|---|---|---|
| 고정 이전 버전 → 현재 앱 업그레이드 | 이전 릴리스의 실제 브라우저 UI로 합성 데이터를 만들고 DB snapshot·공개 API 기준 결과 확보 | `bash scripts/validation/migration.sh` | 종료 0, 실제 1건 통과·스킵 0 | 이전 JVM 종료 완료 후 동일 DB에 현재 bootJar 실행 |
| 데이터·관계·조회 보존 | 조직·행사·설명·분류·예약·티켓의 식별자·가격·상태·관계 snapshot 비교, 고아 관계 0, 공개 조회 비교 | 위 명령 | 종료 0, 실제 1건 통과·스킵 0 | 행사 1·분류 1·확정 예약 1·티켓 10(확정 1/미예약 9) |

## 실패·리뷰·재개

- 준비 실패 요약: 최초 스크립트 종료 1, 실제 테스트 0건. Python 3.9에는 hashlib.file_digest가 없어 해시 검사가 실패.
- 원인: Python 3.11에서 추가된 함수를 로컬 Python 3.9에 사용한 호환성 오류.
- 조치: 표준 sha256·청크 읽기로 교체. 별도 Python 의존성·업그레이드 없음.
- 재검증 결과: 후속 실행으로 확인.
- 자체 리뷰: 앱이 DataMigrator 오류를 로그에만 남길 수 있어 healthz 외에 오류 로그·event_migration 상태도 검사한다. 이전 앱과 현재 앱 동시 실행을 허용하지 않는다. 테스트용 임시 이전 소스·JAR은 shell EXIT trap으로 성공·실패 모두 제거한다.
- 생략·제외: 모든 과거 버전/DB 조합·PostgreSQL major upgrade·유료/외부 결제·실제 메일·운영 DB 변경·배포·프론트 단위 검사·하네스 자동 복구·모든 PR 필수 적용 없음. GitHub Actions 실제 원격 실행은 푸시 금지로 미검증.

### 구현 중 검사 오류·재검증

| 실패 요약 | 원인 | 조치 | 재검증 결과 |
|---|---|---|---|
| 공개 기준 조회 검사 1건 실패·종료 1 | organization 내부 필드가 API JSON에 그대로 노출된다고 가정했으나 DTO의 실제 getter는 organizationName/organizationEmail | 실제 DTO 필드로 비교하고 행사 slug·예약 ID·COMPLETE 기준도 명시적으로 assert | 이전 앱 기준 조회 통과 후 현재 앱 기동·snapshot 보존 비교 통과 |
| 관계 검사 1건 실패·종료 1, 예상 0/실제 9 | 아직 예약되지 않은 FREE 티켓 9장은 category_id가 null일 수 있는데 이를 고아 관계로 계산 | nullable 관계는 값이 있을 때 참조·행사 일치 검사. 예약 관계도 동일하게 null과 끊긴 참조 구분 | 다음 실실행에서 확인 |

- 검사 오류를 제품 데이터 손상으로 보고하지 않는다. 수정 후 동일한 이전 릴리스·현재 코드 경로 전체를 새 전용 DB에서 다시 실행한다.

### 이전 앱 대상의 최종 대조·기준 변경

- 공식 release asset ID 436384737의 `alfio-2.0-M5-2606-boot.jar`(SHA-256 aea1186fd496c3197f72ae5952cef776bb67cc07d3306576c1a6a515d560011c)로 업그레이드 검사 자체는 종료 0, 실제 1건 통과·스킵 0. Flyway 이력 179/179, pending/failed/orphan 0, event_migration COMPLETE, DB·공개 조회 보존 확인.
- 추가 대상 확인에서 해당 공식 배포 JAR의 내부 `alfio.version=2.0-M6-SNAPSHOT`, build-ts=2026-05-31로 확인. 태그 커밋의 gradle.properties는 2.0-M5-2606이므로 태그의 정확한 소스에서 빌드됐다고 주장할 수 없음. 이 결과를 최종 이전 커밋 검증 완료로 계산하지 않음.
- 조치: 최종 실행은 공식 태그가 가리키는 정확한 커밋의 원본 소스를 Java 17·원본 Gradle Wrapper로 직접 빌드하는 방식으로 변경. 로컬/CI 모두 같은 경로 사용. JAR 내부 버전도 assert.
- 재검증 결과: 후속 소스 기반 실행 결과로 기록.

### 최종 소스 기반 로컬 업그레이드 결과

- 명령: `bash scripts/validation/migration.sh`, 종료 0. 이전 소스 bootJar 빌드 종료 0(1분 21초), 현재 앱 전용 검사 Gradle 종료 0(30초). 실제 MigrationValidatorTest 1건 통과·실패 0·오류 0·스킵 0. 2026-10-08 18:46 KST 실행.
- 이전 소스: 커밋 2b4759f9136cf5c6f5cb7784c30c9a09da217151, 원본 Gradle 8.14.4, 설치된 Java 17.0.20, 내부 alfio.version=2.0-M5-2606 assert 통과. 이전 JAR SHA-256 `7bb68e808fe5919cad862eb4e905f803398caf72a83c088bc90223285df9c50f`. 이전 JAR 빌드는 임시 디렉터리에서 매번 수행하며 빌드 시각 때문에 후속 JAR 해시는 달라질 수 있다. 기준 소스 SHA는 고정한다.
- 현재 대상: 955c3aee4 기준에 이 커밋의 변경을 더한 bootJar, 내부 버전 2.0-M6-SNAPSHOT, SHA-256 `319875b948963b9a8fc38af25507aa343aeaffe81efc2a501770f89727d6f72f`. 현재 앱은 포크 작업 트리에서 빌드되며 외부 앱 이미지로 대체하지 않음. 최종 현재 bootJar는 변경 없는 입력의 UP-TO-DATE 재사용, 전용 테스트는 새 DB로 실제 실행.
- 실제 DB 버전: `16.13 (Debian 16.13-1.pgdg13+1)`. 기존 버전 마이그레이션 이력 179 → 179, failed 0, pending 0, 현재 Flyway validate 성공. 새 버전 마이그레이션 정의가 없어 새 적용 0건이며 이 수를 테스트 건수로 계산하지 않음.
- 행사 data migration: current_version=2.0-M6-SNAPSHOT, status=COMPLETE. 앱이 오류를 삼킨 로그 없음. 이전 앱 종료를 기다린 뒤 현재 앱 시작.
- 보존: 조직·행사 1·분류 1·확정 예약 1·티켓 10(ACQUIRED 1/FREE 9), 기존 식별자·가격·참석자·상태·참조 snapshot 일치, 고아 관계 0. 공개 행사·분류·기존 확정 예약 GET 응답 200 및 주요 결과 비교 일치.
- 결과 검사 회귀 6건·하네스 회귀 14건·하네스 구조 검사 종료 0. 스크립트 bash -n·diff check 통과.
- 미검증: 원격 CI/Linux 실실행, 다른 DB/이전 버전 조합, 유료 결제·외부 메일 전달·프론트 단위 검사. 모두 범위·환경 제약을 명시하며 통과로 주장하지 않음.

### 최종 공통 재검증·자체 리뷰·정리

- 공통 도우미 변경 후 `bash scripts/validation/e2e.sh` 종료 0(24초), 실제 1건 통과·실패/오류/스킵 0. 2026-10-08 18:48 KST. 현재 코드 bootJar를 사용한 관리자 생성·게시·공개 예약·확정·DB 비교 재확인.
- `./gradlew check jacocoTestReport -Dpgsql.version=16 --no-daemon --no-build-cache` 종료 0(2분 4초). test·forbiddenApisTest·jacocoTestReport 실제 실행. 일반 검사 XML 872건 집계, 실제 통과 870·실패 0·오류 0·스킵 2. opt-in E2E와 이동한 MigrationValidatorTest의 스킵을 통과로 계산하지 않음. 일반 PostgreSQL major 16 검사이며 전용 검사의 정확한 DB 버전은 위 16.13.
- 라이선스: `./gradlew license --rerun-tasks --no-daemon` 및 `./gradlew licenseTest --rerun-tasks --info --no-daemon` 종료 0. 플러그인은 Header OK여도 UP-TO-DATE로 표시하지만 info 로그에서 새 MigrationValidatorTest·ValidationEnvironment·NormalFlowE2ETest에 실제 Header OK 검사 확인. config/HEADER와 변경 테스트 Java 3개의 주석 내용을 표준 Python으로 대조해 일치 확인(제품 테스트 건수에 포함하지 않음).
- 선정 버전 재확인: 이전 소스의 Boot 3.5.14 BOM에서 Flyway 11.7.2 확인. 원본 릴리스 아티팩트의 잘못된 내부 버전 가정을 최종 소스 빌드·버전 assert로 해소.
- 자체 리뷰: 현재 diff·이슈 완료/제외 조건·두 앱 실행 순서·데이터 snapshot·nullable 관계·API DTO·실패/0건 전달·전용 자원 소유·CI 수동 실행·JDK 17/25 분리·비밀값 및 원본 로그 제외 확인. 테스트 통과 후 코드·실행 설정 변경 없음. 미검증 원격 CI와 제외 조합을 완료된 로컬 검증과 분리.
- 정리 확인: 이번 앱 JVM·ChromeDriver·crash handler·컨테이너 잔존 0, alfio-validation 임시 디렉터리·alfio-previous 임시 소스/빌드 디렉터리 0. 조사용 배포 JAR·오류 DOM·원본 임시 로그는 요약 완료 후 삭제하며 무시되는 build의 JUnit/HTML 결과·도구 캐시만 보관. 사용자 데이터·기존 DB·프로세스·컨테이너 제거 없음.
- 환경 복원: 시작 시 Docker daemon이 정지해 있어 이번 작업에서 Docker Desktop을 시작했으며, 최종 실행 컨테이너 0개 확인 후 Docker Desktop을 다시 종료했다. 기존 컨테이너·볼륨·도구 캐시는 삭제하지 않았다.
- 최종 상태: 구현과 실제 로컬 검증 완료. 승인 범위의 마이그레이션 커밋만 수행하며 푸시·PR·외부 댓글·노션 수정 없음.
