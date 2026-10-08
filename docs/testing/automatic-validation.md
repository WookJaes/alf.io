# 로컬·포크 자동 검증

저장소 루트에서 실행한다. Java 25, Docker Engine(실행 중), Python 3, 인터넷 접근이 필요하다. E2E는 설치된 Chrome과 Selenium Manager가 준비한 호환 드라이버를 사용한다. CI의 ubuntu-latest에도 Chrome이 설치되어 있어야 한다. 새 라이브러리는 추가하지 않는다.

## 자동 E2E

```sh
bash scripts/validation/e2e.sh
```

현재 작업 트리의 `bootJar`를 빌드하고 `e2eValidation`을 실행한다. 테스트마다 전용 PostgreSQL 16.13 컨테이너·임시 디렉터리·별도 앱 JVM·헤드리스 Chrome 세션을 만든다. 앱 포트와 DB 포트는 실행별로 할당한다. 기존 DB·앱·Chrome 사용자 프로필을 사용하지 않는다. 앱은 dev·disable-jobs 프로필로 실행한다. 합성 관리자와 조직을 전용 DB에 준비하고 실제 브라우저로 무료 온라인 행사 생성·게시 → 공개 티켓 1장 선택 → 합성 예약자·참석자 입력 → 약관 동의·확정을 검증한다. 최종 화면 및 DB의 COMPLETE 예약·ACQUIRED 티켓·참석자 필드를 확인한다.

현재 템플릿의 관리자 `displayName`, `format`, `organizationId`, `freeOfCharge`, `actions-dpdwn`과 공개 `amount`, `show-event-continue`, `first-name`, `reservation-page.title`, `app-success`를 사용한다. DOM 존재·클릭 가능·URL 및 표시 상태를 단계별로 기다린다. 화면 변경으로 선택자나 대기 조건이 맞지 않으면 실패한다.

무료 행사로 Stripe·실제 결제·유료 청구 검증을 대체한다. 온라인 행사로 외부 지도·지오코딩 호출을 제외한다. `MAILER_TYPE=disabled` 및 disable-jobs로 외부 메일·정기 작업을 막는다. 실제 결제·메일 전달 및 기존 커스텀 메시지 발송은 검증하지 않는다. 데모 프로필은 행사 게시를 금지하므로 사용하지 않는다.

GitHub Actions → **Local E2E validation** → **Run workflow**에서 검증할 브랜치를 선택한다. 외부 서버·BrowserStack 비밀 설정과 저장소 이름 제한이 없다. 수동 실행만 제공하며 일반 PR 필수 검사로 추가하지 않는다.

## DB 마이그레이션

```sh
bash scripts/validation/migration.sh
```

