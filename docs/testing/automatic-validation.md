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

## 실행 결과·정리

전용 테스트 task는 UP-TO-DATE로 생략되지 않으며 `--no-build-cache`를 사용한다. 앱 빌드의 변경 없는 입력은 재사용할 수 있지만 테스트 실행으로 계산하지 않는다. 실행 전 해당 task의 이전 XML만 제거한다. 종료 코드와 새 XML의 tests/failures/errors/skipped를 확인하고 테스트 0건·스킵이 하나라도 있으면 실패한다. Gradle·앱 준비·브라우저·DB 검사 실패는 비정상 종료로 전달된다.

테스트의 finally/AutoCloseable로 브라우저·앱·컨테이너·임시 앱 로그를 정리하고 Testcontainers Ryuk도 비정상 JVM 종료의 컨테이너 정리를 지원한다. 강제 OS 종료 등으로 정리 완료를 확인할 수 없으면 해당 실행을 성공으로 보고하지 않는다. 기존 사용자 컨테이너와 DB는 제거하지 않는다.

결과는 무시되는 `build/test-results/<task>`와 `build/reports/tests/<task>`에 남는다. 준비 실패 로그와 E2E 오류 DOM은 진단용으로 `build/validation`에만 남고 커밋하지 않는다. 이 원본에는 테스트용 비밀값이 포함될 수 있으므로 외부 게시하지 않는다. 원본 결과·JSON·로그·덤프·실제 개인정보는 커밋하지 않고 작업 MD에는 버전·명령·종료 코드·실제 건수·실패/원인/조치/재검증 요약만 기록한다.

기존 일반 제품 검사는 `./gradlew build distribution jacocoTestReport -Dpgsql.version=16 --no-daemon`으로 실행한다. opt-in E2E·마이그레이션의 스킵은 제품 테스트 통과 건수에서 제외한다. 프론트 단위 테스트 환경·하네스 자동 판단/오류 복구는 변경하지 않는다.
