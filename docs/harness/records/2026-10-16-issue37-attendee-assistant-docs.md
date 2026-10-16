# 이슈 #37 명단 비교 비서 평가 기록과 README 정리

- 작업 ID / 날짜 / 상태: issue37 / 2026-10-16 / completed
- 요청·완료 기준: [이슈 #37](https://github.com/WookJaes/alf.io/issues/37), 최신 제목 `[docs] 명단 비교 비서 평가 기록 정리와 README 안내 보완`, 본문 갱신 시각 2026-10-16 07:32:38 UTC 확인. 기존 네 종류 평가 기록 정리·한계 명시·영문 README 안내·main 코드 대조·합성 화면 재검증·문서 커밋. 새 사람 평가·유료 호출·외부 게시 제외.
- 실행 모드·실제 담당: 현재 Codex 순차 수행, 자체 QA·Review, 하위 에이전트 미사용.
- 초기 Git: `fix/35-reservation-ticket-queries`, 추적·미추적 변경 없음. `git fetch origin main` 후 `origin/main` = `cdb392981f9106d92f805fc5da413cd8eb18a1bf`. 해당 최신 main에서 `docs/37-attendee-assistant-evaluation` 생성. 기존 Git 제외 평가 자료 보존.
- 수정 범위: README.md, docs/attendee-assistant-evaluation.md, docs/attendee-assistant-manual-evaluation.md, 이 신규 기록. 제품·프론트·API·DB·보관/접근 정책·의존성·보호 문서 변경 없음.
- 계획: 원본·후속 기록 확인 → 문서 정리 → 링크·코드 대조 → 기존 모의 Chrome E2E·화면 확인 → 자체 리뷰·gate → 요청 파일만 스테이징·diff 확인·커밋.
- 재현 환경·위치: 저장소 루트, macOS aarch64, Corretto Java 25.0.4.1, Gradle Wrapper 9.7.1. Docker 29.5.2·PostgreSQL 16.13 (Debian 16.13-1.pgdg13+1)·Chrome 155.0.8059.80·Selenium 4.43.0 기반 격리 앱, dev/disable-jobs, loopback 모의 응답과 합성 키·합성 데이터. 실제 API 키 불필요.
- 검증 대상: 위 main HEAD의 제품 코드와 이번 문서 diff. 과거 측정은 기존 기록과 Git 제외 원본을 대조했으며 새 실행으로 표시하지 않는다.
- 로컬 실행 ID·선언: 최종 `issue37-final`, `ai/local-state/issue37-config/plan.json`. structure/e2e/qa/review 필수. QA·Review 독립 수행 요구 없음.
- 근거: Git 제외 `ai/local-state/issue37-config/`(선언·검토 요약), `ai/local-state/issue37-final-xml/`(새 JUnit), `ai/local-state/issue37-artifacts/`(합성 화면). 원본 로그·결과 JSON·실제 개인정보·비밀값은 커밋하지 않는다.

## 변경과 검증

| 완료 기준 | 변경·확인 내용 | 명령·절차 | 종료 코드·실제 건수 | 증거·결과 |
|---|---|---|---|---|
| 기록 구분·시점·원본 보존 | 초기 모의·실제 연결·사용자 수동·에이전트 사용을 구분하고 과거 미호출 범위 명시 | 기존 3개 작업 기록, 수동 원본·연결 응답 요약·에이전트 시간/DOM 근거 대조 | 자동 테스트 아님 | 평가 문서의 출처별 표, 원본 파일 미수정 |
| 측정값·비용·한계 | 471초/204.15초·4/4·수정 횟수·두 별도 호출의 사용량/지연 보존, 실제 청구액 미확인과 기존 추정 비용 구분 | 원본·후속 채점 정정·화면 증거 대조 | 자동 테스트 아님 | 사람 시간 절감률 해석 금지, 에이전트 클립보드 내용 미검증 보존 |
| 영문 README | 이 포크 기능 요약·사용/평가/절차 링크 추가, 기존 영문 보존 | main README와 diff 대조 | 바이트 대조 통과 | 추가 절·목차 제거 후 main README와 동일, 상세 설정·정책·절차 중복 없음 |
| 링크·앵커·설정·기능 | 설정·기본값·UUID 판정·초안·보관·API를 main 소스와 대조 | 자체 QA 및 `python3 -B ai/local-state/issue37-config/check-links.py` | 종료 0, 로컬 링크·앵커 19개와 README 새 앵커 통과 | 아래 확인 범위 참조 |
| 자동 화면 E2E | 기존 `AttendeeReconciliationE2ETest`만 실행 | `python3 -B ai/harness/run.py run issue37-final e2e` | 종료 0, `:e2eValidation` EXECUTED, 실제1건·실패0·오류0·스킵0 | 2026-10-16 16:39:43–16:40:19 KST, 아래 화면 확인과 구분 |
| 구조 검사·자체 리뷰 | diff·완료 조건·링크 확인 | `run issue37-final structure`, 자체 review, `gate issue37-final` | 구조 검사·gate·resume 각각 종료 0, 자체 QA·Review 통과 | 실제 테스트 건수와 구분 |

## 실패·리뷰·재개

- 초기 sandbox에서 `git fetch`는 `.git/FETCH_HEAD` 쓰기 권한, `gh issue view`는 네트워크로 실패했다. 승인된 fetch를 권한 확장으로 재실행해 성공했고 이슈는 GitHub 읽기 도구로 최신 본문을 확인했다. Docker도 sandbox에서 소켓 접근이 거부되어 합성 E2E를 권한 확장으로 실행했다. 사용자 승인 범위 안에서 재시도했으며 외부 쓰기 없음.
- 대조 소스: `AttendeeAssistantSettings`, `AttendeeReconciliationManager`, `OpenAiDraftClient`, `AttendeeReconciliationRepository`, `ReconciliationStore`, `AttendeeComparator`, `AttendeeCsvParser`, `AttendeeReconciliationApiController`, 관리자 `attendee-reconciliation.ts` 및 메뉴/라우트. 설정 5개·기본 모델·1~30초·dev 전용 loopback endpoint가 사용 안내와 일치한다. UUID 비교·`ID`/`E-Mail`·헤더 포함 레코드번호·대상 3상태, AI의 익명 참조/유형 전송과 표현 선택, 초안 편집·복사·사용량 표시·이력·보관·권한·API 경로를 대조했다. 기존 사용 안내는 수정할 불일치가 없어 유지했다.
- 생략 검증: 새 사람 비교 평가·실제 유료 AI 호출·청구 내역 조회는 요청에서 제외. 전체 제품 회귀·운영 배포·다른 OS/모바일·원격 CI는 문서 변경 영향 밖이며 일반 동작 전체를 보증하지 않는다. 기존 실제 연결·사용량은 과거 증거에 근거하며 이번 실행에서 재측정하지 않는다.
- 첫 실행 `issue37-docs`는 실제 E2E 1건 통과했으나 캡처가 없었다. init script의 `systemProperty`가 기존 Gradle의 `setSystemProperties`에 덮이는 원인을 확인하고 `-Dattendee.e2e.artifacts=...`로 전달해 최종 새 실행에서 캡처했다. 첫 실행 중 겹친 구조 검사 상태는 채택하지 않고 최종 실행에서는 변경 명령을 직렬 수행했다. 첫 실행과 최종 실행을 합산해 최종 테스트 건수를 늘리지 않는다.
- 리뷰 방식: 현재 Codex의 자체 리뷰. 네 종류 기록과 시점·정정 근거·출처·수치·비용·한계, 기존 README 보존과 문서 4개 범위, 이슈 완료 조건 8개를 대조했다. 제품 코드·정책·과거 작업 기록 변경 없음.
- 최종 판정: `python3 -B ai/harness/run.py gate issue37-final`과 `resume issue37-final` 모두 종료 0. structure/e2e는 실제 실행, QA·Review는 자체 수동 확인으로 통과. 이슈 #37 완료 조건 8개 충족. 사용자 승인 커밋 범위는 문서 4개, 제목은 `docs: 명단 비교 비서 평가 기록과 안내 정리`이며 실제 해시는 Git 이력과 완료 보고로 확인한다. 커밋 직전 `git diff --cached` 및 `git diff --cached --check`로 범위를 재확인한다. 푸시·PR 생성·이슈 수정·댓글·종료는 수행하지 않는다.


## 자동 테스트와 화면·문서 대조의 구분

최종 자동 실행 명령은 다음과 같다. 새 JUnit XML은 기존 보고서를 복사하지 않았다. 테스트 자체 시간 24.715초는 사람 처리시간이 아니다.

```sh
./gradlew e2eValidation --tests alfio.e2e.AttendeeReconciliationE2ETest --console=plain --no-daemon --no-build-cache -Pverbose -I ai/local-state/issue37-config/reports.gradle -Dattendee.e2e.artifacts=ai/local-state/issue37-artifacts
python3 -B ai/tests/check.py
python3 -B ai/local-state/issue37-config/check-links.py
```

- 자동 화면 흐름: 실제 로그인·메뉴 진입·합성 CSV 업로드 → 정상 1/확인 필요 3과 CSV 레코드 3/4 근거 → 확인 항목 선택 → 모의 AI 초안 → textarea 편집 → 복사 버튼 → 실제 브라우저 클립보드 읽기와 수정 문자열 일치 assertion 통과. 재조회·삭제·AI 오류·헤더/본문 시간 초과·잘못된 참조 후 결과 보존과 권한 검사도 통과했다. 별도 사람 수동 조작 평가가 아니다.
- 화면 이미지 자체 QA: `ai/local-state/issue37-artifacts/comparison.png`를 직접 열어 메뉴·집계·필터·이력·중복/불일치·필수열 오류를 확인했다. 캡처는 오류 안내 뒤 비교 화면이며 초안 편집·복사는 새 E2E의 실제 화면 assertion과 클립보드 내용 대조로 확인한다. 이미지 단독으로 해당 흐름 통과를 주장하지 않는다.
- 문서 대조: Settings·Controller·Manager·Client·Comparator·Parser·Store·Repository와 관리자 Lit/Angular 라우트·메뉴를 현재 main에서 읽었다. 관련 문서의 로컬 링크·앵커 19개 및 README 새 목차 앵커가 유효하다. 과거 가격 출처는 기록 시점과 함께 보존하며 현재 가격 검증으로 표시하지 않는다.
- 컴파일: 별도 컴파일·프론트 빌드 검사를 선언하거나 전체 신규 빌드 통과로 보고하지 않는다. 기존 Gradle E2E의 현재 앱 준비와 실제 테스트 통과를 확인했다. 문서 변경에 전체 제품 회귀는 추가하지 않았다.
- 최종 검증 대상 제품 해시: `6564cdf8dbb70c236707091c05ca97eccf1a2a9b68a473c27b274bc6658a1d1d`. 이후 제품·문서 본문 변경 없이 이 결과 요약만 갱신한다. 커밋 후 새 HEAD에서 E2E를 재실행한 것으로 표시하지 않는다.

## 이슈 #37 완료 조건 대조

| 이슈 본문의 완료 조건 | 충족 여부·근거 |
|---|---|
| 1. 모의·실제 호출·사용자 수동·에이전트 기록 구분 | 충족 — 대표 평가 문서 첫 표와 출처 |
| 2. 시점·조건 보존, 과거 미호출 설명 정리 | 충족 — 초기/후속 구분, 과거 기록 미수정 |
| 3. 시간·정확도·수정·모델·호출·토큰·지연·비용 출처 | 충족 — 결과표와 사용량표, 실제 청구액/추정/미기록 구분 |
| 4. 작업자·시간 범위 차이와 해석 한계 | 충족 — 사용자/에이전트·사전 지식·질문/도구 대기 차이 명시, 생산성 향상률로 해석하지 않음 |
| 5. README 기능 요약·사용/평가 링크 | 충족 — 신규 절과 목차, 세 문서 링크, 상세 정책은 기존 안내 유지 |
| 6. README 영문·기존 내용 보존, 운영 문서 한국어 | 충족 — 기존 README 바이트 대조, 추가 영문과 한국어 문서 |
| 7. main 코드·링크·설정 대조와 합성/모의 실제 화면 | 충족 — 링크 검사·자체 소스 대조·최종 Chrome E2E 1건·캡처 확인, 신규 유료 호출 없음 |
| 8. 기존 측정값 보존·합성 사례 일반화 금지 | 충족 — 471초·204.15초와 원본 보존, 일반 효과 검증 미실행 명시 |

새 사람 평가·청구액 확인·과거 에이전트 측정의 실제 클립보드 내용 확인은 여전히 미실행/미확인이다. 이 한계는 이번 기록 정리의 미충족 조건이 아니며 기존 측정의 증거 범위를 확대하지 않는다. 이슈 #25 전체 평가나 일반적인 사람 생산성 효과를 새로 완료했다고 보고하지 않는다.
