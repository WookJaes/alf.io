# PR 42 브라우저 회귀 실행 환경 개선

- 작업 ID / 날짜 / 상태: pr42-browser-regression / 2026-10-18 / completed (로컬 검증 완료, 원격 CI 미실행)
- 요청·완료 기준: [PR 42 리뷰](https://github.com/WookJaes/alf.io/pull/42#pullrequestreview-5561936405)의 참고 의견. 외부 Playwright 설치·macOS Chrome 경로 의존을 제거하고 표준 실행 명령·CI를 연결한다. 최종 사용자 지시는 로컬 커밋까지이며 푸시·리뷰 답글·PR 본문 수정은 하지 않는다.
- 실행 모드·실제 담당: 현재 Codex의 순차 구현·자체 QA·자체 리뷰. 독립 에이전트 없음.
- 초기 Git: `fix/41-reconciliation-ui-state`, PR 42의 최초 버그 수정 반영 후 작업 트리 깨끗함.
- 변경 범위: 관리자 개발 의존성·잠금 파일, 브라우저 테스트의 모듈/브라우저 선택, package 실행 명령, 기존 E2E 워크플로의 별도 브라우저 작업, 실행 안내와 이 기록. 제품 컴포넌트·백엔드·Java E2E·DB 스키마는 수정하지 않는다.
- 의존성 영향: 적용 전에 사용자에게 Playwright 개발 의존성·잠금 파일 변경과 제품 런타임에 영향 없음을 알렸다. 기존 검증에 사용한 Playwright 1.62.1로 고정한다. 브라우저 다운로드는 명시적 설치 명령에서 수행하며 제품 실행에 포함하지 않는다.
- 실행 환경·기준 디렉터리: macOS arm64, 저장소 루트. 검증 시 Node/브라우저 버전을 확인한다. CI는 Gradle과 같은 Node 22.22.3·pnpm 11.1.2 및 Ubuntu/Windows를 선언한다.
- 계획: 개발 의존성·실행/CI 연결 → 잠금 파일 고정 설치 → 관리 Chromium으로 14건 실제 실행 → 관리자 빌드·설정 검토 → 자체 리뷰·게이트 → 로컬 커밋.

## 구현과 확인 범위

- [Playwright 라이브러리 사용·브라우저 설치](https://playwright.dev/docs/library), [공식 CI 안내](https://playwright.dev/docs/ci), [setup-node](https://github.com/actions/setup-node)의 공식 설명을 확인했다.
- 상세 실행 결과·기록 조정·미검증 범위·게이트 결과는 아래에 기록했다. 원본 로그·JUnit·환경 JSON은 로컬 보관하며 커밋하지 않는다.


## 변경과 검증

실제 검증은 macOS arm64에서 Node 22.22.3·pnpm 11.1.2·Playwright 1.62.1·관리 Chromium 151.0.7922.34로 수행했다. 외부 모듈 경로 환경변수와 `CHROME_PATH`를 제거해 기본 경로를 검증했다.

| 완료 기준 | 변경·실행 방법 | 실제 결과 | 저장소에서 확인할 수 있는 근거 |
|---|---|---|---|
| 의존성·설치 재현성 | `pnpm --dir frontend/admin install --frozen-lockfile` | 종료 0, 잠금 파일과 패키지 일치 | [package.json](../../../frontend/admin/package.json), [pnpm-lock.yaml](../../../frontend/admin/pnpm-lock.yaml) |
| 관리 브라우저 준비 | `pnpm --dir frontend/admin exec playwright install chromium` | 종료 0, 버전에 대응하는 관리 Chromium 준비 | [실행 안내](../../../frontend/admin/README.md#attendee-reconciliation-browser-regression-tests) |
| 기본 브라우저 회귀 | 경로 환경변수 없이 `pnpm --dir frontend/admin exec node --test --test-reporter=junit --test-reporter-destination=../../ai/local-state/pr42-browser-config/regression.xml test/reconciliation.test.mjs` | 최종 실제 14건, 실패 0·오류 0·스킵 0, 종료 0 | [회귀 테스트](../../../frontend/admin/test/reconciliation.test.mjs). 단언·합성 시나리오는 기존과 동일 |
| 표준 명령·새 의존성 사본 | 기존 `node_modules`를 제외하고 package/lock/workspace/tsconfig/src/test만 새 임시 디렉터리에 복사 → `pnpm install --frozen-lockfile --offline` → `pnpm run test:reconciliation` | 설치 종료 0, 실제 14건 전부 통과·스킵 0, 임시 사본 정리 | package의 표준 실행 명령과 위 재현 절차. pnpm 저장소·브라우저 캐시는 재사용했으며 완전히 빈 OS 설치 검증으로 주장하지 않음 |
| 관리자 빌드 | `pnpm --dir frontend/admin run build` | TypeScript·Vite production 빌드 종료 0 | 기존 build 명령·제품 import에는 Playwright 추가 없음 |
| CI 설정 | YAML 파싱 후 Ubuntu/Windows 행렬·고정 Node/pnpm·잠금 설치·브라우저 설치·표준 테스트 실행을 대조 | 종료 0. 기존 Java E2E와 권한·트리거·취소 정책 보존 | [E2E 워크플로](../../../.github/workflows/e2e-test.yml) |
| 문서·구조 | `python3 -B ai/tests/check.py`, 상대 링크·diff 검사 | 종료 0 | 실행 안내·이 기록·최초 기록에 현재 안내 연결 |

`Browser regression`은 Ubuntu/Windows의 별도 matrix 작업으로 추가했다. 실제 실행 시 해당 플랫폼용 브라우저와 Linux 시스템 의존성을 설치하고 14건을 직접 실행하도록 구성했다. 로컬 YAML 검사만으로 원격 실행 성공을 주장하지 않는다. 새 CI는 사용자 지시에 따라 푸시하지 않으므로 이번 단계에서는 미실행이다.

## 자체 QA·리뷰와 로컬 게이트

- 실행 ID·선언: `pr42-browser-final`, `ai/local-state/pr42-browser-config/plan.json`. 자동 항목은 회귀·관리자 빌드·CI 설정·하네스 구조이며 QA·Review는 현재 Codex 자체 수행이다. 독립 수행으로 표시하지 않는다.
- 자체 QA: 프로젝트에 설치한 Playwright로 관리 Chromium을 실행하고, 임시 소스 사본에서 표준 명령도 실제 수행했다. 환경변수의 외부 모듈 선택과 macOS 실행 파일 기본값이 제거됐는지 확인했다. 로컬 새 설치 검증과 원격/다른 OS 미검증을 분리했다.
- 자체 리뷰: Playwright와 playwright-core는 1.62.1로 고정했고 optional fsevents만 잠금 파일에 함께 추가됐다. 기존 의존성 버전·제품 코드·테스트 단언은 바꾸지 않았다. 브라우저는 명시적 설치 명령에서만 받으며 제품 번들에 import하지 않는다. CI의 기존 Java E2E를 보존하고 Node 회귀를 별도 실행한다. 미해결 차단 사항 없음.
- 최종 로컬 게이트: regression/build/config/structure/qa/review 전부 passed, 종료 0. 자동 실행 뒤 테스트·설정·의존성은 변경하지 않았으며 문서 결과 요약만 갱신했다.
- 새 실행 안내와 과거 재현 기록을 구분하기 위해 최초 이슈41 기록의 맨 위에 현재 안내·이 기록 링크를 추가했다. 과거 검증 결과는 다시 실행한 결과로 바꾸지 않았다.
- 로컬 보관·커밋 미포함: `ai/local-state/pr42-browser-config/`의 선언·환경 요약·CI 검사 보조 스크립트·최종 JUnit·QA/Review 메모와 `ai/local-state/pr42-browser-final/`의 실행 영수증. 이 파일 없이도 README의 표준 명령으로 테스트를 실행할 수 있다. 로그·환경 JSON·실제 계정 정보·원본 캡처는 첨부하지 않는다.

## 실행 기록 조정

- 유형 / 단계: 도구·기록 조정 / 로컬 회귀 영수증.
- 기대 / 실제: 최초 Node 회귀의 JUnit은 14건 전부 통과했으나, 같은 실행 ID에서 빌드 기록을 동시에 갱신해 회귀 영수증이 `running`으로 남았다. 제품/테스트 실패로 집계하지 않는다.
- 원인·조치: 실행 기록 갱신 순서가 원인이며, 본인이 생성한 JUnit만 제거하고 해당 회귀를 순차 재실행했다. 최종 14건 통과와 정상 영수증을 확인했다. 반복 실행을 서로 다른 테스트 수로 합산하지 않는다.

## 최종 범위와 처리

- 미실행: 새 Ubuntu/Windows 원격 CI, 다른 OS에서의 실제 로컬 실행, 백엔드·Java E2E 재실행. 제품·Java·DB 변경이 없어 기존 통합 테스트는 이번 테스트 실행 환경 개선에서 재실행하지 않았다. 기존 PR 커밋의 원격 성공 결과와 이번 새 CI 미실행을 구분한다.
- 커밋 메시지: `test: 명단 비교 브라우저 회귀 실행 환경 표준화`. 사용자 최종 지시에 따라 로컬 커밋까지만 수행하며 푸시·외부 리뷰 답글·PR 본문 수정은 하지 않는다.
