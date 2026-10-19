# 이슈 #47 이미지 URL SSRF 수정 기록

- 상태: 필수 검증·자체 검토 완료. 단일 로컬 커밋 대상 기록.
- 범위: 이미지 URL 다운로드의 보안 알림 #6·#7. 이슈 #47의 최신 본문을 작업 시작 시 확인했다.
- 실행 방식: 현재 Codex가 구현·검증·QA·리뷰를 순차 수행했다. 독립 검토가 아니다.
- 초기 Git: main, 기존 변경 없음. 최신 원격 main을 확인하고 fix/47-image-download-ssrf에서 작업했다.
- 환경: macOS, Corretto Java 25.0.4.1, Gradle Wrapper 9.7.1, 기존 Jetty client 12.1.12. 모든 명령은 저장소 루트 기준이다.
- 적용 지침: AGENTS.md, ai/WORKFLOW.md, ai/routing.md, ai/templates/work.md, ai/roles/verification.md, ai/roles/review.md, docs/conventions/git.md, docs/harness/local-validation.md의 선언·실제 건수·수동 근거·무효화 절, docs/testing/automatic-validation.md의 빌드·증거·결과 정리 절.
- 계획: 새 회귀 테스트로 수정 전 재현 → 주소·DNS·연결 정책 수정 → 회귀·컴파일 → 자체 QA·리뷰 → 게이트 → 문서·스테이징 검사 → 단일 로컬 커밋.

## 수정 전 재현

`FileDownloadManager.downloadFile()`은 HTTP·HTTPS 스킴만 검사했다. 행사·구독의 `fetchImage()`는 같은 다운로더 결과를 이미지 검사 후 저장했다. 공유 JDK HTTP 클라이언트의 리다이렉트 정책은 설정 코드상 기본값인 비허용이었다. 수정 전 리다이렉트 실연결 실험은 수행하지 않았다.

합성 1×1 PNG를 제공하는 루프백 서버를 만들고 새 회귀 테스트 `internalImageMustNotReachServerThroughDownloaderOrControllers()`를 먼저 실행했다.

```sh
./gradlew test --tests alfio.manager.FileDownloadManagerTest --console=plain --no-build-cache --rerun-tasks
```

- 실제 테스트 1건, 실패 1건, 명령 종료 코드 1. 제품·테스트 소스 컴파일은 성공했다.
- 다운로더 직접 호출 1회와 행사·구독 `fetchImage()` 직접 호출 각 1회에서 서버가 총 3건을 수신했다.
- 파일 반환 1건, 실제 이미지 검사 통과 및 모의 저장 각 2건이었다. 차단 기대값을 두었으므로 테스트가 실패했다.
- 실제 HTTP와 제품의 이미지 검사 코드를 사용했다. 컨트롤러는 private 메서드를 직접 호출했으며 저장은 모의 처리했다. 전체 앱·인증·DB 통합 테스트가 아니다.
- 재현 URL은 `http://127.0.0.1:<임시포트>/internal.png`이다. 실제 내부 서비스·클라우드 메타데이터·개인정보·결제·메일은 사용하지 않았다.

## 수정 내용과 변경 파일

| 파일 | 변경 내용 |
|---|---|
| src/main/java/alfio/manager/FileDownloadManager.java | 주소 정책·DNS 결과 전체 검사, 검증한 주소로 연결 고정, 원래 호스트명 보존, 이미지 전용 클라이언트, 리다이렉트 비허용 |
| src/main/java/alfio/config/DataSourceConfiguration.java | 이미지 다운로더의 내부 생성자 연결 변경. 다른 기능의 공유 JDK HTTP 클라이언트 설정은 유지 |
| src/test/java/alfio/BaseTestConfiguration.java | 기존 통합 테스트용 다운로더 모의 구현의 생성자 연결 갱신 |
| src/test/java/alfio/manager/FileDownloadManagerTest.java | 수정 전후 재현, 주소 표현·IPv4·IPv6·DNS·정상 PNG·컨트롤러·HTTPS·리다이렉트 회귀 |
| src/test/java/alfio/manager/FileDownloadManagerIntegrationTest.java | 기존 성공·404 대조를 통제된 공개 DNS 결과와 실제 루프백 HTTP로 수행하고 수신 횟수 검증 |
| src/test/java/alfio/manager/ImageDownloadTestSupport.java | 공개 주소 선택을 검증한 후 테스트 서버로 소켓 주소만 대체하는 테스트 도우미 |
| docs/harness/records/2026-10-19-issue47-image-ssrf.md | 이 작업의 수정 전후 결과와 한계 |

