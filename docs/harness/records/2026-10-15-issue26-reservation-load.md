# 이슈 #26 예약 부하 검증

- 작업 ID / 날짜 / 상태: issue26 / 2026-10-15 / incomplete (native 검증 완료, Docker 설치·컨테이너 검증 사용자 보류)
- 범위: https://github.com/WookJaes/alf.io/issues/26 (최신 댓글 0건). #25·결제·외부 메일·운영 데이터 제외.
- 담당: 부모 Codex가 실제 실행·통합, 별도 에이전트가 읽기 전용 계약 Review·측정 분석, 회귀 테스트 파일, 실행 문서를 분담. 파일 수정 책임 분리; 앱·DB·Gradle 실행은 부모 단독.
- 개발 경로: C:/Users/<사용자>/Documents/Codex/alfio-issue26-dev
- 브랜치: codex/issue-26-reservation-load
- 시작 HEAD: b2cb15333f5a67115ac8ccac7b754517f565fc18 (원격 main과 일치). 시작 변경 없음.
- 원본 보존: 채팅 폴더는 빈 Git; Documents의 과거 경로는 HEAD 없음. OneDrive의 과거 경로 HEAD 97ca4e19이나 placeholder 읽기 중 Git 상태 확인이 멈춰 중단. 원본 수정·복구·리셋 없음. 이번 경로는 임시 리뷰 사본이 아닌 새 개발 checkout.
- 환경: Windows, Ryzen 7 1800X 8코어/16논리CPU, RAM 17,086,177,280 bytes. Java 25.0.4.1+1 ZIP SHA256 확인. Gradle 9.7.1 Wrapper. PostgreSQL 16.13 전용 프로세스; Docker/WSL 미준비.
- 사용자 결정: 폴더 안 Java/PostgreSQL로 진행. Docker·WSL은 변경 정리 후 설치 여부를 질문하고 설치 승인 후 컨테이너 검증. 커밋·푸시·PR·외부 댓글 금지 유지.
- 계획: 예약 계약·잠금 분석 → 독립 DB/앱의 HTTP 시나리오·DB 안전성 검사 → 초기 반복 측정 → 목표 기록 → 확인된 문제만 최소 개선 → 동일 조건 재측정·회귀·기존 E2E → 자체 리뷰·문서.
- 필수 검증: 실제 부하/DB, 경계·재전송, 기존 무료 예약 E2E, 자체 QA·Review. Docker 기존 통합 테스트는 설치 결정까지 대기.
- 부하: 각 반복마다 신규 DB/앱, 무료 온라인 warmup 행사 20장 모두 확정 후 본 행사 100장. 총 1,000개 논리 예약 시도; 동시 흐름 수 1/5/10/20/40/80, 각 2회. 각 흐름은 분류 조회→확보→예약 조회→예약자·티켓 검증→확정. 재시도·품절 사전 생략 없음. 성공/재고거절/기타오류/timeout 별도 집계. unexpected failure >5%이면 추가 증가 중단.
- 기준: 초기 측정 전 성능 목표·개선율 미설정. 정합성은 처음부터 위반 0건 필수.
- 증거 정책: 합성 식별자는 메모리에서 대조, 요약 CSV는 ignored build/validation, JUnit은 ignored build/test-results. 원본 앱·DB 임시 로그는 종료 시 제거. 커밋 가능한 문서에는 집계·명령·코드 식별·한계만 기록.

## 예약 계약 확인

- 공개 조회 GET /api/v2/public/event/{slug}/ticket-categories; 확보 POST event/{slug}/reserve-tickets?lang=en. 성공 응답의 value는 새 reservation UUID. 검증 실패 HTTP422의 정확한 validationErrors를 분류한다.
- 확보는 TicketReservationManager의 Spring @Transactional 안에서 새 예약 행 생성→FREE 티켓 SELECT ... ORDER BY id LIMIT ... FOR UPDATE SKIP LOCKED→PENDING 배정→가격·audit. 수량 부족 RuntimeException이면 transaction rollback. 이 경로에는 자동 재시도·생성 idempotency key가 없다.
- 확정 전 GET reservation/{id}, POST validate-to-overview로 합성 예약자·참석자 입력, POST reservation/{id}로 약관/개인정보 동의 후 무료 확정. 실제 DB COMPLETE/ACQUIRED 및 UUID 관계 확인.
- 연결 풀 기본 max=10/min=5, acquisition timeout=1,000ms. 운영 설정 수정 없음.
- 재전송: 생성 POST의 두 제출은 서로 다른 신규 예약을 만들 수 있다는 기존 계약을 확인한다. 확정 재전송은 동일 예약/티켓의 최종 상태를 다시 DB 대조. idempotency 정책을 새로 추가하지 않는다.

