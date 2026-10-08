# 관리자 생성 예약의 필수 추가 옵션 반영

- 작업 ID / 날짜 / 상태: 관리자 필수 옵션 수정 / 2026-10-08 / completed
- 요청·완료 기준: 관리자 생성 예약의 필수 고정·비율 수수료를 일반 예약의 기존 규칙으로 적용. 단일·다중 티켓과 주문 요약을 검증하고 선택 옵션·판매 기간 제한을 유지한다.
- 실행 모드: 현재 Codex 순차 구현·검증·자체 리뷰. 별도 에이전트 없음.
- 브랜치·기준 HEAD·시작 변경: fix/11-admin-mandatory-options / e898acd92 / 작업 트리 깨끗함.
- 환경·명령 기준: 저장소 루트, macOS arm64·Corretto Java 25.0.4.1·Gradle 9.7.1·자동 테스트 Testcontainers 기본 PostgreSQL 10·브라우저 검증 PostgreSQL 16.15·인앱 브라우저. 합성 데이터만 사용.
- 검증 대상: 기준 HEAD 이후 AdminReservationManager와 AdminReservationManagerIntegrationTest의 미커밋 diff. 제품 검증 후 코드 변경 없음.
- 계획·최종 단계: 회귀 테스트로 누락 확인 → 기존 필수 옵션 예약 함수 호출 → 관련 테스트·전체 제품 테스트·로컬 화면 검증 → 기록 및 diff 자체 리뷰 완료.

## 변경과 검증

관리자 생성 경로가 필수 추가 옵션 예약 함수를 호출하지 않는 것이 원인이었다. 모든 카테고리의 티켓 예약과 가격 확정이 성공한 뒤 AdditionalServiceManager.bookAdditionalServicesForReservation을 한 번 호출한다. 기존 판매 기간·수수료 계산·티켓 연결 규칙을 재사용하며, 기존 예약을 소급 변경하지 않는다.

| 완료 기준 | 변경·확인 내용 | 종료 코드·실제 건수 | 결과 |
|---|---|---|---|
| 필수 고정·비율 옵션 반영 | 세 필수 정책 × 티켓 1장·2장, 옵션 개수·금액·PENDING 상태·티켓 연결·주문 요약 확인 | 새 회귀 테스트 6건 통과 | 고정 수수료는 티켓 수만큼, 비율 수수료는 합계 기준 1개 생성 |
| 선택 옵션·판매 기간 유지 | 선택 옵션, 판매 종료·시작 전 필수 옵션을 함께 설정 | 새 회귀 테스트 1건 통과 | 자동 추가되지 않음 |
| 다중 카테고리 정합성 | 100 CHF·50 CHF 카테고리를 함께 예약 | 새 회귀 테스트 1건 통과 | 수수료 15 CHF가 한 번 적용되고 합계 165 CHF |
| 기존 관리자·비율 계산 회귀 | 관리자 통합 테스트 19건 및 기존 비율 수수료 테스트 9건 | 종료 0, 28건 통과·실패 0·스킵 0 | 기존 관리자 예약과 비율 계산 유지 |
| 전체 제품 회귀 | 아래 전체 테스트 명령 실행 | 종료 0, 864건 중 862건 통과·2건 스킵 | 실패 0, 실제 테스트 실행 |
| 실제 화면 정합성 | 고정·티켓 금액 비율 정책의 1장·2장 예약을 새로 생성해 관리자 상세와 결제 전 화면 대조 | 브라우저 조건 4개 확인 | 수수료 10/20 CHF와 합계 110/220 CHF 일치 |
| 반복 진행 시 중복 없음 | 고정 2장 예약의 결제 화면에서 Back → Continue 반복 | 실제 화면 확인 | 수수료 수량 2·20 CHF, 합계 220 CHF 유지 |

관련 테스트:

```sh
TZ=UTC ./gradlew test --tests 'alfio.manager.AdminReservationManagerIntegrationTest' --tests 'alfio.manager.PercentageAdditionalServicesIntegrationTest' --tests 'alfio.controller.api.v2.user.reservation.*ReservationFlowTest' -x frontendBuild -x frontendPnpmInstall -x frontendAdminPnpmInstall --no-daemon
```

