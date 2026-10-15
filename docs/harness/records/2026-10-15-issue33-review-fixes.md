# 이슈 #33 독립 리뷰 후 Git 실행 수정

- 작업 ID / 날짜 / 상태: issue33-review-fixes / 2026-10-15 / completed (리뷰 3건·명시한 중개 경로 범위)
- 요청: 독립 리뷰에서 확인한 복수 push URL·Git 내부 미검증 실행·브랜치 오차단 3건을 하나의 커밋으로 수정. 커밋까지만, 푸시·PR·외부 게시·전역 권한 변경 제외.
- 시작 상태: 브랜치 `feat/33-execution-gate`, HEAD `c67898848d9701a3018095e9bbabb0e707d0945c`, 미커밋 변경 없음. 실제 개발 checkout 유지. 이전 기록의 `codex/33-execution-gate`는 최초 작업 당시 이름이며 이후 저장소 규칙에 맞게 이름만 바로잡았다.
- 범위: execution_gate.py·해당 회귀·사용 안내·이 작업 기록. 기존 run.py·제품 API·DB·새 의존성 변경 없음.
- 담당: 현재 Codex가 수정·QA·자체 리뷰. 앞선 독립 리뷰는 별도 서브에이전트가 수행했고 PR 게시 목적이 아니었다. 수정 뒤 자체 확인과 독립 리뷰를 구분한다.
- 계획: Git 공식 설정 확인 → 검사/실행 공통 안전 설정·실제 단일 목적지·HTTPS helper 승인 범위 → 저장소 브랜치 규칙 → 실제 Git/합성 프로세스 회귀 → 전체 하네스 회귀·QA·리뷰 → 단일 커밋.
- 실행 환경: 저장소 루트, Python3.9.6·Git2.50.1. 네트워크 없는 임시 Git 저장소·합성 HTTPS helper만 사용. 합성 승인 서명은 사용자 운영 승인이 아니다.
- 로컬 선언: ignored `ai/local-state/issue33-review-fixes/plan.json`, 완료 검증 ID `issue33-review-fixes-final`. structure·regression·자체 QA/Review 필수, 독립 수행은 선언상 필수 아님.

## 원인과 변경

1. 첫 번째 push URL만 검사했지만 실제 Git은 모든 pushurl에 전달했다. `--push --all` 결과가 승인한 저장소 URL 1개와 정확히 일치해야 승인 판정으로 진행한다. 중복 URL도 거부한다. URL 전체를 승인 범위에 연결한다.
2. 검사 함수에만 fsmonitor 비활성화가 있었고 실제 커밋은 이를 빠뜨렸다. 검사·실제 커밋·푸시에 공통 argv 생성기를 사용한다. hook·fsmonitor·서명·자동 maintenance/gc·임의 credential helper/askpass를 제한한다. 필요한 인증이 없으면 실패하며 전역 인증 설정을 바꾸지 않는다.
3. 원격 URL을 바꾸지 않고도 remote.vcs로 임의 helper를 고를 수 있었다. 해당 설정과 URL 재작성을 거부하고 HTTPS만 허용한다. Git 설치의 HTTPS helper 디렉터리·해시를 승인 범위에 포함하고 실제 exec-path를 고정한다. helper 변경은 새 승인 필요, 누락은 미전달이다.
4. codex 전용 정규식이 저장소 규칙의 feat 브랜치를 거부했다. feat/fix/docs/test/refactor/chore와 이슈 번호·kebab-case 규칙을 적용했다. 현재 feat/33-execution-gate는 승인 단계로 진행한다.

## 검증과 실패

