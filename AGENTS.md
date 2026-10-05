# alf.io 프로젝트 지침

- 운영 문서·보고는 한국어로 작성하고 기존 코드 관례와 사용자 변경을 보존한다. 시작 시 Git 브랜치·상태와 적용되는 하위 지침을 확인한다.
- 명시적 승인 없이 커밋·푸시하지 않는다. 외부 메시지·이슈 등록·배포는 사용자 지시에 따른다.
- 새 의존성·공개 API 호환성 변경·DB 마이그레이션은 실행 전에 영향과 이유를 알린다.

## 작업 안내

Git 브랜치·커밋·PR 작업은 [Git 규칙](docs/conventions/git.md)을 따른다.

구현·수정·검증 요청은 [ai/WORKFLOW.md](ai/WORKFLOW.md)에 따라 현재 Codex에서 수행한다. 조사·설명은 필요한 자료만 읽는다. 역할 분담은 [ai/routing.md](ai/routing.md), 사용법은 [docs/harness/README.md](docs/harness/README.md)에 있다. 별도 CLI 실행기는 사용하지 않는다. 하위 에이전트는 사용자가 분담을 명시적으로 요청한 경우에 사용한다.

## 프로젝트 고유 정보

- Java 25·Gradle Wrapper. PostgreSQL Testcontainers 통합 테스트는 Docker 필요.
- 백엔드: src/main/java/alfio. 프론트: frontend/public(Angular), frontend/admin(Lit), src/main/webapp/alfio-admin-v1(기존 관리자).
- 컴파일·실제 테스트·화면 확인을 구분한다. 임시 합성 데이터를 사용한 검증은 요청 범위에서 계속 진행한다.