위 마지막 필터는 구체적인 일반 예약 통합 테스트 이름과 일치하지 않아 관련 테스트 28건에 일반 예약 테스트가 포함되지 않았다. 이후 전체 제품 테스트에서 일반 예약 테스트까지 실행했다.

```sh
TZ=UTC ./gradlew test -x frontendBuild -x frontendPnpmInstall -x frontendAdminPnpmInstall --no-daemon
```

화면 검증은 기존 임시 Gradle 초기화 스크립트로 localhost:8087, dev·demo·disable-jobs 프로필을 지정해 수정된 앱을 재시작한 뒤 수행했다. 합성 행사 issue7-local·Public 100 CHF·세금 0%·현장 결제 조건을 유지했다. 비율 10% 검증 후 같은 합성 옵션을 티켓당 고정 10 CHF로 전환해 검증했다. 실제 결제·약관 동의·메일 발송은 수행하지 않았다.

| 화면 재검증 조건 | 관리자 상세 합계 | 결제 전 수수료·합계 |
|---|---:|---|
| 티켓 금액의 10%, 1장 | 110 CHF | 10 CHF·110 CHF |
| 티켓 금액의 10%, 2장 | 220 CHF | 20 CHF·220 CHF |
| 고정 10 CHF / 티켓, 1장 | 110 CHF | 10 CHF·110 CHF |
| 고정 10 CHF / 티켓, 2장 | 220 CHF | 20 CHF·220 CHF |

## 실패·리뷰·재개

- 제품 실패 재현: 수정 전 새 회귀 테스트 7건을 실행해 모두 옵션 개수 0으로 실패했다(종료 1). 기존 화면 재현과 같은 관리자 경로의 누락을 확인했다.
- 테스트 환경 조정: 수정 후 옵션 생성 검사는 통과했으나 새 테스트 8건의 합계가 기대값보다 티켓 세금 1%만큼 높았다(종료 1). 공통 initEvent가 기본 세율 1%를 설정하는 것이 원인이었다. 테스트 전용 행사 세율·상태를 0%로 명시해 재현 조건과 맞춘 뒤 28건 통과. 제품 계산이나 기대 금액을 우회하지 않았다.
- 브라우저 환경: 서버 재시작 중 연결 거부 화면이 남아 새 검증 탭으로 복구했다. 비율 2장 예약은 합성 행사의 잔여 좌석 부족으로 처음 거부됐다. 관리자 화면의 기존 Add extra seats to event if needed 기능으로 필요한 합성 좌석만 추가해 성공했다. 기존 고객·예약·티켓을 삭제하지 않았다.
- 리뷰 방식·범위: 자체 diff 리뷰. 모든 티켓 성공 후 한 번만 옵션을 추가하고 실패 Result에서는 호출하지 않는지, 기존 트랜잭션 롤백 경로 안에 있는지, 다중 카테고리 합계·옵션 티켓 연결·선택 옵션·판매 기간·반복 화면 이동을 대조했다. 새 의존성·공개 HTTP API·프론트 코드·DB 스키마·CI 설정 변경 없음.
- 관련 기억: 등록된 공통 기억 없음. 기존 재현 기록의 네 가지 조건을 기준으로 검증했다. 기존 체크인 시간 의존성 때문에 전체 제품 테스트는 앞선 검증과 같은 TZ=UTC로 실행했다. 시간 의존성 자체는 이번에 수정하지 않았다.
- 최초 검증 시 제약(아래 추가 검증으로 보완): PostgreSQL 15/17은 로컬 미실행. MigrationValidatorTest와 NormalFlowE2ETest는 전용 환경·실행 플래그가 필요한 기존 조건에 따라 2건 스킵됐다. 화면 확인은 실제 로컬 브라우저로 별도 수행했으며 자동 E2E 통과로 표시하지 않는다. 주문 전체 금액 비율 정책은 통합 테스트로 확인했고 브라우저에서는 고정·티켓 금액 비율 정책을 확인했다.
- 데이터 검토: 실제 연락처·결제 데이터·비밀번호·토큰·API 키·원본 로그·결과 JSON을 저장소에 추가하지 않았다. 테스트 고객 주소는 example.test 합성 값이다.
- 최종 결과: 관련 및 전체 제품 테스트·네 가지 화면 조건 검증 완료. 커밋·푸시·PR 생성·노션 수정은 수행하지 않았다.


