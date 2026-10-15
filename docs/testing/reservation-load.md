# 예약 부하와 안전성 검증

이슈 [#26](https://github.com/WookJaes/alf.io/issues/26)의 반복 실행 안내다. 실제 앱 JVM과 새 PostgreSQL DB에 합성 무료 온라인 행사를 만든다. 운영 DB, 외부 결제, 실제 메일을 사용하지 않는다. 예약 제품 코드를 바꾸기 전에 처리 한계와 정합성을 측정한다.

2026-10-15에 Windows의 전용 PostgreSQL 프로세스로 6개 동시성 단계 × 2회, 총 12회 측정을 완료했다. 아래 결과는 실제 CSV와 JUnit 결과에서 집계했다. 제품 예약 코드는 변경하지 않았다. 실행 근거와 전체 완료 상태는 [작업 기록](../harness/records/2026-10-15-issue26-reservation-load.md)에 남긴다. 기존 브라우저 E2E 2건(무료 예약·DB 대조 1건, 브라우저 탐색 회귀 1건)도 별도로 통과했다. Docker·WSL 설치는 사용자 선택으로 보류했고 컨테이너 통합 검증은 미수행이다.

## 준비

- 실제 개발 checkout의 루트에서 실행한다. 경로, 브랜치, HEAD, 미커밋 변경을 확인한다. 과거 리뷰 사본을 사용하지 않는다.
- Java 25, Gradle Wrapper, Chrome이 필요하다. Selenium Manager가 호환 드라이버를 준비하므로 최초 실행에는 인터넷 접근이 필요할 수 있다.
- 기본 DB 경로는 실행 중인 Docker Engine의 `postgres:16.13` Testcontainers다. Windows에서 Docker/WSL 설치는 별도 사용자 승인을 받은 후 진행한다.
- Docker 없이 실행할 때만 PostgreSQL 16.13의 `bin` 경로를 `ALFIO_VALIDATION_POSTGRES_BIN`에 지정한다. `initdb`, `pg_ctl`과 필요한 배포 DLL이 있는 완전한 배포본을 사용한다.
- 다른 부하 검사, 빌드, 앱·DB 검증을 함께 실행하지 않는다. 같은 PC에서 부하 생성기와 서버가 실행되므로 CPU·메모리·디스크 경쟁을 기록한다.

새 라이브러리를 추가하지 않는다. JDK `HttpClient`가 공개 예약 API 부하를 만들고, 기존 Selenium 관리자 흐름이 행사 생성·게시를 담당한다. 행사 준비와 브라우저 작업은 부하 시간에 포함하지 않는다.

## Windows PowerShell 실행

아래 경로는 예시다. 실제 준비한 Java·PostgreSQL 경로를 넣는다. 환경 변수는 현재 PowerShell 세션에만 적용하며 시스템 설정을 바꾸지 않는다.

```powershell
Set-Location 'C:\path\to\alfio-issue26-dev'
git branch --show-current
git status --short
git rev-parse HEAD

$env:JAVA_HOME = 'C:\path\to\jdk-25'
& "$env:JAVA_HOME\bin\java.exe" -version
$env:ALFIO_VALIDATION_POSTGRES_BIN = 'C:\path\to\postgresql-16.13\bin'
& "$env:ALFIO_VALIDATION_POSTGRES_BIN\postgres.exe" --version

$env:ALFIO_LOAD_LEVELS = '1,5,10,20,40,80'
$env:ALFIO_LOAD_REPEATS = '2'
$env:ALFIO_LOAD_LABEL = 'baseline'
```

첫 준비 확인은 낮은 부하 1단계·1회로 시작해도 된다. 전후 비교에 쓸 본 측정에서는 두 실행의 단계·반복 수를 같게 맞춘다.

```powershell
$env:ALFIO_LOAD_LEVELS = '1'
$env:ALFIO_LOAD_REPEATS = '1'
$env:ALFIO_LOAD_LABEL = 'smoke'
& .\gradlew.bat reservationLoadValidation --no-daemon --no-build-cache -Pverbose
if ($LASTEXITCODE -ne 0) { throw '예약 부하 검증 실패: 결과를 확인하세요.' }
```

본 측정 명령:

```powershell
$env:ALFIO_LOAD_LEVELS = '1,5,10,20,40,80'
$env:ALFIO_LOAD_REPEATS = '2'
$env:ALFIO_LOAD_LABEL = 'baseline'
& .\gradlew.bat reservationLoadValidation --no-daemon --no-build-cache -Pverbose
if ($LASTEXITCODE -ne 0) { throw '예약 부하 검증 실패: 결과를 확인하세요.' }
python scripts/validation/check-results.py build/test-results/reservationLoadValidation
if ($LASTEXITCODE -ne 0) { throw '실제 테스트 건수 또는 결과 검사 실패' }
```

`reservationLoadValidation`은 현재 작업 트리의 `bootJar`를 빌드한다. 테스트에 앱 JAR 경로와 opt-in 실행 변수를 전달하므로 별도로 설정할 필요가 없다. 테스트 task는 UP-TO-DATE로 생략하지 않는다. `-Pverbose`는 요약·DB 설정을 보이게 한다. 빌드 성공과 실제 테스트 실행 성공을 구분한다. 클래스의 3개 테스트를 모두 실행한 경우 새 XML의 `tests=3`, `failures=0`, `errors=0`, `skipped=0`을 확인한다.

## Docker 기본 경로

Windows에서는 같은 PowerShell 명령을 사용하되 portable DB 선택 변수를 제거한다. Docker Engine이 실행 중인지 먼저 확인한다. 기존 DB 주소나 데이터 디렉터리는 지정하지 않는다.

```powershell
Remove-Item Env:ALFIO_VALIDATION_POSTGRES_BIN -ErrorAction SilentlyContinue
docker info
if ($LASTEXITCODE -ne 0) { throw 'Docker Engine을 먼저 준비하세요.' }
$env:ALFIO_LOAD_LABEL = 'docker-baseline'
& .\gradlew.bat reservationLoadValidation --no-daemon --no-build-cache -Pverbose
if ($LASTEXITCODE -ne 0) { throw 'Docker 예약 부하 검증 실패' }
python scripts/validation/check-results.py build/test-results/reservationLoadValidation
if ($LASTEXITCODE -ne 0) { throw '실제 테스트 건수 또는 결과 검사 실패' }
```

Linux의 대응 명령은 다음과 같다. Java 25와 Docker·Chrome을 먼저 준비한다.

```sh
set -e
unset ALFIO_VALIDATION_POSTGRES_BIN
export JAVA_HOME=/path/to/jdk-25
export ALFIO_LOAD_LEVELS=1,5,10,20,40,80
export ALFIO_LOAD_REPEATS=2
export ALFIO_LOAD_LABEL=docker-baseline
./gradlew reservationLoadValidation --no-daemon --no-build-cache -Pverbose
python3 scripts/validation/check-results.py build/test-results/reservationLoadValidation
```

portable DB와 컨테이너 DB의 실행 결과는 별도로 표시한다. OS·DB 설정·자원 조건이 다르면 동일 조건의 전후 성능 비교로 합치지 않는다. 일반 Docker 통합 검사는 [기존 검증 안내](automatic-validation.md)를 따른다.

## 데이터와 흐름

각 동시성 단계의 각 반복은 새로운 앱과 DB를 만든다. 앱은 `127.0.0.1`의 자동 할당 포트, `dev,disable-jobs`, 최대 힙 `768m`으로 실행한다. 임시 실행 디렉터리와 환경 변수 필터로 기존 앱 설정·외부 제공자 비밀값을 분리한다. 메일은 `disabled`, 지도는 `NONE`으로 설정한다.

합성 관리자·조직을 넣고, 20장짜리 warmup 행사의 예약을 순차 확정한다. 이어서 본 행사에 FREE 티켓 100장을 만든다. warmup 행사 20장의 ACQUIRED 티켓은 매 반복 DB에 동일하게 남고 본 행사 측정과 분리한다.

portable DB는 매 실행 새 임시 `postgres-data`를 `initdb`로 만든다. `127.0.0.1`만 수신하며 포트를 자동 할당한다. 기존 DB·서비스·데이터 폴더를 열지 않는다. 현재 고정 설정은 `max_connections=100`, `shared_buffers=128MB`, `track_io_timing=on`, `log_lock_waits=on`, `deadlock_timeout=100ms`다. trust 인증은 이 격리된 로컬 합성 DB에만 사용한다. 실행 시 출력된 실제 `work_mem`, `fsync`, `synchronous_commit` 등도 결과 기록에 적는다. Docker 경로의 실제 설정도 출력으로 확인한다.

본 시나리오는 **총 1,000개 논리 예약 시도**다. `ALFIO_LOAD_LEVELS`는 동시에 진행되는 흐름 수다. 총 요청 수를 동시 요청 수와 혼동하지 않는다. 기본은 동시 흐름 1 → 5 → 10 → 20 → 40 → 80, 단계마다 2회다. 양의 정수 단계만 쓰고 최대 200을 넘기지 않는다. 품절을 미리 확인해 예약 시도를 생략하거나 실패를 자동 재시도하지 않는다.

각 흐름은 공개 API에서 다음을 실행한다.

1. 티켓 분류 조회.
2. 티켓 1장 확보 POST. 예상 재고 거절이면 이 흐름을 종료.
3. 예약·티켓 조회.
4. 합성 예약자·참석자 입력과 overview 검증.
5. 약관·개인정보 동의와 무료 예약 확정.

오류 없이 정확히 100개 확정·900개 품절 거절이면 부하 구간 실제 HTTP는 `100 × 5 + 900 × 2 = 2,300개`다. 이 중 확보 POST는 1,000개다. 세션 초기화, warmup, 관리자 행사 생성·게시, 별도 경계·재전송 테스트 HTTP는 2,300개에 포함하지 않는다. 실패가 중간에 발생하면 실제 HTTP 수가 달라지므로 CSV 값을 확인한다.

예상 품절 거절 이외의 결과가 **1건이라도 있으면**, 해당 회차의 CSV와 최종 DB 상태를 남긴 뒤 JUnit을 실패시켜 추가 부하 증가를 중단한다. 오류 없이 정확히 100개 확정·900개 예상 거절, 최종 COMPLETE 100개·FREE/PENDING 0장이어야 통과한다. 오류를 측정했다는 사실과 수용 기준 통과를 구분한다.

오류 판정 자체를 재현하려면 warmup 뒤 확보 POST만 loopback HTTP 503 서버로 일부 보내는 진단 모드를 사용할 수 있다. 제품 앱의 장애나 성능 측정으로 해석하지 않는다. `ALFIO_LOAD_INJECT_503`은 기본 0, 허용 범위 0~1,000이며 회차별 적용된다. 예를 들어 단계 `1,5`, 반복 `1`, 새 label과 주입 수 `1` 또는 `51`을 지정하고 `reservationLoadValidation --tests alfio.e2e.ReservationLoadValidationTest.steppedLoad`를 실행하면 첫 단계에서 실패해야 한다. CSV에는 각각 `server_error=1` 또는 `51`이 남고 다음 단계는 실행하지 않아야 한다. `check-results.py`도 실패해야 한다. 진단 뒤 `Remove-Item Env:ALFIO_LOAD_INJECT_503`으로 해제하고 정상 검증을 실행한다. 실제 서버에 전달된 확보 POST 수는 주입 수만큼 줄어든다.

## 지표 해석

CSV는 단계·반복·결과 종류마다 한 행을 남긴다.

| 결과 | 분류 |
| --- | --- |
| `complete` | 확정 HTTP 흐름을 완료한 시도. 이후 예약·티켓 관계를 DB에서 대조한다. |
| `inventory_rejection` | 확보 응답이 HTTP 422, 최상위 `success=false`, `errorCount>0`이며 허용한 재고 오류 코드만 포함한 경우. 사전에 공개·판매중·접근 제한 없음이 확인된 합성 행사에서만 분류하고 최종 DB 재고를 확인한다. |
| `server_error` | 예상하지 않은 HTTP 5xx. |
| `unexpected_response` | 예상 재고 거절에 해당하지 않는 응답·4xx 등. |
| `client_error` | HTTP 생성기·통신·파싱 등의 예외. |
| `timeout` | HTTP 요청 제한 시간 초과. |

허용한 재고 오류 코드는 `error.STEP_1_NOT_ENOUGH_TICKETS`, `error.STEP_1_TICKET_CATEGORY_MUST_BE_SALEABLE`다. `error.STEP_1_OVER_MAXIMUM`은 구매 수량 제한(기본 5장) 오류이므로 품절에서 제외하고 `unexpected_response`로 분류한다. 판매 가능 여부 오류는 사전에 공개·판매중·접근 제한 없음과 1장 요청을 확인한 이 합성 행사에서만 후보로 본다. 실제 소진 여부는 최종 DB로 확인한다. 모든 4xx를 정상 처리하지 않는다. 오류 분류는 별도 회귀 검사와 함께 확인하며 기존 API 계약을 따른다.

- `mean_ms`, `p95_ms`: 해당 결과의 전체 흐름 시간. 첫 분류 조회부터 확정 또는 거절·오류까지다. 작업 큐에서 차례를 기다린 시간은 제외한다. p95는 정렬한 관측값의 95% 위치를 올림하여 선택한다. 해당 결과가 0건이면 `NaN`이다.
- `outcome_rps`: 해당 결과 건수 / 전체 부하 구간 시간. `total_rps`는 1,000개 전체 시도 / 같은 시간이다. 확정 처리량과 품절 거절 처리량을 따로 보고한다.
- `success_window_seconds`: 첫 측정 요청 시작부터 마지막 HTTP 완료 성공까지의 구간. 오류 없이 100개 성공이면 `100 / success_window_seconds`로 초기 성공 구간 처리량을 별도로 계산할 수 있다. 이는 티켓이 소진될 때까지의 burst 처리량이며 지속 최대 처리량이 아니다. 전체 1,000개 시도 시간을 분모로 한 `complete`의 `outcome_rps`와 구분한다.
- 빠른 품절 응답을 확정 예약 응답시간 개선으로 계산하지 않는다. 서로 다른 결과의 p95를 섞지 않는다.
- `http_requests`: 부하 작업자의 실제 HTTP 수. 초기 세션 요청을 뺀다.
- `db_samples`, `peak_active`, `wait_samples`: 약 100ms 간격 `pg_stat_activity` 관찰 횟수·활성 세션 최대·대기 사건 표본 수. 표본은 누적 대기시간이나 모든 잠금 발생 건수가 아니다. 짧은 대기는 놓칠 수 있다.
- `deadlocks`, `rollbacks`: 측정 전 통계를 초기화한 전용 DB의 통계. 재고 부족의 정상 transaction rollback과 다른 오류를 함께 고려하며 rollback 전체를 서버 결함으로 판단하지 않는다.
- `app_cpu_seconds`: 앱 프로세스 CPU 시간 차이. 생성기·DB CPU나 전체 PC CPU 사용률을 대신하지 않는다.
- `db_complete`, `db_acquired`, `db_free`, `db_pending`: 본 행사 최종 DB의 COMPLETE 예약과 각 상태의 티켓 수. `unacknowledged_complete`는 HTTP 완료 성공으로 기록하지 못했지만 DB에는 COMPLETE로 남은 예약 수다. timeout·오류 뒤에 실제 확정됐을 가능성을 보여주므로 HTTP 성공 건수만으로 결과를 판단하지 않는다. 이 DB 수치는 HTTP 결과 분류를 임의로 바꿔 오류를 숨기는 데 사용하지 않는다.

## 예약 안전성과 회귀 검사

HTTP 완료 응답에서 받은 reservation·ticket 식별자는 메모리에서 DB와 대조한다. 본 행사 총 티켓 수가 100이고 확정 ACQUIRED 수가 정원 이하인지, FREE/PENDING/ACQUIRED 합계, ticket UUID와 응답 식별자 중복, 고아 예약, 티켓 상태와 예약 상태 관계, COMPLETE 예약당 ACQUIRED 티켓 1장과 실제 식별자 연결을 검사한다. 티켓 재고는 별도 감소 숫자 대신 행 상태로 표현하므로 정원과 상태 합계로 검사한다.

별도 실제 API·DB 테스트는 1장 재고에 2장 요청, 수량 0·음수, 마지막 1장 확정 뒤 추가 확보 거절을 확인한다. 부족 요청의 transaction rollback 뒤 티켓·예약 잔여 상태도 검사한다.

재전송 검사는 확보 POST를 두 번 보내면 서로 다른 예약을 만드는 기존 계약을 확인한다. 생성 API에 새 idempotency 규칙을 추가하지 않는다. 같은 예약의 확정 POST를 다시 보낸 뒤 두 예약·티켓의 최종 상태가 유지되는지 DB에서 검사한다. 재전송 응답과 오류 코드는 실제 실행 결과에 남긴다.

## 결과 보관과 정리

- 요약 CSV: `build/validation/reservation-load-<label>.csv`. 같은 label은 이전 CSV를 덮어쓰므로 재실행·개선 후 측정은 `baseline-r2`, `after-r1` 등 새 label을 쓴다.
- 새 JUnit XML: `build/test-results/reservationLoadValidation/`. HTML: `build/reports/tests/reservationLoadValidation/`.
- 모두 `.gitignore`의 `build/` 아래다. 원본 CSV·JSON·로그·덤프·비밀값·개인정보는 커밋하거나 외부 게시하지 않는다. 커밋 가능한 작업 기록에는 집계·조건·코드 식별·실제 명령·종료 결과·한계만 적는다.
- 정상 종료와 일반 예외 시 브라우저·앱·전용 DB를 닫고 임시 폴더를 제거한다. 앱 준비·data migration 실패의 진단 사본은 `build/validation`에 남을 수 있다. 합성 비밀값이 포함될 수 있으므로 그대로 공유하지 않는다.
- 전용 PostgreSQL 시작은 성공 여부와 별도로 시도 여부를 추적한다. 시작 실패·준비 timeout 뒤에도 소유 데이터 경로의 서버를 확인·정지하며, 정지 확인 실패나 상태 불명일 때 데이터 폴더를 보존한다. 정리 오류는 원래 시작 오류에 suppressed로 연결한다. 강제 OS 종료·프로세스 강제 종료 후에는 정리가 보장되지 않는다. 실행 소유 PID·임시 폴더·컨테이너를 확인해 그 실행 자원만 정리한다. 기존 사용자 DB·컨테이너나 전체 PostgreSQL 프로세스를 일괄 종료하지 않는다.

## 실제 측정 결과

측정 기준 HEAD는 `b2cb15333f5a67115ac8ccac7b754517f565fc18`이며, 해당 HEAD 위의 미커밋 검증 자산으로 실행했다. 부하 검사 코드 SHA-256은 `f3037c068aed2d06a130e2be6bb85d59263bef42031d591d0140026835f5e714`, 앱 JAR SHA-256은 `3f0d902e90e15d8a8e048b60c618ba7830845d3f87f8729a4b361ed232195936`다. 측정 중 제품 예약 코드와 앱 설정은 바꾸지 않았다. 원 측정 소스는 Windows CRLF 바이트 해시이며, 커밋 `2344241`의 같은 파일을 LF로 정규화한 SHA-256은 `66227b8a3bc0865ea6c4f86d5fd31699d1c6c67fc0536219763cebaf4b6d5fc2`다. 해당 커밋 blob을 CRLF로 복원하면 위 `f3037c…` 값과 일치함을 확인했다. 이후 리뷰 수정의 재검증은 [별도 기록](../harness/records/2026-10-15-pr32-review-fixes.md)에 구분한다.

환경은 Windows 10 Pro 10.0.19045, Ryzen 7 1800X(8코어/16논리 CPU), RAM 17,086,177,280 bytes, Java 25.0.4.1+1, Gradle 9.7.1, PostgreSQL 16.13 전용 프로세스다. C:는 220,358,246,400 bytes의 File Backed Virtual Disk로 관찰했다. 물리 저장장치와 가상 디스크 계층의 영향을 분리 측정하지 않았다. 앱 최대 힙은 768m, 기존 DB 연결 풀 max=10/min=5, 연결 대기 제한은 1,000ms다. 실제 DB 출력은 `max_connections=100`, `shared_buffers=128MB`, `work_mem=4MB`, `fsync=on`, `synchronous_commit=on`, `track_io_timing=on`이었다.

아래 값은 각 동시성의 **2회 실행 최솟값~최댓값**이다. 평균·p95는 성공과 품절을 나누어 계산했다. 처리량 단위는 논리 예약 시도/초이며 HTTP 요청/초가 아니다.

| 동시 흐름 | 성공 평균(ms) | 성공 p95(ms) | 품절 p95(ms) | 성공 구간 처리량 | 전체 구간 성공 처리량 | 전체 시도 처리량 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 81.339~90.645 | 108.433~127.495 | 11.410~13.303 | 11.025~12.290 | 5.722~6.238 | 57.216~62.384 |
| 5 | 177.117~188.149 | 269.186~295.418 | 16.355~17.506 | 26.337~27.824 | 16.307~17.201 | 163.065~172.006 |
| 10 | 343.771~380.324 | 486.688~539.488 | 46.122~46.171 | 25.157~28.337 | 15.872~16.468 | 158.725~164.683 |
| 20 | 663.365~711.294 | 872.076~1235.493 | 88.407~104.552 | 26.178~27.412 | 16.738~17.752 | 167.382~177.518 |
| 40 | 1315.494~1475.423 | 1896.726~2343.221 | 251.780~252.525 | 22.789~25.867 | 15.877~16.519 | 158.770~165.187 |
| 80 | 2394.602~2470.216 | 3323.862~3408.418 | 470.294~491.275 | 21.935~23.408 | 17.284~17.618 | 172.838~176.179 |

모든 12회에서 각각 100개 예약 확정·900개 예상 재고 거절이었다. 측정 부하만 합치면 **예약 확보 POST 12,000개, 실제 HTTP 27,600개, COMPLETE 예약 1,200개, 예상 재고 거절 10,800개**다. warmup·행사 준비·별도 회귀 테스트는 이 합계에서 제외했다. 5xx·기타 응답·생성기 오류·timeout은 모두 0건이다.

최종 DB는 매회 COMPLETE 예약 100개, ACQUIRED 티켓 100장, FREE/PENDING 0장, HTTP 미확인 확정 0개였다. 정원 초과·동일 티켓 중복 배정·재고와 상태 합계 위반·예약과 티켓 상태/관계 불일치 검사에서 위반은 0건이었다. JUnit은 부하·재고 경계·재전송의 3개 테스트를 실제 실행해 `tests=3, failures=0, errors=0, skipped=0`이었다. HTTP 확보 재전송은 별도 예약을 만들고, 같은 무료 예약의 확정 재전송은 성공하는 기존 계약을 유지했다.

관찰된 DB 활성 세션 최대는 동시성 1/5/10/20/40/80에서 각각 1/5/7/6/7/8이었다. WAL 계열 대기 표본이 관찰됐지만 누적 대기시간은 측정하지 않았다. deadlock은 전 회차 0건, rollback은 회차당 0~4건이었다. 재고 부족 시 발생 가능한 rollback을 별도로 보며, 이 통계만으로 결함을 단정하지 않는다. 앱 CPU 시간은 회차당 19.859~55.188초였다.

## 판단과 남은 검증

동시성 1/5/10/20의 초기 두 회차를 확인한 뒤, **제품 개선 전에** 작업 기록에 로컬 수용 기준을 정했다. 기준은 총 1,000개 예약 확보 POST·동시 흐름 20개에서 성공 평균 ≤1초, 성공 p95 ≤1.5초, 초기 100건 성공 구간 처리량 ≥20예약/초, 성공 100건·예상 거절 900건, 서버·기타·timeout 오류와 DB 정합성 위반 0건이다. 동시성 20의 두 회차는 이 기준을 모두 충족했다. 이는 측정 근거를 바탕으로 정한 실험실 기준이며 운영 SLA를 대신하지 않는다.

동시성 40/80에서는 지연이 늘고 처리량이 더 늘지 않았다. 다만 이번 관측만으로 연결 풀·SQL·CPU·디스크 중 특정 병목 원인을 확정하지 못했다. 로컬 목표 미충족이나 예약 정합성 결함이 확인되지 않아 제품 예약 코드·설정은 수정하지 않았다. 따라서 변경 전후 성능 개선율은 없으며, 검증 자산과 관찰된 처리 범위를 결과로 남긴다.

기존 브라우저 E2E는 위 JUnit 3건과 별도로 2건을 실행해 통과했다. Docker·WSL 설치 승인을 질문했고 사용자가 현재 설치 보류를 선택했다. 설치·컨테이너 환경의 기존 통합 검증은 미수행이며 native DB 결과로 이를 대체하지 않는다. 자세한 후속 상태는 작업 기록에 남긴다.

이 시나리오는 재고 100장에 한 장씩 예약하는 무료 단일 행사, 같은 PC의 부하 생성기와 앱·DB에 대한 제한된 검사다. 유료 결제, 실제 메일, 다중 행사·인스턴스, 운영 네트워크, 장시간 지속 부하와 사용자 생각 시간은 검증하지 않는다. 단계 증가 방식의 동시 흐름 검사이며 일정한 초당 도착률을 강제하는 검사는 아니다. 관찰된 한계를 운영 SLA나 다른 서버의 최대 처리량으로 일반화하지 않는다.

## Docker 준비 상태와 다음 단계

2026-10-15 재개 확인에서 BIOS 가상화는 꺼져 있고, Windows `VirtualMachinePlatform`·`Microsoft-Windows-Subsystem-Linux` 기능은 Disabled였다. native 결과는 그대로 보존한다. 사용자가 “지금은 설치 보류”를 선택했으므로 설치와 Windows 기능 변경은 진행하지 않았다. 나중에 재개 승인을 받으면 Windows 기능과 공식 WSL·Docker Desktop을 준비하고, 별도로 정한 재부팅 시점에 BIOS SVM을 활성화해야 WSL2 경로를 실제 확인할 수 있다. 현재 컨테이너 검증은 완료하지 않았다.

설치 조건은 [Docker Windows 안내](https://docs.docker.com/desktop/setup/install/windows-install/)와 [Microsoft WSL 설치 안내](https://learn.microsoft.com/en-us/windows/wsl/install)를 기준으로 확인한다. 자동 재부팅·BIOS 변경·운영 설정 변경을 이번 native 측정에 포함하지 않는다.
