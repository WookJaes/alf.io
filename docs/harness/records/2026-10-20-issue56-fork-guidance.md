# 이슈 #56 포크 운영 안내 정리

- 날짜 / 상태: 2026-10-20 / completed (작업 단위 1)
- 요청: [이슈 #56](https://github.com/WookJaes/alf.io/issues/56)의 포크 소개·기여·정책 안내. 현재 Codex가 순차 구현·검증·자체 리뷰했다.
- 초기 Git: `main`, 사용자 변경 없음. `git fetch origin main` 후 `0c57ea660`에서 `docs/56-non-deployment-fork` 생성. 커밋만 승인됨. 푸시·PR·이슈 변경·댓글·배포 금지.
- 확인 지침: `AGENTS.md`, `ai/WORKFLOW.md`, `ai/routing.md`, `docs/conventions/git.md`, `docs/harness/local-validation.md`, `ai/templates/work.md`. 하위 AGENTS 없음. 최신 이슈 본문과 PR #55의 Windows 논의도 조회했다.
- 실행 기준: 저장소 루트, macOS / Python 3.9.6. 검증 대상은 이 커밋의 README·SECURITY·CODE_OF_CONDUCT diff.

| 완료 기준 | 변경·확인 | 검증·결과 |
|---|---|---|
| 독립 포크·날짜·비배포·현행 기능 | README를 한국어 안내로 정리, 도우미·선택 task를 현행 코드와 대조 | 자체 diff 검토 완료 |
| 포크 기여·지원 범위 | 이슈·PR은 WookJaes/alf.io, 원본 연락처·응답·CVE·집행 약속 정리 | 새 개인 연락처·지원 체계 없음 |
| 원본 보존 | 라이선스·저작권 파일 미수정, 원본 코드 기여 링크와 번역자 13개 행 보존 | Python 상대 링크 존재·명단 동일성 검사 종료 0 |
| 문서 구조 | `python3 -B ai/tests/check.py` | 하네스 static 실행 종료 0 |
| 자체 리뷰·완료 게이트 | `issue56-1`, 선언 `ai/local-state/issue56/plan1.json`, review begin → record → gate | gate 종료 0, 독립 리뷰 아님 |

원본 배지·후원 유도는 삭제했다. 원본 릴리스·이미지는 포크 변경 미포함을 명시했고 원본 정책·기여·번역·인증서·JDK·Lombok·Testcontainers·Podman 링크는 출처·기술 참고로 유지했다. 원본 변경 이력 파일은 수정하지 않았다.

문서 변경이므로 제품 컴파일·DB·브라우저·배포는 실행하지 않았다. 링크 대상 파일 존재와 문맥을 확인했으며 모든 외부 URL의 HTTP 응답을 검사한 것은 아니다. 실제 Python 회귀·화면 단위 검증은 별도 작업 단위 기록에 남긴다. 요청된 로컬 커밋에 이 기록과 관련 문서를 함께 포함한다.
