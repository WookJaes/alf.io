# 이슈 25 실제 AI 연결 검증

- 작업 ID / 날짜 / 상태: issue25-live-ai-validation / 2026-10-14 / completed
- 요청: 로컬에서 실제 AI 기능을 검증하고 이번 실행 결과만 기록한다. 원격 푸시는 하지 않는다.
- 완료 기준: 합성 명단 비교, 실제 OpenAI 응답, 초안 수정·복사, DB 결과 재조회와 실행 증거 확인.
- 실행 모드·담당: 현재 Codex의 직접 실행 및 자체 QA·Review.
- 브랜치·검증 대상 HEAD: `feat/25-attendee-assistant`, `e32e7d83004b4522160c1f2d45af25c95b1d297e`.
- 시작 시 기존 변경: 이번 작업의 결과 기록 파일만 미추적 상태. 제품 코드 변경 없음.
- 실행 환경: macOS, IntelliJ의 환경 변수 설정된 zsh 터미널, Gradle Wrapper, Docker 기반 PostgreSQL 격리 DB, Chrome 자동화.
- 명령 실행 위치: alf.io 저장소 루트.
- 테스트 시작: 2026-10-14 13:52:58 KST, 실제 테스트 소요 21.459초.
- 로컬 검토 실행 ID: `issue25-live-result-1353`.
- 검토 선언: `ai/local-state/issue25-live/evidence-plan.json`. 자체 QA·Review이며 독립 수행은 요구하지 않는다.

## 실행 명령과 결과

```zsh
python3 ai/local-state/issue25-live/run.py
```

실행 파일은 다음 Gradle 명령을 실행한다. 환경 변수의 비밀값은 명령 인자에 넣지 않는다.

```zsh
./gradlew -I ai/local-state/issue25-live/live.gradle attendeeLiveValidation --console=plain --no-daemon --no-build-cache
```

`LIVE_VALIDATION_EXIT_CODE=0`, `BUILD SUCCESSFUL in 33s`를 터미널에서 확인했다. `:attendeeLiveValidation`은 실제 실행됐으며 JUnit XML의 실제 testcase는 1건, 실패·오류·스킵은 모두 0건이다. 컴파일 및 프론트 빌드는 `UP-TO-DATE`였으므로 이번에 새로 컴파일했다고 판단하지 않는다.

| 완료 기준 | 이번 실행 확인 내용 | 결과 |
|---|---|---|
| 실제 관리자 화면 로그인·CSV 비교 | 합성 행사·계정·CSV 사용, 정상 일치 1개·확인 필요 3개 | 통과 |
| 실제 OpenAI 연결 | 공식 Responses API, 모의 서버 주소 지정 없음, 실제 요청 1회 | 통과 |
| 선택 항목 초안 생성 | F-2·F-3·F-4에 연결된 초안 3개, 선택 모델 표시 | 통과 |
| 초안 편집·복사 | 문구 수정 후 실제 브라우저 클립보드 값을 수정한 문자열과 대조 | 통과 |
| DB 보관 결과 재조회 | 새로고침 후 이력에서 비교를 다시 열고 확인 필요 3개 대조 | 통과 |
| DB 초기화 | 격리 DB의 앱 마이그레이션 오류 없음 확인 | 통과 |
| 화면·문구 QA | 생성 결과 화면과 안전한 응답 요약 직접 확인 | 통과 |

## 실제 모델 응답과 사용량

| 항목 | 측정 결과 |
|---|---|
| 모델 | `gpt-5.4-mini-2026-03-17` |
| 실제 공급자 요청 | 1회 |
| 입력 토큰 | 264 |
| 캐시 입력 토큰 | 0 |
| 출력 토큰 | 95 |
| 서버 표시 응답 시간 | 3,374ms |
| 생성 버튼 클릭부터 결과 표시까지 | 3,613ms |

F-2는 CSV 중복과 이메일 불일치를 함께 설명하고 중복 기재 여부·현재 사용할 이메일 확인을 요청했다. F-3는 선택한 행사의 비교 대상에 없는 CSV 항목에 대해 행사·티켓 정보 확인을 요청했다. F-4는 CSV에 없는 시스템 항목에 대해 명단의 포함 범위 확인을 요청했다. 설명과 초안은 비교 근거에 일치하며 예약 오류·취소·결제 상태를 임의로 단정하지 않았다. 초안은 허용된 표현 조합으로 렌더링됐으며 실제 이메일·티켓 UUID를 포함하지 않았다.

실제 청구액은 OpenAI 사용·청구 내역을 조회하지 않아 확인하지 않았다. 운영자의 수동 처리 시간과의 비교, 다회 실행에 따른 품질·지연 분포, 운영 배포 검증은 이번 확인 범위에 포함하지 않는다.

## 증거·리뷰

아래 증거는 Git 제외 디렉터리에 보관하며 원본 로그·결과 JSON·비밀값을 커밋에 첨부하지 않는다.

- 테스트 소스: `ai/local-state/issue25-live/src/alfio/e2e/AttendeeReconciliationLiveE2ETest.java`.
- JUnit: `ai/local-state/issue25-live/xml/TEST-alfio.e2e.AttendeeReconciliationLiveE2ETest.xml`.
- JUnit SHA-256: `a21fe153cd4f5d925e8e4b1c109d6ea8c21ad8b80d9a919f526bb2945867213c`.
- 응답 요약: `ai/local-state/issue25-live/artifacts/safe-evaluation.json`.
- 화면: `ai/local-state/issue25-live/artifacts/drafts.png`.
- 자체 QA·Review 근거: `ai/local-state/issue25-live/qa.md`, `ai/local-state/issue25-live/review.md`.

실행 설정·테스트 assertion·JUnit·화면·문구와 이 기록을 대조했다. 테스트는 합성 데이터와 격리된 DB를 사용하며 실제 행사·예약 데이터에 적용하지 않았다. 테스트 XML에서 OpenAI 키·개인 키 패턴이 검출되지 않았고 이 문서에는 비밀값을 기록하지 않았다. 자동 테스트 실행 자체는 IntelliJ 터미널에서 수행했으며 로컬 게이트는 그 실행의 증거를 확인한 자체 QA·Review를 판정한다.

- gate 판정: `python3 -B ai/harness/run.py gate issue25-live-result-1353` 종료 코드 0, 자체 QA·Review 모두 `passed (manual)`, 완료 판정 통과.
- 검증 후 제품 코드·실행 설정 변경: 없음.
- 커밋 범위: 사용자 요청에 따라 이 검증 기록만 포함한다.
- 원격 푸시: 수행하지 않음.
