# 이슈 #39 빌드·검증 단계 분리 작업 기록

## 시작 상태

- 작업일: 2026-10-17 (Asia/Seoul)
- 기준 브랜치/HEAD: `main` / `2c908175b`
- 시작 작업 트리: 깨끗함
- 작업 브랜치: `chore/39-build-validation-separation`
- 환경: Java 25.0.4.1 (Corretto), Gradle Wrapper 9.7.1, pnpm 11.25.0, macOS
- 실행 기준 디렉터리: 저장소 루트
- Gradle 캐시: `/private/tmp/alfio-gradle-before` (샌드박스의 기존 사용자 캐시 접근 제한으로 사용)

## 기준 측정

코드 수정 전, `--no-daemon`과 `clean`을 포함한 동일 명령으로 측정했다.

| 대상 | 명령 | 결과 | 시간 |
|---|---|---:|---:|
| 전체 배포 빌드 | `./gradlew --no-daemon --console=plain clean distribution` | 성공 | 213초 |
| 공개 프론트엔드 | `./gradlew --no-daemon --console=plain clean frontendBuild` | 성공 | 13초 |
| 관리자 프론트엔드 | `./gradlew --no-daemon --console=plain clean frontendAdminBuild` | 성공 | 8초 |
| 백엔드 컴파일·테스트 | `./gradlew --no-daemon --console=plain clean compileJava test` | 성공 | 141초 |
| CI 전체 실행 | 로컬에서 실행하지 않음 | 미측정 | - |

참고: 전체 및 백엔드 테스트 중 Jetty 종료 경고가 출력됐지만 Gradle 종료 코드는 0이었다. 첫 번째 자동 측정 시도는 셸 인자 전달 오류로 무효 처리하고 위의 올바른 명령으로 재측정했다.

## 계획

1. Gradle task를 공개/관리자 프론트엔드, 백엔드 검증, 전체 배포로 분리한다.
2. `bootRun` 기본 흐름은 유지하면서 독립적인 dev server task와 프론트엔드 비활성 백엔드 실행 옵션을 제공한다.
3. 변경 경로에 따라 선택 검증 또는 전체 검증을 수행하도록 CI를 조정한다.
4. README에 명령과 기준/변경 후 시간을 기록하고, JAR·Docker 산출물을 확인한다.

## 최종 결과

### 변경 후 동일 명령 측정

| 대상 | 명령 | 결과 | 시간 | 기준 대비 |
|---|---|---:|---:|---:|
| 전체 배포 빌드 | `./gradlew --no-daemon --console=plain clean distribution` | 성공 | 158초 | 55초 단축 |
| 공개 프론트엔드 | `./gradlew --no-daemon --console=plain clean frontendBuild` | 성공 | 11초 | 2초 단축 |
| 관리자 프론트엔드 | `./gradlew --no-daemon --console=plain clean frontendAdminBuild` | 성공 | 7초 | 1초 단축 |
| 백엔드 컴파일·테스트 | `./gradlew --no-daemon --console=plain clean compileJava test` | 성공 | 138초 | 3초 단축 |

선택 검증 경로도 실제 실행했다.

- `./gradlew --no-daemon --console=plain frontendCheck`: 성공
- `./gradlew --no-daemon --console=plain backendCheck`: 성공
- `./gradlew --no-daemon --console=plain tasks --all`: 새 task 목록 확인
- `./gradlew --no-daemon --console=plain backendCheck --dry-run`: 프론트엔드 task 미포함 확인
- `./gradlew --no-daemon --console=plain fullBuild --dry-run`: 두 프론트엔드, `bootJar`, 테스트, `dockerize` 포함 확인
- `./gradlew --no-daemon --console=plain -PfrontendDev=false bootRun --dry-run`: 백엔드만 실행 경로 확인
- `./gradlew --no-daemon --console=plain frontendPublicDev frontendAdminDev --dry-run`: 독립 dev server task 확인
- Ruby YAML parser: `.github/workflows/build-on-push.yml` 파싱 성공
- `git diff --check`: 성공

CI는 PR에서 공개 프론트엔드만 변경되면 `frontendCheck`, 백엔드만 변경되면 `backendCheck`, 양쪽·공통 설정 변경이면 전체 matrix build를 선택한다. `main` push와 release tag는 기존 Docker image artifact 생성을 보존하기 위해 전체 build를 선택한다.

### 실제 개발 서버 기동 확인

- `env GRADLE_USER_HOME=/private/tmp/alfio-gradle-before ./gradlew --no-daemon --console=plain frontendPublicDev`: 성공. Angular Live Development Server가 `http://localhost:4200/`에서 기동하고 컴파일 성공 로그를 확인한 뒤 종료했다.
- `env GRADLE_USER_HOME=/private/tmp/alfio-gradle-before ./gradlew --no-daemon --console=plain frontendAdminDev`: 성공. Vite가 `http://localhost:5173/`에서 ready 상태가 된 것을 확인한 뒤 종료했다.
- `env GRADLE_USER_HOME=/private/tmp/alfio-gradle-before ./gradlew --no-daemon --console=plain -Pprofile=dev bootRun`: 실패. 기존 `bootRun`이 공개 프론트엔드 준비와 관리자 Vite 기동까지 진행했지만, 애플리케이션 초기화 중 현재 PostgreSQL의 사용자 인증이 실패해 종료됐다. `killViteDevServer`가 실행되어 Vite 프로세스는 정리됐다.

따라서 프론트엔드 독립 dev server의 실제 기동은 검증했으며, `bootRun`의 전체 런타임 성공은 현재 DB 인증 정보 불일치로 검증하지 못했다.

배포 산출물도 확인했다. `build/libs/alfio-2.0-M6-SNAPSHOT-boot.jar`는 97,338,799 bytes였고, `BOOT-INF/classes/resources/alfio-public-frontend/` 12개 및 `BOOT-INF/classes/resources/alfio-admin-frontend/` 2,059개 항목과 공개 index 변환 파일 1개를 포함했다. `build/dockerize/Dockerfile`과 `build/dockerize/alfio-boot.jar`도 생성됐다.

Docker daemon은 현재 소켓 권한 오류(`permission denied ... docker.sock`)로 실제 `docker build`를 실행하지 못했다. 따라서 Docker 이미지 빌드 성공은 확인하지 않았고, Gradle이 생성한 Docker context와 JAR만 확인했다.

전체 배포 빌드 시간은 악화되지 않았으며, 공개·관리자·백엔드 독립 명령은 모두 기준보다 짧았다. 변경 경로별 CI 선택 실행은 workflow 정적 파싱과 로컬 task 확인까지 수행했으나 GitHub Actions 실제 실행 시간은 측정하지 않았다.