## 추가 검증 — PostgreSQL 15/17·전체 예약 금액 비율 수수료

- 검증 대상: 1fadaf345의 제품 코드·테스트, 코드 변경 없이 실행. 시작 작업 트리 깨끗함.
- DB 버전 표기 정정: 앞선 자동 테스트 명령은 pgsql.version을 지정하지 않아 BaseTestConfiguration의 기본 PostgreSQL 10으로 실행됐다. 브라우저용 DB 16.15와 구분해 환경 표기를 정정했다. 이번에는 버전을 명시했다.
- 범위: 관리자 예약 19건·기존 비율 수수료 9건·일반 예약 흐름 19건, 총 47건을 각 PostgreSQL 버전에서 실제 실행했다. 전체 제품 864건을 각 버전에서 실행한 결과로 확대해 해석하지 않는다.

```sh
TZ=UTC ./gradlew test -Dpgsql.version=15 --tests 'alfio.manager.AdminReservationManagerIntegrationTest' --tests 'alfio.manager.PercentageAdditionalServicesIntegrationTest' --tests 'alfio.controller.api.v2.user.reservation.ReservationFlowIntegrationTest' -x frontendBuild -x frontendPnpmInstall -x frontendAdminPnpmInstall --no-daemon
TZ=UTC ./gradlew test --rerun -Dpgsql.version=17 --tests 'alfio.manager.AdminReservationManagerIntegrationTest' --tests 'alfio.manager.PercentageAdditionalServicesIntegrationTest' --tests 'alfio.controller.api.v2.user.reservation.ReservationFlowIntegrationTest' -x frontendBuild -x frontendPnpmInstall -x frontendAdminPnpmInstall --no-daemon
```

| 추가 검증 | 실행 결과 |
|---|---|
| PostgreSQL 15 관련 테스트 | 종료 0, 47건 통과·실패 0·스킵 0, test 태스크 실제 실행 |
| PostgreSQL 17 관련 테스트 | 종료 0, 47건 통과·실패 0·스킵 0, --rerun으로 test 태스크 실제 재실행 |
| 전체 예약 금액 10%, 티켓 1장 | 관리자 상세: 티켓 100 + 수수료 10 = 110 CHF. 결제 전 화면도 동일 |
| 전체 예약 금액 10%, 티켓 2장 | 관리자 상세: 티켓 200 + 수수료 20 = 220 CHF. 결제 전 화면도 동일. 수수료 수량은 1 |

- 브라우저 절차: 동일 합성 행사의 필수 옵션을 Mandatory percentage fee, entire reservation (including user-selected Additional Items, if any)·10%로 변경하고 저장된 정책을 확인했다. 티켓 1장·2장 예약을 새로 만들어 관리자 상세와 공유 링크의 Contact Details → Continue → 결제 전 요약을 대조했다. 잔여 좌석 부족은 기존 좌석 추가 기능으로 필요한 합성 좌석만 추가했다.
- 실제 결제·약관 동의·메일 발송 없음. 추가 선택 옵션이 없는 관리자 생성 조건으로 확인했으며 선택 옵션을 포함한 브라우저 조건을 검증했다고 주장하지 않는다.
- 추가 검증 중 테스트 실패 없음. 기존 자동 E2E·마이그레이션 스킵 2건과 체크인 시간 의존성 수정은 이번 범위 밖이다.
- 리뷰·최종 상태: 버전별 JUnit 실제 건수·종료 코드와 관리자/결제 화면의 수수료·합계를 자체 대조했다. 이번 변경은 요약 MD의 추가·정정만이며 커밋·푸시·PR 생성·노션 수정 없음.