- 첫 표적 회귀43건 중1건 실패: 합성 HTTPS helper가 성공 응답 뒤 종료용 빈 줄에 다시 출력해 BrokenPipe로 종료1을 반환했다. 실제 Git의 원격 프로토콜 대역에서 불필요한 마지막 출력을 제거했고 다음43건은 모두 통과했다. 제품이나 실제 원격 실패라고 표시하지 않는다.
- 이후 PATH의 동명 helper와 호출 환경 GIT_EXEC_PATH가 승인 helper를 바꾸지 못하는 검사도 보강했다. 최종 전체 회귀는 그 뒤에 수행한다.
- 로컬 완료 ID와 원본 계획 디렉터리가 겹친 첫 init은 기존 ID 보호로 거부됐으며 파일을 덮어쓰지 않았다. 실제 완료 검증 ID를 `issue33-review-fixes-final`로 분리한다.
- 실제 원격 푸시·GitHub 쓰기·실제 인증/사용자 승인 UI·Windows 중개·악의적인 OS 동시 조작 격리는 미검증. 기존 중개 CLI 밖의 직접 셸·에디터·MCP 우회 한계는 유지한다.
- 최종 실제 건수·종료 코드·QA/Review·gate/resume·커밋 결과는 아래에 실행 뒤 기록한다. 이전105건 통과만으로 독립 리뷰3건이 해결됐다고 간주하지 않는다.


## 최종 결과

- `run issue33-review-fixes-final structure`: 종료0. 문서·링크·구조 정상.
- `run issue33-review-fixes-final regression`: 종료0, 실제113건·실패0·오류0·skipped0. 기존 결과 하네스70건과 실행 중개43건 포함. PATH/호출 환경의 동명 helper 우회 검사까지 보완한 최종 코드에서 실행했다.
- 복수/중복 push URL: 유효한 단일 대상에 합성 승인 후 설정을 바꿔도 deny/repository/not_started, 실제 합성 helper 시작 표식 없음. 첫 URL만 확인했던 결함을 재현 조건으로 고정했다.
- fsmonitor: 실제 임시 Git 커밋에서 승인 확인 전후와 커밋 성공 뒤 모두 합성 프로그램 표식 없음. 검사 함수만 고친 테스트가 아니라 실제 commit invoke를 실행했다.
- remote.vcs: 합성 승인 뒤 임의 helper 설정 추가 시 deny/integration/not_started, PATH의 합성 git-remote-synthetic 미실행. URL 재작성도 미전달.
- 정상 push: 실제 Git 프로세스와 로컬 HTTPS 프로토콜 대역을 사용해 feat/33-synthetic의 동일 원격에 한 번 전달·성공을 확인했다. 같은 ID는 replayed이며 helper 시작은1회. 실제 GitHub 요청·외부 네트워크는 없었다.
- HTTPS helper: 승인 후 내용 변경은 approval_required, 누락은 integration 거부. 호출자가 지정한 GIT_EXEC_PATH와 PATH의 동명 프로그램은 선택되지 않았다.
- 브랜치: feat/33-execution-gate·fix·docs·test·refactor·chore 정상 작업 브랜치는 승인 단계 도달. main·codex·앞자리0/0 이슈·중첩 경로·비정규 요약은 미전달.
- 후속 독립 리뷰: 이전 리뷰 서브에이전트가 수정4개 파일 diff를 읽기 전용으로 재검토해 이전3건 해소·추가 차단 결함 없음으로 보고했다. 전체113건을 반복하지 않았고 실제 원격·사용자 승인 UI는 검증하지 않았다. PR 용도·외부 게시·파일 수정 없음. 부모 자체 QA/Review에서는 이 보고와 실제 최종 회귀 근거를 다시 대조했다.
- 민감정보: 합성 데이터와 프로그램 표식만 사용했다. 토큰·실제 자격·승인 키·원본 로그는 변경4개 파일에 포함하지 않는다. 직접 셸·OS 동시 조작 등의 기존 한계와 프로그램 기반 인증/URL 재작성의 미지원 범위를 안내에 유지했다.
- 검증 이후 코드·사용 안내 변경 없음. 이후 추가한 이 결과 요약은 기존 snapshot 제외 작업 기록이다. 사용자 승인 범위에서4개 파일을 하나의 새 커밋으로 남기며 기존 커밋을 재작성하지 않는다. 푸시·PR·외부 댓글·전역 설정 변경 없음.

- 최종 gate/resume 모두 종료0: structure/regression=passed(executed), QA/Review=passed(manual). 커밋 뒤 HEAD 변경은 기존 하네스의 보수적 무효화 조건으로 유지하며 커밋 전 검증을 새 실행으로 표시하지 않는다.
