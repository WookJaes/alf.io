# PR #51 공개 IPv6 리터럴 검토 의견 반영

- 상태: 수정·검증·자체 검토 완료, 로컬 커밋 대상.
- 범위: 공개 IPv6 리터럴 이미지 URL의 연결 고정 단계 호스트 비교와 회귀 검증.
- 실행 방식: 현재 Codex가 수정·검증·QA·리뷰를 순차 수행했다. 독립 리뷰가 아니다.
- 초기 Git: fix/47-image-download-ssrf, 기존 사용자 변경 없음. 로컬 출발점과 PR 최신 커밋이 같음을 확인하고 기존 PR 브랜치에서 이어서 작업했다. 식별 정보는 Git 제외 상태에서만 유지한다.
- 지침: AGENTS.md, ai/WORKFLOW.md, ai/roles/review.md, docs/conventions/git.md, docs/harness/local-validation.md. 이전 작업 기록의 민감정보 제외 원칙을 유지했다.
- 환경: macOS, Corretto Java 25.0.4.1, Gradle Wrapper 9.7.1, Jetty client 12.1.12. 명령은 저장소 루트 기준이다.
- 계획: 공개 IPv6 리터럴 회귀의 수정 전 실패 확인 → 호스트 비교 형식 수정 → 관련 회귀·컴파일·문서 구조·실제 헤더 검사 → 자체 QA·리뷰·게이트 → 로컬 커밋.
- 새 의존성·공개 API 변경·DB 마이그레이션 없음. 푸시·PR 본문 수정·댓글 게시·리뷰 스레드 해결은 이번 요청 범위가 아니다.

## 원인과 수정

DNS 검증용 IPv6 호스트는 대괄호가 없고 Jetty 연결 resolver가 받는 IPv6 호스트에는 대괄호가 있다. 기존 코드는 이를 직접 비교해 공개 주소 검사에 성공해도 연결을 거부했다. 기존 회귀는 호스트명의 IPv6 DNS 응답만 검사해 IPv6 주소를 직접 적은 URL을 놓쳤다.

| 변경 파일 | 반영 내용 |
|---|---|
| src/main/java/alfio/manager/FileDownloadManager.java | Jetty의 HostPort.normalizeHost로 검증한 호스트를 연결 경로와 같은 형식으로 비교 |
| src/test/java/alfio/manager/FileDownloadManagerTest.java | 축약형·전체 표기의 공개 IPv6 리터럴 URL 회귀 1건 추가. 두 표기의 합성 PNG 수신을 각각 검증 |
| src/test/java/alfio/manager/ImageDownloadTestSupport.java | 소켓 주소의 원래 호스트명 보존 단언도 같은 IPv6 대괄호 형식으로 비교 |
| docs/harness/records/2026-10-19-pr51-ipv6-literal.md | 수정 전후 재현·검증·한계 기록 |

주소 바이트와 원래 호스트명, DNS 결과 전체 검사, 검사한 주소로 연결 고정, 내부 주소·모호한 숫자 표기 차단, 리다이렉트 비허용 및 직접 연결 정책은 유지했다. 프록시 관련 참고 의견은 이번 수정 대상에 포함하지 않았다.

## 수정 전후 검증

공개 IPv6 주소는 합성 DNS 응답·선택 주소 단언에만 사용했다. 실제 HTTP 연결은 테스트 도우미가 루프백 서버로 대체하며 응답은 합성 1×1 PNG다. 재현 URL 형태는 `http://[<합성IPv6주소>]:<임시포트>/image.png`이다. 실제 공개 IPv6 망·내부 서비스·개인정보·결제·메일은 사용하지 않았다.

| 대상 | 수정 전 | 수정 후 | 실제 실행과 한계 |
|---|---|---|---|
| 공개 IPv6 리터럴 URL | 새 회귀 1건 실행·1건 실패, 반환값 null. 첫 축약형에서 실패해 전체 표기 검증은 진행되지 않음 | 축약형·전체 표기 각각 PNG 수신 성공, 서버 수신 총 2건 | 제품의 URL 처리·주소 검증·Jetty 연결 resolver를 사용. DNS 답과 최종 소켓 주소만 통제한 실제 루프백 HTTP |
| 기존 내부 주소 차단 | 이전 작업의 검증 결과 | 다운로더·행사·구독의 동일 루프백 조건에서 수신 0건·저장 없음 재확인 | 실제 HTTP 서버·직접 메서드 호출, 저장 모의 처리 |
| 기존 정상 다운로드·보안 회귀 | 이전 작업의 검증 결과 | DNS 고정·변경·혼합 응답, IPv4·IPv6 정책, HTTPS 호스트명·SNI, 리다이렉트 비허용, 성공·404 대조 통과 | 기존 회귀를 새로 실행. 앱·DB 통합 검증으로 주장하지 않음 |

