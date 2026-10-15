# PR #32 리뷰 수정 기록

- 작업 ID / 날짜 / 상태: pr32-review-fixes / 2026-10-15 / completed（두 리뷰 지적의 수정·로컬 검증 범위）. #26 전체 및 Docker 부하 검증 완료를 뜻하지 않는다.
- 요청: 최신 리뷰의 수정 사항을 반영·검증하고 기존 스레드에 답한 뒤 실제 해결된 대화만 Resolve. PR 본문은 보존한다.
- 범위: [오류 판정](https://github.com/WookJaes/alf.io/pull/32#discussion_r4279391265), [PostgreSQL 정리](https://github.com/WookJaes/alf.io/pull/32#discussion_r4279391270). 제품 예약 API·정책·DB schema 변경 없음.
- 실제 개발 checkout: `alfio-issue26-dev`, 브랜치 `codex/issue-26-reservation-load`, 시작 HEAD `2344241a3a4b56f3096876ec9f89b33c3147db47`. 시작 시 미커밋 변경 없음. 과거 리뷰 사본 제외.
- 실행 담당: 부모가 HTTP 측정·환경 cleanup·문서·직렬 검증·GitHub 답글 담당. 하위 postgres_startup_fix가 PG lifecycle/회귀 파일 담당, 부모가 diff 통합. 해당 담당의 PG 자체 변경은 부모 자체 리뷰이며 HTTP 변경 검토만 독립 리뷰다.
- 환경: Windows 10, Java 25.0.4.1+1 / Gradle 9.7.1 / PostgreSQL 16.13 native / Chrome. 기존 측정과 동일 앱 JAR SHA-256 `3f0d902e90e15d8a8e048b60c618ba7830845d3f87f8729a4b361ed232195936` 사용 여부 확인. 실행은 저장소 루트, 합성 행사만 사용한다.
- 계획: 오류 1건/51건의 실제 HTTP 진단, CSV 보존·JUnit 실패·결과 검사 거부 확인 → 정상 실제 앱/DB 경계·재전송 확인 → PG 실패 정리 회귀 → 답글 및 해결.
- 로컬 선언: `ai/local-state/pr32-review/plan.json`, 실행 ID `pr32-review-fixes`. 원본 XML/CSV·로컬 영수증은 ignored 경로만 사용한다.

## 원인과 변경

1. 기존 코드는 예상 밖 오류가 있으면 100건 성공·재고 소진 검사를 생략하고, 50건 초과 시 정상 return했다. 측정 완료와 검증 통과를 섞은 결함이다. 현재는 CSV 6개 결과군 및 DB snapshot 기록 뒤 예상 밖 결과 0건, 성공 100건·예상 거절 900건과 DB 소진을 강제한다. 1건 오류에도 JUnit 실패로 다음 단계 증가를 막는다.
2. warmup 뒤 확보 POST만 별도 loopback HTTP 503 서버로 보내는 명시적 `ALFIO_LOAD_INJECT_503` 진단 모드를 추가했다. 기본 0이며 제품 앱 오류나 처리 한계 측정으로 해석하지 않는다. 원래 API 및 앱 응답을 변조하지 않는다.
3. PG 시작 시도와 성공을 분리하고 실패·준비 대기 timeout 뒤에도 소유 data 경로의 status/stop/status를 확인한다. 정지 확인 불가·명령 자체 생존이면 데이터 보존. 시작 오류를 cleanup 오류로 덮지 않고 suppressed로 연결한다.
4. 원 측정 파일 해시 차이는 CRLF/LF임을 확인했다. 커밋 `2344241`의 LF blob SHA-256 `66227b8a3bc0865ea6c4f86d5fd31699d1c6c67fc0536219763cebaf4b6d5fc2`를 CRLF로 복원하면 기존 기록의 `f3037c068aed2d06a130e2be6bb85d59263bef42031d591d0140026835f5e714`와 일치한다. 이전 12회 측정은 당시 결과이며 수정 후 결과로 재포장하지 않는다.

## 실행 명령과 검증

공통 환경: Java 25 `JAVA_HOME`, native PostgreSQL `ALFIO_VALIDATION_POSTGRES_BIN`. Gradle 공통 옵션은 `--console=plain --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx2g -XX:MaxMetaspaceSize=768m" -Pverbose`. 앱 빌드가 포함된 실행에서 이미 설치한 프론트 의존성 설치만 `-x frontendPnpmInstall -x frontendAdminPnpmInstall`로 생략한다.

- 기본 회귀 1차: `test --tests alfio.e2e.ReservationLoadMetricsTest --tests alfio.e2e.ValidationPostgresTest`, 종료 0, 22+8건 실제 통과. 이후 PG process 생존 추적·실제 PG 회귀 추가로 관련 재검증 예정이며 최종 결과와 구분한다.

## 리뷰·한계

- 하위 담당이 HTTP 오류 판정 및 진단 경로를 읽기 전용으로 검토해 원 지적 해소를 확인했다. PG 담당의 추가 발견(중단된 pg_ctl 자체가 살아 있을 가능성)은 생존 추적·보존으로 반영하고 부모가 통합 diff를 확인한다.
- PG timeout은 실제 서버를 띄운 뒤 command boundary에서 timeout 예외를 주입하는 방식이다. 실제 느린 기동으로 60초를 초과시키거나 OS kill 실패를 강제로 재현하는 검사는 아니다. fake 회귀와 실제 PG 실행을 구분한다.
- Docker/WSL 설치·로컬 컨테이너 검증은 사용자 보류를 유지한다. 이전 원격 CI와 이번 수정 뒤 CI를 혼동하지 않는다. 유료 결제·실제 메일·장시간 부하 등 기존 미검증 범위는 유지한다.
- 제품 성능 개선이 아닌 검증 도구의 실패 판정·정리 안전성 수정이다. 성능 개선율을 주장하지 않는다.

## 중간 실패와 유효 결과 분리

- 첫 정상 실행은 JUnit 3건 중 2건 실패, Gradle 종료 1. 재전송은 앱 준비 전 Java native memory allocation 실패, 경계 검사는 행사 logo 표시 대기 timeout이었다. 두 번째 실패의 원인을 메모리 부족으로 확정하지 않는다. 로그는 ignored `build/validation/readiness-failure.log`, 최초 XML은 `build/validation/pr32-initial/`에 보존했다.
- 같은 실행의 steppedLoad는 실제 동시20·1회 부하와 DB 안전성을 통과했다. 성공100 / 품절900 / 예상밖오류0, 평균823.536ms / p95 1341.582ms, 성공창4.387초(약22.79예약/초), 전체구간 성공14.683/s, HTTP2300, COMPLETE/ACQUIRED100·FREE/PENDING0·미확인확정0·deadlock0. CSV `build/validation/reservation-load-pr32-fixed-normal.csv`. 이 유효 사례만 재사용하며 전체3건 통과라고 표시하지 않는다.
- 변경은 앱 코드가 아니라 검증 실패 처리다. PC의 메모리 압박도 관찰했으므로 기존 12회와 같은 조건의 성능 개선 비교로 사용하지 않는다. 앱 JAR hash는 이전과 동일함을 확인했다.
- 후속 실행은 Gradle heap384m / metaspace256m / code cache64m / worker1로 빌드 도구 사용량만 낮췄다. 앱 heap768m·앱 JAR·DB 설정은 유지한다. 실패한 경계·재전송을 표적 재실행하고, 유효했던 정상20 부하는 반복하지 않는다.

## 오류 주입과 PG 후속 검증

| 검사 | 실제 결과 | 판정 / 증거 |
|---|---|---|
| warmup 뒤 HTTP503 1건 | 성공100·예상품절899·503 1, DB COMPLETE/ACQUIRED100·FREE/PENDING0 | JUnit 1건 failure1, 프로세스 종료1, CSV6행 보존, 다음 동시5 단계 미실행 |
| warmup 뒤 HTTP503 51건 | 성공100·예상품절849·503 51, DB COMPLETE/ACQUIRED100·FREE/PENDING0 | JUnit 1건 failure1, 프로세스 종료1, CSV6행 보존, 다음 동시5 단계 미실행 |
| 위 XML 결과 검사 | 각각 tests1 / failures1 / errors0 / skipped0 | check-results.py 각각 종료1로 거부 |
| 추가 정지 명령 생존 회귀 포함 PG | 실제 JUnit11 / 실패0 / 오류0 / skipped0 | PG 실제2 + 대역9, 결과 검사 종료0 |

- HTTP 진단은 실제 앱과 전용 native PG의 warmup·행사준비를 마친 뒤 측정 확보 POST만 별도 loopback 503 응답으로 라우팅했다. 모든 회차 총1000시도·동시1·반복1, 다음 단계5를 지정하여 중단을 확인했다. 실제 앱의 장애를 재현했다고 주장하지 않는다.
- 두 CSV: `build/validation/reservation-load-pr32-fault1.csv`, `reservation-load-pr32-fault51.csv`. XML: `build/validation/pr32-fault1/TEST-load.xml`, `pr32-fault51/TEST-load.xml`. AssertionFailedError의 실제 값이 1/51임을 확인했고 CSV 행·분류 합계·DB 결과·다음 단계 미실행을 별도 대조했다.
- 빌드 도구 heap 축소 뒤에도 앱 시작 메모리 오류가 있었고, 최초 direct JUnit 실행기도 메모리 부족으로 종료됐다. 해당 실행에는 주입 구간 결과가 없어 검증 성공으로 계산하지 않았다. 남은 소유 앱·DB·브라우저만 식별해 종료했으며 사용자 프로그램은 종료하지 않았다. 원본 JVM 오류 파일은 ignored `build/validation/pr32-environment-failures/`로 옮겼다.
- 성공한 진단은 이미 컴파일된 동일 테스트와 캐시된 JUnit Platform Launcher6.0.3을 별도 JVM으로 실행했다. 프로젝트 의존성·제품 JAR·앱 heap·DB 설정은 변경하지 않았다. 실행기만 `-Xmx192m -XX:ReservedCodeCacheSize=32m -XX:TieredStopAtLevel=1`로 낮췄다. ignored `build/validation/ReviewLauncher.java`가 JUnit discovery/실행 요약/실제 testcase 결과로 XML을 남기고 실패 시 종료1을 반환한다. `review-java.args`는 기존 Gradle test runtime classpath를 사용한다. 이는 성능 비교용 실행이 아니다.
- 실제 명령: `java @build/validation/review-java.args build/validation/pr32-fault<N>/TEST-load.xml steppedLoad`. 환경은 `ALFIO_RUN_LOAD=true`, `VALIDATION_APP_JAR` 지정, `ALFIO_LOAD_LEVELS=1,5`, `ALFIO_LOAD_REPEATS=1`, `ALFIO_LOAD_LABEL=pr32-fault<N>`, `ALFIO_LOAD_INJECT_503=<1 또는 51>`. 일반 재현에는 실행 안내의 Gradle task와 같은 테스트를 사용한다.
- 중단 뒤 재개 시 PG 최신 XML11건과 컴파일 시각, 소스 및 원격 HEAD 불변을 재확인했다. 해당 Gradle 명령의 실행 세션이 만료되어 최종 프로세스 종료코드는 회수하지 못했다. XML의 실제 testcase11건 통과와 `check-results.py` 종료0을 근거로 인정하며 이미 유효한 PG 검사는 반복하지 않는다. 이전 분류22 XML은 `build/validation/pr32-regression32/`에 보존했다.
- 추가 독립 최종 Review 담당 `pr32_final_review`가 PG·HTTP 통합 diff를 확인했다. stop 명령이 강제종료에도 생존할 때 후속 status가 Process 참조를 덮어쓸 수 있다는 지적을 반영했다. runProcess 진입에 생존 명령 guard를 추가하고, Process 대역으로 후속 status 미실행·데이터 보존·재close 거부를 검증했다. 이 마지막 증분도 독립 담당이 읽기 전용으로 재확인해 추가 차단사항 없음을 보고했다. 실제 OS kill 실패나 60초 지연을 일으킨 검사는 아니다.

## 최종 로컬 판정

- 예약 경계·재전송을 최종 컴파일 상태에서 direct JUnit으로 표적 재실행했다. `java @build/validation/review-java.args build/validation/pr32-boundary-replay/TEST-load.xml inventoryBoundaryAndInvalidInput retransmissionPreservesExistingContract` 종료0, 실제2건 성공·실패0·오류0·skipped0. 결과 검사도 종료0. 재고1장 경계·0/음수·확보 재전송의 별도예약·같은예약 확정 재전송HTTP200 및 최종DB1/2장 ACQUIRED를 확인했다.
- 유효한 분류22, 최종PG11, 정상C20 부하1회, 최종 경계·재전송2건을 구분해 인정했다. 의도한 HTTP503 진단2회는 각각 JUnit failure1/프로세스종료1이 기대 결과였으며 일반 정상 통과 건수에 합산하지 않았다.
- 최종 코드·컴파일·원본 근거를 재대조한 수동 QA/독립 리뷰 기록을 갱신했다. `gate pr32-review-fixes` 종료0: fault-http=passed(manual), postgres=passed(manual), review=passed(manual). 원 테스트를 새 실행으로 표시하지 않는다. 최초32건 뒤 PG 보강만11건 재실행했고, 유효했던 C20과 분류22는 반복하지 않았다.
- `ai/tests/check.py` 및 `git diff --check` 종료0. 제품 소스·DB schema·의존성·운영 설정 변경 없음. 원본CSV/XML/로그/로컬launcher/런타임은 커밋하지 않는다. 이전 원본 12회 성능 수치는 역사적 측정으로 유지한다.
- OOM으로 중단된 실행의 소유 앱·DB·브라우저 종료를 확인했다. 소유 임시 DB 폴더 하나의 삭제는 도구 자동 승인 정책에 의해 차단되었으며 구체적 사유는 제공되지 않았다. 우회 삭제하지 않고 정지된 폴더를 보존했다. 다른 사용자 앱·DB는 건드리지 않았다.
- GitHub 작업은 사용자 요청 범위의 커밋·기존PR푸시·기존지적답글·Resolve만 진행한다. PR 본문·병합·배포·Docker/WSL·BIOS·재부팅은 변경하지 않는다. 수정 커밋과 상세 검증 근거는 기존 리뷰 답글에서 연결한다. 새 원격CI는 이 커밋의 실제 상태로 따로 확인한다.

## 원격 CI 라이선스 실패 후속 수정

- 사용자 실패 제보 뒤 `d03da2bce`의 원격 결과를 확인했다. build의 PostgreSQL16 작업과 CodeQL 빌드가 새 `ValidationPostgresTest.java`의 `licenseTest` strict header 검사에서 실패했다. PostgreSQL15/17 작업 로그에는 다른 행렬 작업 실패에 따른 operation canceled가 기록되어 있었다. E2E·migration·Linux/Windows harness는 통과했다. 로컬 동작 회귀 통과를 원격 전체 CI 통과로 표시하지 않는다.
- 원인: 저장소 `config/HEADER`는 문장 뒤 공백 2칸을 요구한다. 신규 파일의 `PURPOSE. See`와 `alf.io. If`가 각각 공백 1칸이었다. 해당 두 공백만 기존 양식과 동일하게 맞췄다. 테스트 로직과 제품 동작은 변경하지 않았다.
- 로컬 첫 license 실행은 PC native memory allocation 실패로 Gradle daemon이 종료됐다(종료1). 진단 사본은 ignored build 경로에 보존했다. Gradle 실행기 heap256m/metaspace192m/codecache32m/TieredStopAtLevel1/ActiveProcessorCount2로 낮춰 재실행했다. 제품·앱·DB 설정은 바꾸지 않았다.
- `gradlew.bat licenseMain licenseTest --console=plain --no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx256m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=32m -XX:TieredStopAtLevel=1 -XX:ActiveProcessorCount=2" --rerun-tasks --info` 종료0. 플러그인은 성공 시 task를 UP-TO-DATE로 표기하지만 info에서 두 task가 `Executed with --rerun-tasks`로 강제 검사되고 `Header OK`를 출력한 사실을 확인했다. 캐시만 읽고 검사했다고 주장한 것이 아니다.
- 기존 예약·PG 회귀는 주석 공백만 바뀌었으므로 반복하지 않는다. 원격 CI는 이 수정 커밋에서 새로 실행하며 실제 완료 결과를 별도로 확인한다. PR 본문은 보존하고 기존 리뷰 답글에서 후속 근거를 남긴다.