- IPv4의 루프백·사설망·링크 로컬·미지정·공유 주소·멀티캐스트·예약·문서용·벤치마크 범위를 거부한다. IPv6는 전역 유니캐스트 범위를 기본으로 하고 특수·문서·전환 범위를 추가 거부한다. 일부 특수 목적의 전역 도달 가능 주소도 보수적으로 거부한다.
- 호스트명의 모든 DNS 응답을 검사하며 하나라도 금지 주소이면 요청하지 않는다. 빈 응답·조회 실패도 요청하지 않는다. DNS 결과는 요청별로 새로 검증한다.
- 축약 IPv4·단일 정수·앞자리 0·16진수 등 모호한 숫자 표기와 사용자 정보가 있는 URL·영역 식별자가 있는 IPv6 URL을 거부한다. IPv4 매핑·호환 IPv6, NAT64 및 6to4 등의 우회를 검토하고 내부 주소 및 전환 범위를 차단한다.
- 검증된 주소의 바이트로 해석 완료된 소켓 주소를 생성한다. 이때 원래 URL의 호스트명을 보존해 HTTP Host·TLS 인증서 호스트명 검사·SNI를 유지한다. 연결 경로는 이 주소 목록만 사용하므로 검사 뒤 시스템 DNS를 다시 조회하지 않는다.
- 이미지 요청마다 전용 클라이언트를 만들고 종료한다. 리다이렉트는 계속 따르지 않는다. 다른 기능의 공유 클라이언트나 프록시 설정을 재사용하지 않는다. 연결 제한은 10초, 요청·유휴 제한은 30초다. 최초 운영체제 DNS 조회 시간은 이 제한에 포함되지 않는다.
- 최종 변경에는 새 의존성·공개 REST API 변경·DB 마이그레이션이 없다. 기존 Jetty 의존성을 사용하며 빌드 설정은 변경하지 않았다. 원래 URL·DNS 응답·서버 응답·예외 덤프를 새 경고 로그에 기록하지 않는다.
- 연결 주소 고정에는 기존 라이브러리의 [HttpClient](https://javadoc.jetty.org/jetty-12.1/org/eclipse/jetty/client/HttpClient.html)와 [SocketAddressResolver](https://javadoc.jetty.org/jetty-12.1/org/eclipse/jetty/util/SocketAddressResolver.html)를 사용했다. 설치된 버전의 소스도 확인했다.

## 수정 후 비교와 실제 연결 범위

| 검증 대상 | 수정 전 | 수정 후 | 검증 방식·한계 |
|---|---|---|---|
| 동일한 루프백 PNG: 다운로더·행사·구독 | 실제 수신 3건, 파일·이미지 저장 경로 수용 | 실제 수신 0건, 반환·저장 없음 | 동일한 합성 데이터와 직접 호출 조건. 실제 HTTP 서버 수신 수로 비교 |
| IPv6 루프백 | 별도 실험 없음 | 실제 IPv6 서버 수신 0건 | 루프백 IPv6 서버 실기동 |
| 주소 표현·호스트명 | 별도 실험 없음 | 루프백·숫자 우회·localhost·IPv4 포함 IPv6 요청 모두 거부 | 실제 루프백 서버 수신 0건과 전송 생성 금지 단언. 모호한 숫자 표기는 DNS 전 거부 |
| 주소 정책 | 검사 없음 | 금지 주소 37개 거부, 공개 주소·경계 10개 허용 | 주소 객체의 분류 검사. 해당 주소들에 네트워크 연결하지 않음 |
| DNS 혼합·실패 | 검사 없음 | 공개·내부 혼합의 양 순서, IPv6 루프백, 빈 응답·조회 실패 거부 | DNS 합성 응답. 전송 생성 전 차단 검증 |
| DNS 변경 | 연결 주소 고정 없음 | 첫 요청 조회 1회·소켓 주소 선택 1회·PNG 수신 1건. 다음 요청의 변경된 답은 거부 | 합성 DNS가 첫 응답은 공개 주소, 다음은 루프백을 반환. 실제 연결 주소는 테스트 도우미에서 루프백으로 대체 |
| 정상 HTTP PNG·행사·구독 | 루프백 PNG 수용으로 확인 | PNG 원본 바이트·이름·형식 보존. 다운로더 1건, 두 컨트롤러 2건의 실제 수신 | 공개 IPv4 DNS 답을 주입하고 선택 주소를 단언한 후 루프백으로 연결. 저장만 모의 처리 |
| 공개 IPv6 DNS 대조 | 미실행 | 선택된 IPv6 주소 단언, PNG 수신 1건 | DNS 답 및 연결 주소 대체. 실제 공용 IPv6 망 통신은 아님 |
| 정상 HTTPS·호스트명·SNI | 미실행 | 정상 PNG 수신 1건, 원래 SNI 확인. 인증서 호스트명 불일치 시 추가 HTTP 수신 0건 | 임시 키·인증서를 메모리에서 합성하고 테스트 신뢰 저장소만 주입. 소켓은 루프백. 호스트명 검사는 활성화 |
| 리다이렉트 | 설정 코드상 비허용 | 301·302·303·307·308 응답 수신 각 1건, 루프백 이동 대상 수신 0건 | 실제 HTTP 응답. 각 최초 연결의 주소만 테스트 도우미에서 대체 |
| 기존 성공·404 | 기존 테스트 존재 | 성공·404 각각 실제 HTTP 수신 1건 | WireMock 사용. 이름에 IntegrationTest가 있으나 앱·DB 통합 테스트가 아님 |

## 실행 명령과 최종 게이트

최종 로컬 실행 ID는 `issue47-image-complete`이다. 선언은 `ai/local-state/issue47-plan.json`, 도구 버전 요약과 JUnit 출력 설정은 같은 Git 제외 디렉터리에 있다. 필수 항목은 회귀·구조 검사와 수동 라이선스 확인·자체 QA·자체 리뷰이며 독립 수행은 요구하지 않았다. 원본 로그·XML·실행 영수증을 커밋하지 않는다.

```sh
python3 -B ai/harness/run.py init issue47-image-complete ai/local-state/issue47-plan.json
python3 -B ai/harness/run.py run issue47-image-complete regression
./gradlew test --tests 'alfio.manager.FileDownloadManager*Test' --console=plain --no-build-cache --rerun-tasks -I ai/local-state/issue47-junit.gradle -Dharness.reports=ai/local-state/issue47-complete-xml
./gradlew licenseMain licenseTest --info --console=plain --no-build-cache --rerun-tasks
python3 -B ai/harness/run.py run issue47-image-complete structure
python3 -B ai/harness/run.py gate issue47-image-complete
python3 -B ai/harness/run.py resume issue47-image-complete
```

위 Gradle 테스트 명령은 하네스 `regression`이 실행하는 명령이며 별도의 추가 실행을 뜻하지 않는다. 수동 항목은 각각 `begin` → 실제 확인 → `record`로 연결한다.

| 항목 | 결과 |
|---|---|
| 최종 회귀 | 종료 0, 실제 15건(신규 13·기존 2), 실패·오류·스킵 0. :test 실제 실행 확인 |
| 컴파일 | 회귀 명령에서 --rerun-tasks로 제품·전체 테스트 소스를 다시 컴파일. 명령 종료 0. 컴파일은 테스트 건수에 포함하지 않음 |
| 라이선스 | 명령 종료 0. 변경 Java 6개 파일별 Header OK를 상세 출력에서 수동 확인. 플러그인의 UP-TO-DATE는 자동 통과로 계산하지 않음 |
| 구조 검사 | python3 -B ai/tests/check.py 실제 실행, 종료 0 |
| QA·리뷰·gate·resume | 자체 QA·리뷰 완료. gate·resume 모두 종료 0, 모든 필수 항목 유효·통과 |
| 스테이징 문서·diff 검사 및 커밋 | 이번 변경 7개 파일만 스테이징. 전체 cached diff·공백 검사 및 금지 정보·산출물 제외 검사 완료 |

## 실패·진단·자체 검토

- 임시 HTTP 클라이언트 후보 구현에서 인터페이스 구현 누락으로 컴파일 오류 2건이 발생했다. 수정 후 컴파일했다. 최종 구현은 기존 Jetty를 사용하므로 신규 의존성 후보와 빌드 변경은 제거했다.
- 중간 회귀에서는 12건 통과를 두 차례 확인했다. HTTPS 추가 후 14건 중 1건이 실패했고, 단일 HTTPS 진단 1건씩 두 차례도 실패했다. 원인은 TLS 소켓 주소의 호스트명 보존이었다. 주소 바이트와 원래 호스트명을 함께 보존한 후 14건이 통과했고, 추가 회귀 15건도 통과했다. 최종 선언의 결과와 혼동하지 않는다.
- 초기 숫자 표기 실험에서 앞자리 0을 JVM이 다르게 해석해 외부 주소 연결을 시도할 가능성을 발견했다. 성공 응답의 증거는 없으며 정상 다운로드 근거로 사용하지 않았다. 최종 코드는 모호한 표기를 DNS 전에 거부하며 해당 테스트는 전송 객체 생성 자체가 실패 조건이다.
- 라이선스 플러그인은 실제 헤더 검사에도 UP-TO-DATE를 출력했다. 자동 static 항목은 이를 거부했다. 상세 출력에서 파일별 실제 검사 사실을 확인하는 수동 QA 선언으로 변경했고, 선언 변경에 따라 회귀도 새 출력 경로에서 다시 실행했다. UP-TO-DATE를 자동 검사 통과로 기록하지 않는다.
- 자체 리뷰 범위: 최종 diff, 주소 경계, DNS 전체 응답·연결 고정, 호스트명·SNI, 리다이렉트, 공유 클라이언트 영향, 컨트롤러 이미지 경로, 테스트의 실제 HTTP/모의 경계 및 문서 제외 정보.

## 미검증 범위와 남은 위험

- 실제 공개 이미지 서버, 운영 DNS·인터넷 라우팅·공용 IPv6 망은 사용하지 않았다. 정상 대조는 DNS 답과 소켓 주소를 통제한 실제 루프백 HTTP·HTTPS이며 외부 서비스 가용성을 보장하지 않는다.
- 전체 앱의 인증·DB·브라우저 E2E, PostgreSQL 통합 테스트·DB 마이그레이션 검증은 이 다운로드 변경의 필수 항목으로 선택하지 않았다. 컨트롤러 이미지 검사는 실제 코드지만 저장은 모의 처리다. 컴파일을 앱 통합 테스트 통과로 표시하지 않는다.
- 주소 정책은 애플리케이션에서 확인 가능한 IP 범위를 기준으로 한다. 배포 환경의 특수 라우팅·공개 주소를 통한 별도 중계 서비스까지 판별하는 네트워크 방화벽은 아니다. 최초 OS DNS 조회 시간과 다운로드 크기 제한은 별도 개선 범위다.
- 리다이렉트 이미지는 기존처럼 다운로드하지 않는다. 특수 목적 범위는 보수적으로 거부하므로 해당 범위에서 제공하는 이미지는 허용되지 않는다.
- 원격 푸시·PR 생성·이슈 댓글·보안 알림 종료·원격 CodeQL 재분석은 수행하지 않는다. 코드 수정 결과만으로 보안 알림의 자동 종료를 주장하지 않는다.

## 최종 확인

- 최종 제품 코드는 회귀 실행 이후 변경하지 않았다. 이후 변경은 이 결과 기록과 Git 제외 근거뿐이다.
- 필수 회귀·구조 검사, 수동 라이선스 확인·QA·리뷰와 gate·resume가 모두 통과했다. 로컬 도구가 요구하는 식별 정보는 Git 제외 상태에만 유지했다.
- 이번 변경 7개 파일만 명시적으로 스테이징했다. 문서의 금지 정보·임시 파일·원본 로그·테스트 산출물 제외와 git diff --cached 및 git diff --cached --check를 확인했다. 이 기록·수정 코드·회귀 테스트를 단일 로컬 커밋으로 저장한다.
- 커밋 메시지: `fix: 이미지 다운로드의 내부 주소 접근 차단`. 원격 푸시나 게시 작업은 수행하지 않는다.
