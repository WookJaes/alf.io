# alf.io 프로젝트 지침

- 운영 문서·보고는 한국어로 작성하고 기존 코드 관례와 사용자 변경을 보존한다. 시작 시 Git 브랜치·상태와 적용되는 하위 지침을 확인한다.
- 명시적 승인 없이 커밋·푸시하지 않는다. 외부 메시지·이슈 등록·배포는 사용자 지시에 따른다.
- 생성된 PR의 설명 본문은 절대 수정하지 않는다. 본인·다른 협업자가 만든 PR 모두에 적용하며 리뷰·재검증 결과·정정·진행 상황은 새 댓글이나 리뷰로 남긴다. 본문 변경이 필요해도 수정안을 초안이나 댓글로 제시한다. 상세 규칙은 [Git 규칙](docs/conventions/git.md)을 따른다.
- 새 의존성·공개 API 호환성 변경·DB 마이그레이션은 실행 전에 영향과 이유를 알린다.

## 작업 안내

사용자가 이 저장소의 개별 PR 링크(`https://github.com/WookJaes/alf.io/pull/<번호>`)만 입력하면 기본 요청을 PR 리뷰로 해석한다. 별도 지시가 없으면 [리뷰 역할](ai/roles/review.md)과 연결된 리뷰·대댓글 양식을 먼저 읽고 최신 HEAD를 검토해 PR #2 형식의 리뷰 초안을 작성한다. 같은 대화에서 해당 리뷰 게시를 이미 승인했으면 승인 범위에서 등록까지 진행한다. 링크만으로 새로운 외부 게시 권한을 부여하지 않는다. 링크와 함께 조사·설명·비교 등 다른 목적을 명시했으면 그 요청을 우선한다.

Git 브랜치·커밋·PR 작업은 [Git 규칙](docs/conventions/git.md)을 따른다.

구현·수정·검증 요청은 [ai/WORKFLOW.md](ai/WORKFLOW.md)에 따라 현재 Codex에서 수행한다. 조사·설명은 필요한 자료만 읽는다. 역할 분담은 [ai/routing.md](ai/routing.md), 사용법은 [docs/harness/README.md](docs/harness/README.md)에 있다. 별도 CLI 실행기는 사용하지 않는다. 하위 에이전트는 사용자가 분담을 명시적으로 요청한 경우에 사용한다.

## 프로젝트 고유 정보

- Java 25·Gradle Wrapper. PostgreSQL Testcontainers 통합 테스트는 Docker 필요.
- 백엔드: src/main/java/alfio. 프론트: frontend/public(Angular), frontend/admin(Lit), src/main/webapp/alfio-admin-v1(기존 관리자).
- 컴파일·실제 테스트·화면 확인을 구분한다. 임시 합성 데이터를 사용한 검증은 요청 범위에서 계속 진행한다.