## 변경과 검증

예약 제품 코드·API·DB 마이그레이션은 변경하지 않았다. 신규 부하/분류 회귀 검사, 검증용 portable PostgreSQL 지원, 전용 Gradle task와 실행 문서를 구현했다. 실제 결과는 아래 표에 기록한다.

## 사전 검사와 분담

- 사용자 요청으로 분담 승인: 부모는 실제 앱/DB 실행·코드 통합 담당, 읽기 전용 계약 Review 에이전트, 측정 분류 회귀 파일 담당, 실행 문서 담당으로 나눔. 같은 파일 동시 수정·같은 자원 검증 없음.
- 예약 부하 도구 사전 실패: 공개 API의 X-Auth-Token 세션 헤더/CSRF 전달 누락, 예약 행사 컬럼 event_id_fk 및 공개 티켓 public_uuid 대조 오류를 수정. 제품 오류로 집계하지 않음.
- 검증 명령 실수: 여러 Gradle 작업 뒤의 --tests는 마지막 작업에만 적용되어 :test 전체가 실행됨. Docker 미준비 오류 확인 후 중단. 필요한 작업을 분리 실행. 해당 실패는 제품 테스트 결과/부하 서버오류로 쓰지 않음.
- 측정 분류 회귀: test --tests alfio.e2e.ReservationLoadMetricsTest / 종료0 / 실제18건 / 실패0·오류0·스킵0. build/test-results/test/TEST-alfio.e2e.ReservationLoadMetricsTest.xml 확인. 이후 수정은 DB 대조와 자원 정리이며 분류 계약 변경 없음.
- 독립 계약 검토: 수량제한 OVER_MAXIMUM은 품절 제외, 실제 품절은 유효한 판매 fixture의 SALEABLE/NOT_ENOUGH_TICKETS. 무료 확정 replay는 200 성공. 실패 응답 이후 DB 확정도 별도 집계. 실패 경로 worker client 정리와 반복0회 방지 반영.
- 최종 상태 측정: 활성 앱 DB 작업이 3회 연속 없을 때(최대15초) repeatable-read snapshot으로 티켓/예약 대조. 이 유계 대기가 모든 임의 장기 외부처리를 보장한다고 주장하지 않음.
- 제품 JAR SHA256: 3f0d902e90e15d8a8e048b60c618ba7830845d3f87f8729a4b361ed232195936. 예약 제품 코드 수정 없음.

## 첫 유효 측정

- baseline C=1 반복1: 1,000 예약POST/2,300 HTTP, 전체17.478s. 성공100(평균90.645ms,p95 127.495ms), 재고거절900(평균9.318ms,p95 13.303ms), 5xx/기타응답/생성기오류/timeout0. DB COMPLETE/ACQUIRED=100, FREE/PENDING=0, 미확인확정0. Deadlock0, rollback0. 성공구간9.070s(100/9.070≈11.03/s), 전체구간 성공처리량5.722/s와 구분.
- 실제 실행 OS: Windows 10 Pro 10.0.19045. C:는 File Backed Virtual Disk 220,358,246,400 bytes. 실제물리 SSD의 전체 특성·가상디스크 계층 영향은 이번 수치로 분리하지 않았음.
- 부하검사 코드 SHA256: f3037c068aed2d06a130e2be6bb85d59263bef42031d591d0140026835f5e714.
- 독립 최종 정적 Review: /root/reservation_contract_review. 위 코드 및 Environment 7340cf9a7118, PostgreSQL ba9d5ebf4561에서 추가 차단사항 없음. 실제 실행/정리·Docker는 해당 리뷰 범위 밖.

## 초기 측정 뒤 정한 수용 기준 (제품 개선 전에 기록)

