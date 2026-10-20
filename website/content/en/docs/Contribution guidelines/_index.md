---
title: "Contribution Guidelines"
linkTitle: "Contribution Guidelines"
weight: 10
description: >
  How to contribute to the docs
---

이 웹사이트 소스는 코드 수정·검증용 포크의 보존 문서이며 별도로 배포하지 않습니다. 포크에서는 Netlify 배포 미리보기나 문서 사이트 운영을 제공하지 않습니다.

## WookJaes/alf.io에 기여하기

1. [포크 이슈](https://github.com/WookJaes/alf.io/issues)에서 중복을 확인하고 공개 가능한 문제·재현 조건을 작성합니다.
2. [WookJaes/alf.io](https://github.com/WookJaes/alf.io)를 포크하고 최신 `main`에서 작업 브랜치를 만듭니다.
3. 필요한 문서·링크만 수정하고 관련 검사를 수행합니다. 새 운영 안내는 한국어로 작성합니다.
4. 대상 `WookJaes/alf.io`, 기준 `main`으로 PR을 등록합니다. 저장소의 README·Git 규칙·PR 양식을 따릅니다.

## 로컬 문서 확인

[Hugo](https://gohugo.io/)와 [Docsy](https://github.com/google/docsy)를 사용합니다. [Docsy 준비 안내](https://www.docsy.dev/docs/getting-started/)에 따라 Hugo extended 등 필요한 도구를 준비하고 서브모듈을 포함해 소스를 받습니다.

```sh
git clone --recurse-submodules https://github.com/WookJaes/alf.io.git
cd alf.io/website
hugo server
```

로컬 주소는 기본 `http://localhost:1313/`입니다. 로컬 확인은 사이트 배포를 뜻하지 않습니다.

## 기술 참고·출처

[Docsy 사용자 안내](https://www.docsy.dev/docs/), [Hugo 문서](https://gohugo.io/documentation/), [GitHub PR 안내](https://help.github.com/articles/about-pull-requests/), [Draft PR 안내](https://github.blog/2019-02-14-introducing-draft-pull-requests/), [GitHub 시작 안내](https://guides.github.com/activities/hello-world/)를 참고하세요. 이 페이지는 [Docsy 예제 프로젝트](https://github.com/google/docsy-example)의 기여 안내에서 파생했습니다.