수정 전 실패 확인 명령:

```sh
./gradlew test --tests alfio.manager.FileDownloadManagerTest.publicIpv6LiteralUsesPinnedConnection --console=plain --no-build-cache
```

종료 코드 1, 실제 테스트 1건·실패 1건·오류·스킵 0건이다. 이 실행의 제품 컴파일 작업은 UP-TO-DATE였으며 새 컴파일 성공으로 계산하지 않았다. 새 테스트 소스는 컴파일됐다. 수정 후 최종 검증은 아래 명령에서 제품·전체 테스트 소스를 강제로 다시 컴파일했다.

## 필수 로컬 검증

- 실행 ID: pr51-ipv6-literal.
- 선언: ai/local-state/pr51-ipv6-plan.json. JUnit 출력 설정과 환경 요약을 inputs에 연결했다.
- 필수 항목: 회귀·구조 검사, 수동 라이선스 확인·자체 QA·자체 리뷰. 독립 수행은 요구하지 않았다.
- 원본 로그·XML·실행 영수증·환경 파일은 Git 제외 디렉터리에만 두고 커밋하지 않는다.

```sh
python3 -B ai/harness/run.py init pr51-ipv6-literal ai/local-state/pr51-ipv6-plan.json
python3 -B ai/harness/run.py run pr51-ipv6-literal regression
./gradlew test --tests 'alfio.manager.FileDownloadManager*Test' --console=plain --no-build-cache --rerun-tasks -I ai/local-state/issue47-junit.gradle -Dharness.reports=ai/local-state/pr51-ipv6-xml
python3 -B ai/harness/run.py run pr51-ipv6-literal structure
./gradlew licenseMain licenseTest --info --console=plain --no-build-cache --rerun-tasks
python3 -B ai/harness/run.py gate pr51-ipv6-literal
python3 -B ai/harness/run.py resume pr51-ipv6-literal
```

Gradle 테스트 명령은 regression이 실제 실행한 명령을 풀어 쓴 것으로 중복 실행을 뜻하지 않는다. 수동 항목은 begin → 실제 확인 → record로 연결한다.

| 검사 | 결과 |
|---|---|
| 제품·테스트 컴파일 및 회귀 | 명령 종료 0. --rerun-tasks로 다시 컴파일. :test 실제 실행, 테스트 16건(단일 클래스 14건·기존 HTTP 대조 2건), 실패·오류·스킵 0 |
| 문서·구조 | python3 -B ai/tests/check.py 실제 실행, 종료 0 |
| 라이선스 | 명령 종료 0. 변경 Java 3개 파일의 실제 Header OK를 상세 출력에서 확인. 플러그인의 UP-TO-DATE 표시를 자동 통과로 계산하지 않음 |
| 자체 QA·리뷰 | 최종 diff·새 XML·호스트 비교 및 내부 주소 정책 보존·문서의 검증 경계를 대조. 미해결 차단 사항 없음 |
| gate·resume | 모두 종료 0, 모든 필수 항목 유효·통과 |
| 스테이징·문서 검사 | 이번 변경 4개 파일만 대상으로 금지 정보·산출물·cached diff 최종 확인 후 커밋 |

## 한계와 최종 조치

- 새 IPv6 리터럴 검증은 HTTP이며 실제 공개 IPv6 망이나 IPv6 리터럴 HTTPS 인증서 배포는 검증하지 않았다. 기존 호스트명 HTTPS 인증서·SNI 회귀는 새로 실행해 통과했다.
- 전체 앱·DB·브라우저 통합 테스트와 원격 CI는 재실행하지 않았다. 이번 변경은 호스트 비교 형식과 테스트 보강에 한정하며 관련 컴파일·16건 회귀·필수 로컬 게이트로 검증한다.
- 제품 코드와 실행 설정은 최종 회귀 이후 변경하지 않았다. 이후 변경은 이 기록과 Git 제외 검토 근거뿐이다.
- 커밋 메시지: `fix: 공개 IPv6 이미지 URL의 호스트 비교 수정`. 로컬 커밋까지만 수행하고 원격 브랜치·PR 본문·댓글·리뷰 상태는 변경하지 않는다.