이전 공식 릴리스 [2.0-M5-2606](https://github.com/alfio-event/alf.io/releases/tag/2.0-M5-2606)의 커밋 `2b4759f9136cf5c6f5cb7784c30c9a09da217151`을 git archive로 임시 디렉터리에 추출한다. 로컬에 커밋 객체가 없으면 해당 SHA만 upstream에서 fetch한다. 작업 브랜치·사용자 파일은 변경하지 않는다. **원본 이전 소스·그 소스의 Gradle Wrapper를 Java 17로 직접 빌드**하고 `alfio.version=2.0-M5-2606`을 확인한다. 빌드 JAR의 SHA-256을 출력하고 실행 직전 재확인한다. 현재 코드는 현재 Wrapper·Java 25로 별도 빌드한다.

Java 17 경로를 `JAVA17_HOME`으로 지정한다. macOS에서는 설치된 Java 17을 `/usr/libexec/java_home -v 17`로 자동 발견하며 설치 작업은 하지 않는다. CI는 Java 17·25를 setup-java로 각각 준비한다. Python 3.8 이상·git·tar가 필요하며 추가 Python 패키지를 사용하지 않는다. 이전 소스와 빌드 산출물은 실행 종료 시 삭제하지만 Gradle/npm 도구 캐시는 남겨 재사용한다.

공식 릴리스의 배포 JAR은 조사 중 내부 버전이 태그와 다른 2.0-M6-SNAPSHOT으로 확인돼 최종 기준에서 제외했다. 배포 파일 이름만으로 이전 코드의 정확한 버전을 주장하지 않는다.

이전 릴리스 앱의 Flyway 11.7.2가 전용 PostgreSQL 16.13 DB를 초기화한다. 같은 헤드리스 Chrome UI로 합성 관리자 행사 생성·게시·무료 예약·확정을 수행한 후 조직·행사·설명·분류·예약·티켓의 주요 필드 snapshot과 공개 행사/분류/예약 API 조회 결과를 메모리에 보관한다. 기존 데이터를 현재 앱의 모델로 만들어 이전 버전 검증이라고 주장하지 않는다.

이전 앱의 종료 완료를 확인한 뒤 **현재 작업 트리의 bootJar**(Spring Boot 4.1.1/Flyway 12.11.0)로 같은 DB를 업그레이드한다. 행 수만 비교하지 않고 식별자·참석자·상태·가격·행사-분류-예약-티켓 관계를 비교하며 고아 관계 0을 검사한다. Flyway 기존 버전 이력·체크섬·success 보존, 현재 정의 validate 성공, pending/failed 0 및 행사 data migration의 현재 버전/COMPLETE를 확인한다. 앱이 data migration 오류를 healthz 성공 뒤 로그에만 남겨도 실패로 처리한다. 공개 행사·티켓 분류·기존 확정 예약의 실제 HTTP 조회 결과도 비교한다.

선정 근거: 이전 릴리스 CI의 PostgreSQL 10/15/16과 현재 포크 CI의 15/16/17이 겹치는 지원 major 16을 사용한다. 공식 PostgreSQL 16 지원은 [2028-11-09까지](https://www.postgresql.org/support/versioning/)다. 16.13은 E2E 실기동을 확인한 고정 마이너이며 최신 마이너 여부를 검사하는 작업은 아니다. 두 버전 간 새 버전 SQL/Java 마이그레이션은 없어 새 적용 수가 0일 수 있다. 그렇더라도 기존 DB의 현재 Flyway 검증·행사 data migration·보존 검사는 실제 수행한다. 자세한 실행 근거는 [작업 기록](../harness/records/2026-10-08-issue15-migration.md)에 있다.

GitHub Actions → **Local DB migration validation** → **Run workflow**에서 검증할 브랜치를 선택한다. 현재 코드 체크아웃을 빌드하며 외부 latest 앱 이미지·레지스트리 로그인·기존 DB 비밀값을 사용하지 않는다. 전용 JUnit class는 `alfio.e2e.MigrationValidatorTest`로 이동했다. 이전 소스·JAR·앱·DB·브라우저는 성공·실패 후 정리한다. 유료 결제·외부 메일·모든 과거 버전 조합·PostgreSQL major upgrade는 범위 밖이다.

## 실행 결과·정리

전용 테스트 task는 UP-TO-DATE로 생략되지 않으며 `--no-build-cache`를 사용한다. 앱 빌드의 변경 없는 입력은 재사용할 수 있지만 테스트 실행으로 계산하지 않는다. 실행 전 해당 task의 이전 XML만 제거한다. 종료 코드와 새 XML의 tests/failures/errors/skipped를 확인하고 테스트 0건·스킵이 하나라도 있으면 실패한다. Gradle·앱 준비·브라우저·DB 검사 실패는 비정상 종료로 전달된다.

테스트의 finally/AutoCloseable로 브라우저·앱·컨테이너·임시 앱 로그를 정리하고 Testcontainers Ryuk도 비정상 JVM 종료의 컨테이너 정리를 지원한다. 강제 OS 종료 등으로 정리 완료를 확인할 수 없으면 해당 실행을 성공으로 보고하지 않는다. 기존 사용자 컨테이너와 DB는 제거하지 않는다.

결과는 무시되는 `build/test-results/<task>`와 `build/reports/tests/<task>`에 남는다. 준비 실패 로그와 E2E 오류 DOM은 진단용으로 `build/validation`에만 남고 커밋하지 않는다. 이 원본에는 테스트용 비밀값이 포함될 수 있으므로 외부 게시하지 않는다. 원본 결과·JSON·로그·덤프·실제 개인정보는 커밋하지 않고 작업 MD에는 버전·명령·종료 코드·실제 건수·실패/원인/조치/재검증 요약만 기록한다.

기존 일반 제품 검사는 `./gradlew build distribution jacocoTestReport -Dpgsql.version=16 --no-daemon`으로 실행한다. opt-in E2E·마이그레이션의 스킵은 제품 테스트 통과 건수에서 제외한다. 프론트 단위 테스트 환경·하네스 자동 판단/오류 복구는 변경하지 않는다.
