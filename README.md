# alf.io — WookJaes/alf.io 포크

[원본 alfio-event/alf.io](https://github.com/alfio-event/alf.io)에서 파생한 독립 포크입니다. 행사·티켓 예약 관리 코드를 수정하고 로컬 및 CI에서 검증하는 프로젝트이며, 앱·Docker 이미지·웹사이트를 별도로 배포하지 않습니다. 원본 운영진이 이 포크의 지원을 담당하지 않습니다.

- 주요 수정 기간: 2026년 10월. 안내 최종 확인: **2026-10-20**.
- 관리자 참석자 대조 도우미: CSV의 `ID`, `E-Mail`을 행사 티켓과 비교하고 누락·중복 ID·이메일 불일치를 확인하며 저장된 대조를 다시 엽니다. 선택적 AI는 확인된 결과로 설명과 메시지 초안을 만들고, 관리자가 검토·수정·복사합니다. 메시지를 전송하거나 예약을 변경하지 않습니다.
- 공개 Angular·관리자 Lit 프론트와 백엔드의 선택 빌드, 자동 E2E·DB 마이그레이션 검증, CI 검사 선택을 정리했습니다. 지도 이미지 URL의 XSS 차단 등 보안 수정도 포함합니다. 상세 변경 이력은 [커밋 이력](https://github.com/WookJaes/alf.io/commits/main/)과 기존 작업 기록에 보존합니다.
- 도우미의 [사용·설정](docs/attendee-reconciliation.md), [평가 결과·한계](docs/attendee-assistant-evaluation.md), [수동 평가](docs/attendee-assistant-manual-evaluation.md)를 참고하세요.

## 개발 준비

저장소 루트에서 Gradle Wrapper를 사용합니다. JDK **25**([Oracle](http://www.oracle.com/technetwork/java/javase/downloads/index.html), [OpenJDK](http://openjdk.java.net/install/))와 PostgreSQL 10 이상이 필요합니다. DB 사용자는 row security 검사가 적용되도록 SUPERUSER가 아니어야 합니다. PostgreSQL Testcontainers 통합 테스트에는 실행 중인 Docker가 필요합니다. [Testcontainers 환경 조건](https://www.testcontainers.org/supported_docker_environment/)을 확인하세요.

IntelliJ·Eclipse에서 Gradle 프로젝트를 가져올 수 있습니다. IDE의 [Lombok](https://projectlombok.org/) 지원도 준비하세요. 원본의 [Podman 소켓 관련 참고](https://github.com/containers/podman/issues/7927#issuecomment-732676422)는 기술 자료이며 이 포크의 실행 검증을 뜻하지 않습니다.

| 목적 | macOS·Bash 명령 |
|---|---|
| 공개 Angular 빌드 | `./gradlew frontendPublicBuild` |
| 관리자 Lit/Vite 빌드 | `./gradlew frontendAdminBuild` |
| 양쪽 프론트 빌드 검사 | `./gradlew frontendCheck` |
| 백엔드 컴파일 | `./gradlew backendBuild` |
| 백엔드 컴파일·테스트 | `./gradlew backendCheck` |
| 통합 빌드·테스트·로컬 패키징 | `./gradlew fullBuild` |
| 기존 로컬 패키징 | `./gradlew distribution` |

Windows PowerShell에서는 `./gradlew` 대신 `.\gradlew.bat`를 사용합니다. 프론트 빌드는 단위 테스트 실행과 구분합니다. E2E·DB 검증 및 Python 회귀의 환경·명령은 [자동 검증 안내](docs/testing/automatic-validation.md)를 참고하세요. 특히 Windows는 내부 Bash의 `python3` 준비가 필요합니다.

```sh
# 백엔드만 실행
./gradlew -PfrontendDev=false bootRun
# 공개 프론트 개발 서버: localhost:4200 (API는 localhost:8080으로 프록시)
./gradlew frontendPublicDev
# 관리자 프론트 개발 서버: localhost:5173
./gradlew frontendAdminDev
# 기존 통합 개발 흐름: 백엔드 8080 + 관리자 Vite
./gradlew bootRun
```

`bootRun`은 기본 로컬 PostgreSQL `localhost:5432`, DB `alfio`, 사용자 `postgres`, 비밀번호 `password`를 사용합니다. 개발용 DB를 별도로 준비하고 실제 데이터·비밀값을 사용하지 마세요. 앱이 테이블을 준비한 후 `http://localhost:8080/admin`에서 사용자 `admin`과 콘솔에 출력된 비밀번호로 로그인합니다.

`./gradlew -Pprofile=dev bootRun`처럼 프로필을 지정합니다. `dev`는 개발, `spring-boot`는 Spring Boot 실행, `demo`는 임시 관리자 생성, `disable-jobs`는 작업 비활성화, `jdbc-session`은 DB 세션 저장에 사용합니다. `demo`는 행사 게시를 금지합니다.

추가 JVM 속성은 Git에서 제외되는 `custom.jvmargs`에 한 줄씩 작성합니다. 디버깅에는 `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005`를 사용할 수 있습니다. 전체 task는 `./gradlew tasks --all`, 의존성 업데이트 조회는 `./gradlew dependencyUpdates`로 확인합니다.

선택 빌드의 기존 측정 환경·결과는 [빌드 검증 기록](docs/harness/records/2026-10-17-issue-39-build-validation.md)에 있습니다. 이 기록의 측정값은 현재 실행 결과가 아닙니다.

## Contributing — 이 포크에 기여하기

1. [WookJaes/alf.io 이슈](https://github.com/WookJaes/alf.io/issues)에서 중복 여부를 확인하고 재현 방법·기대 동작·실행 환경을 작성하세요. 비밀값이나 개인정보는 공개하지 마세요.
2. `WookJaes/alf.io`를 포크하고 최신 `main`에서 [Git 규칙](docs/conventions/git.md)에 맞는 작업 브랜치를 만드세요.
3. 필요한 코드·문서와 관련 검증을 함께 수정하고 실행 명령·결과·미검증 범위를 기록하세요. 새 운영 안내는 한국어로 작성합니다.
4. 대상 저장소 **WookJaes/alf.io**, 기준 브랜치 **main**으로 [PR](https://github.com/WookJaes/alf.io/pulls)을 등록하고 이슈를 연결하세요. [PR 양식](.github/PULL_REQUEST_TEMPLATE.md)과 [작업 워크플로우](ai/WORKFLOW.md)를 따릅니다.

보안 관련 공개 범위는 [보안 안내](SECURITY.md), 참여 기준은 [행동 강령](CODE_OF_CONDUCT.md)을 확인하세요. 별도 상용 지원·응답 기한을 제공하지 않습니다.

## 로컬 패키징과 원본 배포물

`distribution`과 `fullBuild`는 양쪽 프론트·백엔드가 들어 있는 JAR 및 `build/dockerize`의 Docker context를 로컬에 만듭니다. Dockerfile·`bootJar`·Gradle 배포 task·Docker Compose 코드는 향후 재사용을 위해 유지합니다. 이 명령 자체가 원격 배포를 수행한다는 뜻은 아닙니다. 이 포크에서는 이미지 게시·서비스 운영을 하지 않습니다.

[원본 릴리스](https://github.com/alfio-event/alf.io/releases)와 [원본 Docker 이미지](https://hub.docker.com/r/alfio/alf.io/tags)는 원본 프로젝트의 배포물로, **이 포크의 변경 사항이 포함되지 않습니다**. 포크 검증에는 현재 소스를 빌드하세요. 기존 `docker-compose up`은 개발용이며 운영 배포 지원 안내가 아닙니다.

## 라이선스·원본 출처

기존 [LICENSE](LICENSE)와 소스의 저작권 고지를 유지합니다. 제품 이름 alf.io와 원본의 과거 변경 이력도 보존합니다. [원본 소스](https://github.com/alfio-event/alf.io)는 출처 확인용이며 포크의 지원 창구가 아닙니다.

### 포함된 AppleWWDRCAG4.cer

`src/main/resources/alfio/certificates/AppleWWDRCAG4.cer`는 [passkit4j 기능](https://github.com/ryantenney/passkit4j#usage)에 사용됩니다. 기존 기록의 만료일은 2030-10-12이며 [Apple 인증 기관](https://www.apple.com/certificateauthority/)을 참고하세요.



## 기여 이력

### 포크의 직접 기여

[WookJaes/alf.io 기여 이력](https://github.com/WookJaes/alf.io/graphs/contributors)과 커밋을 확인하세요. GitHub 집계에는 원본에서 이어받은 이력도 포함될 수 있으므로 포크 이후 커밋으로 구분합니다.

### 원본에서 이어받은 코드 기여

[원본 기여자 이력](https://github.com/alfio-event/alf.io/graphs/contributors)을 보존합니다. 아래 번역자 명단도 원본에서 이어받은 기록이며 포크의 직접 참여 명단이 아닙니다.

### 원본 번역 기여자 (POEditor)

[원본 번역 소스](https://github.com/alfio-event/alf.io/tree/master/src/main/resources/alfio/i18n)를 참고하세요. 원본의 이름·프로필 공개 동의 기준과 명단을 유지합니다.

| Language        | Name          | Github  | Twitter |
| -------------   |:--------------| -----:  |---|
| Dutch (nl)      | Matthjis      |    | |
| Turkish (tr)    | Dilek         |      | |
| Spanish (es)    | Mario Varona  |    [@mvarona](https://www.github.com/mvarona)   | [@MarioVarona](https://www.twitter.com/MarioVarona) |
| Spanish (es)    | Sergi Almar   |    [@salmar](https://www.github.com/salmar)   | [@sergialmar](https://www.twitter.com/sergialmar) |
| Spanish (es)    | Jeremias      |    | |
| Bulgarian (bg)  | Martin Zhekov |  [@Martin03](https://www.github.com/Martin03)  | [@MartensZone](https://www.twitter.com/MartensZone) |
| Portuguese (pt) | Hugo          |    | |
| Swedish (sv)    | Johan         |    | |
| Romanian (ro)   | Daniel        |    | |
| Polish (pl)     | Pawel        |    | |
| Danish (da)     | Sune        |    | |

원본에서 번역 완료 후 통합 대기로 기록된 명단 (WIP)

| Language        | Name          | Github  | Twitter |
| -------------   |:--------------| -----:  |---|
| Japanese (jp) | Martin      |    | |
| Chinese (Taiwan) (cn_TW) | Yu-cheng, Lin      |    | |
