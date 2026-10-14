# 이슈 25 OpenAPI 안정성 검사 수정

- 작업 ID / 날짜 / 상태: issue25-ci-openapi / 2026-10-14 / 로컬 수정·검증 completed, 원격 CI 미실행
- 요청: PR #31 CI 실패 관련 코드를 수정·검증하고, 후속 사용자 승인에 따라 커밋·푸시한다.
- 브랜치·기준 HEAD·초기 상태: feat/25-attendee-assistant, 1c60bb6fea725675ff5a8e109a467048573bc140, 작업 트리 변경 없음.
- 실행 모드·담당: 현재 Codex 자체 구현·검증·Review.
- 환경·실행 위치: macOS, Java 25, Gradle Wrapper 9.7.1, Docker PostgreSQL 16.13, 저장소 루트.
- 완료 기준: 신규 API만 기준 명세에 추가하고 기존 API 식별자·동작을 유지, OpenAPI 안정성 및 관련 컨트롤러 테스트 통과.

## 원인과 수정

CI 빌드 PostgreSQL 15·16·17에서 `TestCheckRestApiStability.checkRestApiStability`가 실패했다. 신규 명단 비교 API 5개가 기준 명세에 없으며 기존 이벤트 삭제 API의 자동 생성 operationId가 `delete_5`에서 `delete_6`으로 밀렸다.

신규 API 5개의 Java 메서드에 고유한 이름을 사용해 operationId가 충돌하지 않도록 한다. 이 이름 변경은 HTTP 경로·요청·응답·권한·DB 동작을 변경하지 않는다. 기존 기준 명세를 실제 생성 결과로 갱신하며 기존 경로·operationId·스키마 변경 여부를 대조한다. 새 의존성·DB 마이그레이션은 추가하지 않는다.

## 검증 계획

- 생성: 테스트의 기존 updateDescriptor 절차를 임시 활성화해 명세 생성 후 테스트 소스를 복원한다. 생성 실행은 최종 안정성 통과와 구분한다.
- 최종: updateDescriptor=false 상태에서 OpenAPI 안정성 및 명단 비교 컨트롤러 테스트 실행.
- 리뷰: 기존 API·스키마 동일 여부, 추가 API만 포함 여부, 생성 자료의 비밀정보 제외 및 diff 확인.
- 커밋·푸시 범위: 사용자 승인에 따라 컨트롤러 메서드 이름·OpenAPI 기준 명세·이 기록을 현재 PR 브랜치에 반영한다.

- 로컬 실행 ID·선언: issue25-openapi-final, ai/local-state/issue25-openapi/plan.json. JUnit 증거: ai/local-state/issue25-openapi/final-xml/.

## 실행 결과

- 명세 생성 실행: `python3 ai/local-state/issue25-openapi/regenerate.py` 종료0. 기존 테스트의 재생성 플래그는 생성 중에만 활성화하고 종료 시 원래 소스로 복원했다. 생성 모드의 성공을 최종 안정성 통과로 집계하지 않는다.
- 최종 명령: `./gradlew test --tests alfio.controller.api.TestCheckRestApiStability --tests alfio.controller.api.admin.AttendeeReconciliationApiControllerTest --console=plain --no-daemon --no-build-cache -Dpgsql.version=16.13 -I ai/local-state/issue25-openapi/reports.gradle -Dharness.reports=ai/local-state/issue25-openapi/final-xml`.
- 최종 결과: 종료0, `:test` 실제 실행, XML 2개·실제 4건·실패0·오류0·스킵0. OpenAPI 안정성 1건, 명단 비교 컨트롤러 3건.
- 검토 결과: 기존 API 333개 경로·스키마 298개와 최상위 정보 모두 동일. 신규 3개 경로의 5개 API 및 스키마 8개만 추가. 기존 삭제 API의 `delete_5` 유지. JSON의 실제 생성 결과와 최종 명세 의미는 동일하며 기존 텍스트 순서·형식을 보존했다.
- 전체 컨트롤러 diff: 메서드 이름 5개만 변경, 매핑·인자·응답·권한·로직 동일. `CheckRestApiStability.java` 변경 없음, `updateDescriptor=false` 유지.
- 비밀키 패턴·공백 검사: 신규 diff에서 비밀키 패턴 미검출, `git diff --check` 종료0.
- 자체 Review 근거: `ai/local-state/issue25-openapi/review.md`.
- gate: `python3 -B ai/harness/run.py gate issue25-openapi-final` 종료0, tests 실제 실행 통과·자체 Review 통과.
- 미실행 범위: 전체 제품 테스트·PostgreSQL 15/17 재검증·원격 CI. 이번 오류는 명세 차이이며 PostgreSQL 16.13에서 관련 검사를 확인했다. 실제 OpenAI 호출·화면 재검증은 HTTP 동작을 변경하지 않아 수행하지 않았다.
- 검증 후 제품 코드 변경: 없음. 결과 기록만 갱신했다.
- PR 본문 변경: 수행하지 않음. 원격 CI는 수정 커밋 푸시 후 재실행한다.

## 발생 문제와 조치

- 처음에는 Swagger 애너테이션으로 식별자를 지정하려 했으나 해당 애너테이션은 현재 main 컴파일 의존성에 없어 컴파일 실패했다. 애너테이션을 제거하고 기존 코드 방식인 고유 메서드 이름을 사용했다. 새 의존성을 추가하지 않았다.
- 생성 테스트에서 Docker 엔진이 꺼져 테스트 환경 생성이 실패했다. Docker Desktop을 시작해 엔진 29.5.2 준비를 확인한 후 재실행했다. 사용자 DB는 변경하지 않고 Testcontainers 격리 DB를 사용했다.
