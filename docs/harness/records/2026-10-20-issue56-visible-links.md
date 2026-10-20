# 이슈 #56 사용자 노출 링크 정리

- 날짜 / 상태: 2026-10-20 / completed (작업 단위 2)
- 기준: `docs/56-non-deployment-fork`의 첫 작업 단위 이후. 기존 사용자 변경 없음. [이슈 #56](https://github.com/WookJaes/alf.io/issues/56)의 화면·website 범위만 수정했다. 현재 Codex가 구현·검증·자체 리뷰했다.
- 지침·초기 상태는 [운영 안내 기록](2026-10-20-issue56-fork-guidance.md)과 같다. 실행 기준은 저장소 루트(빌드는 각 프론트 폴더), macOS / Python 3.9.6 / Node v26.0.0 / TypeScript 5.9.3 / Vite 7.3.0 / Angular CLI 17.3.17. 검증 대상은 이 커밋의 diff.

| 완료 기준 | 변경·검증 | 결과 |
|---|---|---|
| 관리자 출처·지원 링크 | Lit 배너 두 분기·기존 sidebar: Powered by Alf.io·버전 보존, 포크 저장소·이슈·기여 안내 연결 | 소스 정적 확인 |
| 공개 화면 | 티켓 조회·수정·예약 성공·구독 성공·대기실의 credits href 변경 | 번역 키·문구·바인딩 보존 |
| website 안내 | 홈페이지 CTA·기여·커뮤니티·양식·config 링크 정리, 기존 원본 배포 문서에 출처 안내 | 구조 개편·배포 없음 |
| 정적 구조 비교 | 임시 Python HTMLParser로 태그·비링크 속성·class·script·바인딩·dismiss 코드 원문 비교 | 종료 0, 안내 내 링크 1개 추가 외 구조 동일 |
| 관리자 빌드 | `frontend/admin`: `node node_modules/typescript/bin/tsc && node node_modules/vite/bin/vite.js build --mode production` | 종료 0, 276 modules, 기존 chunk 크기 경고 |
| 공개 빌드 | `frontend/public`: `node node_modules/@angular/cli/bin/ng.js build --aot=true --configuration production` | 종료 0, bundle·assets·index 생성. 기존 ES5 제외 브라우저 경고 |
| 문서 구조·자체 리뷰 | `issue56-2`, `ai/local-state/issue56/plan2.json`, structure 실행·review begin/record·gate | gate 종료 0, 독립 수행 아님 |
| 제외 범위 보존 | 기준 `0c57ea660`과 LICENSE·Gradle·Dockerfile·workflows·scripts/ci·scripts/validation·하네스/회귀 코드 diff 비교 | 변경 없음 |

## 링크 보존 기준

사용자를 원본 서비스·데모·문의·후원으로 유도하는 CTA는 포크 안내로 바꿨다. 홈페이지의 원본 후원 로고·OpenCollective 링크는 **원본 후원 이력**으로 구분하고 모집 문구를 없앴다. 원본 기여자 API는 원본 이력 표시 용도로 유지했다. 원본 모델·확장 문서·결제 설정 가이드·과거 이슈·체크인 앱/PI·배포 예시는 기술 참고로 보존했다. 원본 앱·릴리스·이미지에 포크 변경이 없다는 안내를 추가했다. 과거 변경 이력·저작권·라이선스는 수정하지 않았다.

`rg`로 frontend·기존 관리자·website의 alf.io 도메인, alfio-event 저장소, usage-form, OpenCollective, 문의 링크를 검색해 남은 목적을 대조했다. 외부 URL 전체의 HTTP 상태를 검사하지는 않았다.

## 실패·제약

처음 `pnpm run build`는 양쪽 모두 실행기의 레지스트리 접근 실패와 비TTY 의존성 재설치 거부로 종료 1이었다. 의존성·잠금 파일을 수정하지 않고 설치된 로컬 실행 파일로 package.json의 실제 빌드 단계를 실행해 성공했다. 홈페이지 정적 비교에서 빠진 inline 줄바꿈 태그를 발견해 복구한 후 다시 통과했다.

Hugo가 설치되지 않아 website 렌더는 실행하지 않았다. 실제 앱 기동·브라우저 화면·E2E·DB 검사는 실행하지 않았으며 소스의 링크·문구 및 양쪽 프론트 빌드로 검증했다. 링크와 안내만 바뀌어 태그·class·사용자 동작 로직이 동일함을 비교했다. 원본 양식·지도 script·사이트 구조도 그대로 보존했다. 산출물은 Git 제외 경로에만 남기고 배포하지 않았다. 관련 소스와 이 기록을 하나의 로컬 커밋으로 묶는다.
