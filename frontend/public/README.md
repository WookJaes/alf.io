# AlfioPublicFrontend

[WookJaes/alf.io](https://github.com/WookJaes/alf.io)의 공개 Angular 프론트입니다. 원본에서 파생한 코드 수정·검증용 포크이며 프론트 앱을 별도로 배포하지 않습니다. 원본 배지·공식 서비스 안내를 포크의 상태로 사용하지 않습니다. 원본 출처·라이선스·기여 이력은 [루트 README](../../README.md)를 확인하세요.

## 개발·빌드

저장소 루트에서 Gradle Wrapper를 사용하면 저장소에 지정된 Node·pnpm과 의존성 준비 task를 사용합니다.

```sh
./gradlew frontendPublicDev
./gradlew frontendPublicBuild
./gradlew frontendCheck
```

Windows PowerShell에서는 `.\gradlew.bat`를 사용합니다. 공개 개발 서버는 `http://localhost:4200/`이며 API는 `localhost:8080`의 백엔드가 필요합니다. 백엔드는 별도 터미널에서 `./gradlew -PfrontendDev=false bootRun`으로 실행할 수 있습니다.

이미 의존성이 준비된 경우 `frontend/public`에서 `pnpm run start`·`pnpm run build`를 사용할 수 있습니다. 현행 build 스크립트는 `ng build --aot=true --configuration production`이며 산출물은 `dist/alfio-public-frontend/`에 생성됩니다. 과거 `--prod` 옵션을 사용하지 않습니다.

## 검증과 기여

프론트 빌드·컴파일은 실제 테스트와 구분합니다. `package.json`의 `test`는 [Karma](https://karma-runner.github.io), `e2e`는 [Protractor](http://www.protractortest.org/) 기반 기존 스크립트이며 실행 환경 준비 없이 통과를 보장하지 않습니다. 현재 포크의 자동 예약 E2E·Python 회귀·macOS 및 Windows 준비 방법은 [자동 검증 안내](../../docs/testing/automatic-validation.md)를 따릅니다.

기여·문제 보고는 [WookJaes/alf.io 이슈](https://github.com/WookJaes/alf.io/issues)와 `main` 대상 PR로 진행합니다. [Angular CLI 문서](https://github.com/angular/angular-cli/blob/master/README.md)는 기술 참고로 보존합니다. 구성요소 생성에는 준비된 개발 환경에서 `pnpm exec ng generate component component-name`, 도움말에는 `pnpm exec ng help`를 사용할 수 있습니다.