- 설정 시점: 2026-10-15, 동시1/5/10/20 각2회 결과 확보 뒤. 제품 예약 구현·설정 변경 전이며 지금까지 제품 수정 없음.
- 이번 실험실 목표: 총1,000개의1장 예약 생성POST, 동시흐름20개, 성공평균≤1,000ms, 성공p95≤1,500ms, 초기100건 성공구간 처리량≥20예약/s, 성공100건·예상재고거절900건, 5xx/예상밖응답/클라이언트오류/timeout0, 모든 DB정합성 위반0.
- 근거: 동시20의 두 회차 성공평균663.365/711.294ms, p95 872.076/1235.493ms, 성공창3.648/3.820s(27.41/26.18예약/s). 나쁜 회차에도 지연여유 약21%, 처리량여유 약31%. 운영SLA·사용자사업요구를 대신하지 않는 로컬 합성 검증 기준이다.
- 동시40/80은 추가 탐색 단계. 큰 동시성에서 이 로컬 기준을 넘더라도 곧바로 기존 서버 결함이라고 단정하지 않으며, 실제원인과 목표를 충족하는 최소변경 필요를 먼저 판단한다.
- 기존 연결풀10의 활성DB세션 표본최대는 동시20에서6이었고, pool timeout/서버오류0이다. WAL 계열 대기표본을 관찰했으나 connection pool/SQL/CPU의 특정 병목원인까지 증명한 것은 아니다. 안전성 문제·목표 미충족을 확인하지 않고 Redis/Kafka/분산락/제품 튜닝을 도입하지 않는다.

## 전체 native 결과와 재개 검증

- 사용량 중단 뒤 코드/JAR 해시, JUnit XML, 72개 CSV 결과행을 다시 열어 확인했다. 원격 main도 b2cb153과 동일. 유효한 12회 부하는 재실행하지 않았다.
- 부하 실행: reservationLoadValidation / 종료0 / 실제 JUnit 3건, 실패0·오류0·스킵0. 이 중 steppedLoad 1건이 12개 독립 DB/앱 회차를 수행했고 나머지 2건은 경계·재전송이다.
- 측정 구간 합계: 예약 생성 POST 12,000개, HTTP 27,600개, COMPLETE 예약 1,200개, 재고거절 10,800개. 각 회차마다 100/900; 모든 예상밖 결과0, DB 정합성 위반0.
- 데이터: 각 회차 행사2개, 티켓120개(warmup ACQUIRED20 + 본행사 FREE100), warmup 예약20개. 본 부하 종료 시 본행사 COMPLETE/ACQUIRED100. 본문 표의 지연은 전체 흐름이며 큐 대기·행사준비·warmup을 제외한다.

| 동시 흐름 | 반복 | 성공 평균 ms | 성공 p95 ms | 품절 평균 ms | 품절 p95 ms | 성공 burst 예약/s | 전체 구간 성공/s | 전체 시도/s |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 1 | 90.645 | 127.495 | 9.318 | 13.303 | 11.03 | 5.722 | 57.216 |
| 1 | 2 | 81.339 | 108.433 | 8.754 | 11.410 | 12.29 | 6.238 | 62.384 |
| 5 | 1 | 177.117 | 269.186 | 12.551 | 16.355 | 27.82 | 17.201 | 172.006 |
| 5 | 2 | 188.149 | 295.418 | 13.127 | 17.506 | 26.34 | 16.307 | 163.065 |
| 10 | 1 | 380.324 | 539.488 | 27.636 | 46.171 | 25.16 | 15.872 | 158.725 |
| 10 | 2 | 343.771 | 486.688 | 29.137 | 46.122 | 28.34 | 16.468 | 164.683 |
| 20 | 1 | 663.365 | 872.076 | 51.132 | 88.407 | 27.41 | 17.752 | 177.518 |
| 20 | 2 | 711.294 | 1235.493 | 53.337 | 104.552 | 26.18 | 16.738 | 167.382 |
| 40 | 1 | 1475.423 | 2343.221 | 112.637 | 251.780 | 22.79 | 15.877 | 158.770 |
| 40 | 2 | 1315.494 | 1896.726 | 120.262 | 252.525 | 25.87 | 16.519 | 165.187 |
| 80 | 1 | 2470.216 | 3408.418 | 234.336 | 470.294 | 21.93 | 17.284 | 172.838 |
| 80 | 2 | 2394.602 | 3323.862 | 231.657 | 491.275 | 23.41 | 17.618 | 176.179 |

- 각 두 회차를 독립적으로 표시했다. 서로 다른 회차의 p95를 평균해 전체 p95로 주장하지 않았다. 성공 burst는 처음 요청부터 마지막 성공까지의 구간이고 지속 최대 처리량은 아니다.
- DB 대기: ClientRead·WALWrite/WALInit 계열·일부 LockManager 표본을 관찰. deadlock 모든 회차0. rollback은 회차별0~4, 합계8(결과종류별 중복행 제외). 오류와 정합성 위반은0이며 이 통계만으로 rollback 원인이나 특정 병목을 확정하지 않는다.
- 동시5~20의 성공 burst 약25~28/s 이후, 동시40 약23~26/s·80 약22~23/s이며 지연이 늘었다. 이번 같은 PC/작은 합성 행사에서 추가 동시성 효과가 제한됨을 관찰했다. 특정 CPU·연결풀·WAL 병목이나 운영 환경 최대 성능은 미확정이다.
- 목표 동시20은 두 번 모두 평균1초/p95 1.5초/burst20예약/s/오류0/정합성0 기준을 충족했다. 제품 수정 필요 근거가 없어 예약 코드·풀 설정·인덱스·외부 인프라를 바꾸지 않았다. 제품 전후 변경이 없으므로 성능 개선율은 해당하지 않는다.
- 기존 브라우저 검증(재개 후 신규 실행): e2eValidation --tests alfio.e2e.NormalFlowE2ETest --tests alfio.e2e.ValidationBrowserTest / 종료0 / 실제2건, 실패0·오류0·스킵0. 무료 행사 생성·게시·공개 예약·약관동의·최종 화면 및 DB COMPLETE/ACQUIRED/참석자 대조 1건 + 지연 이미지에서 로그인 DOM 사용 회귀 1건이다. 제품 기능 2개 검증이라고 계산하지 않는다.
- 실행 공통: Java25 JAVA_HOME, ALFIO_VALIDATION_POSTGRES_BIN 전용폴더 지정; --console=plain --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=768m' -Pverbose. 반복 시 이미 준비한 프론트 의존성 설치만 -x frontendPnpmInstall -x frontendAdminPnpmInstall로 생략했고 bootJar/테스트 작업은 대상 task가 판단했다. bootJar 최초 실행은 13 task 실제 실행 BUILD SUCCESSFUL.
- 새 XML 검사: scripts/validation/check-results.py에서 reservationLoadValidation 3/0/0/0, e2eValidation 2/0/0/0. 분류 회귀18건은 이전 유효 결과를 재사용; 이후 분류 helper·계약 변경 없음.
- 종료 후 이 실행의 PostgreSQL 프로세스와 alfio-validation-* 임시 디렉터리가 남지 않은 것을 확인했다. 사용자 Chrome·다른 프로세스 일괄 종료 없음.
- 원본 CSV SHA256: c3a994f5567b87de86058a57e8d2cda6707dc947e3cc362b2b9c2981ca4e9793. 원본 CSV/JUnit/로그는 ignored build 아래에만 있고 커밋 대상이 아니다.

## 포트폴리오 근거와 남은 작업

1. 문제: 기존 기능·브라우저 테스트만으로는 몰리는 예약의 지연·거절·최종 재고 안전성을 알 수 없었다.
2. 측정: 독립 합성 DB/앱, 고정 JAR·설정·120개 티켓, 단계별 동시 흐름과 반복 측정을 구현했다.
3. 원인 확인: 지연 증가와 처리량 정체를 관찰했다. 특정 제품 병목의 원인은 미확정이며, 예약 정합성 결함은 이 범위에서 관찰하지 못했다.
4. 개선: 부하 도구·오류 분류 회귀·실제 DB snapshot 검증과 재현·정리 안내를 추가했다. 제품 성능 개선이라고 포장하지 않는다.
5. 재검증: 분류18건, 실제 HTTP/DB3건(부하12회 포함), 기존 브라우저2건을 구분해 확인했다.
- 미검증: Docker/Testcontainers 경로와 기존 Docker DB 통합 테스트, 유료 결제·실제 메일·운영 데이터·다중행사/앱·장시간/고정도착률 부하, 특정 병목 CPU/SQL profiling. 80 동시 흐름을 넘는 한계와 운영SLA는 미확정.
- Docker 사전 확인(읽기 전용): CPU가 SLAT/VM monitor를 지원하지만 VirtualizationFirmwareEnabled=false, HypervisorPresent=false. WSL/가상머신 플랫폼 기능 상태를 확인하고, 사용자 요청대로 설치 전에 승인 질문한다. BIOS SVM 활성화와 재부팅 없이 Docker WSL2 실검증 완료를 주장하지 않는다.
- 커밋·푸시·PR·외부 댓글 없음. 상태는 native 검증 완료 / Docker 설치·컨테이너 검증 사용자 보류인 incomplete.

- Windows 선택 기능 재확인: VirtualMachinePlatform=Disabled, Microsoft-Windows-Subsystem-Linux=Disabled. 상태 조회만 수행했으며 설치·활성화·재부팅은 하지 않았다.

## 사용자 보류 결정

- 2026-10-15 재개 후 설치 여부를 질문했고 사용자가 “지금은 설치 보류”를 선택했다. Docker·WSL 설치/Windows 기능 활성화/BIOS 변경/재부팅을 수행하지 않는다. native 검증 결과와 유효한 측정은 유지하고 컨테이너 검증은 미수행으로 남긴다. 해당 시점에는 커밋·푸시·PR·외부 댓글 권한을 부여받지 않았다. 이후 승인 범위는 다음 절에 기록한다.

## 최종 로컬 판정

- 로컬 실행 ID: issue26-resumed-final. 선언: ai/local-state/issue26/plan-final.json. 최소 QA/독립 Review 근거는 ignored ai/local-state/issue26/*.md에 저장했다. 원본 로그·결과 JSON을 기록 문서에 첨부하지 않았다.
- gate 결과: load-db=passed(manual), regression-e2e=passed(manual), review=passed(manual), docker-db=not_run. 종료1 / 전체 완료 판정 거부. 이는 사용자 설치 보류에 따른 미실행 조건이며 native 검증 실패나 승인 도구 거부가 아니다.
- manual 항목은 실제 원본 CSV/JUnit과 현재 관련 코드·JAR을 대조한 결과다. 기존 부하12회/분류18건을 새 테스트 실행으로 표시하지 않았으며 재개 후 브라우저2건만 신규 실행했다.
- 독립 최종 Review 후 문서의 설치 보류 표현을 재확인했으며 추가 차단사항 없음. 후속 gate 요약만 이 기록에 추가했다. 제품·검증 코드와 실행 안내는 검증 이후 변경하지 않았다.
- 다음 행동: 사용자가 Docker·WSL 설치를 다시 승인하면 필요한 환경 준비·BIOS SVM/재부팅 시점을 확인한 뒤 기존 컨테이너 통합 검증을 수행한다. 현재는 설치를 진행하거나 자동 재개하지 않는다.

## 커밋·PR 후속 승인

- 사용자가 2026-10-15에 “추가검증이 필요없으면 커밋및pr올려줘 docker는 그후에 설치해도된다면”으로 커밋·푸시·PR 생성을 요청했다. 이번 변경의 native 검증 결과와 코드/JAR 해시가 동일하며 원격 main도 기준 HEAD와 일치함을 다시 확인했다. 원본 부하·DB·브라우저 검증을 불필요하게 반복하지 않았다.
- 기존 PR 워크플로우는 PostgreSQL 15/16/17 빌드·통합 검사와 Docker 기반 기존 E2E를 실행한다. 로컬 Docker 설치는 PR 이후로 보류하며, 원격 CI의 실행/통과 여부는 로컬 검증과 별개다. CI 통과를 미리 주장하지 않는다.
- PR은 Refs #26으로 연결한다. Docker 부하 시나리오, 장시간 지속부하, 특정 병목 profiling과 전체 이슈 완료는 여전히 미검증이다. 기존 gate의 docker-db 미실행을 통과로 바꾸지 않는다.
- 공개할 파일은 신규 부하·분류 검사, portable 검증 DB 지원, 전용 Gradle task, 실행 문서와 집계 작업 기록 7개뿐이다. 원본 CSV/JUnit/로그·로컬 실행 영수증·런타임 설치 폴더는 제외한다. 사용자 데이터·제품 예약 API·운영 설정·DB 마이그레이션을 변경하지 않았다.
- 커밋·PR 생성은 승인됐지만 병합·배포·Docker/WSL 설치·재부팅·별도 외부 댓글 등록은 이번 요청으로 실행하지 않는다.
