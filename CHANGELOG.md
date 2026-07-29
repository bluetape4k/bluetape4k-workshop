# Changelog

`bluetape4k-workshop`의 주요 변경 사항을 이 문서에 기록합니다.

형식은 [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)를 따릅니다.
이 저장소는 실행 가능한 워크숍과 예제 애플리케이션을 제공합니다.

## [Unreleased]

### Added

- 루트 README 영웅 이미지와 프로젝트 목적, 기능 진입점 문서를 갱신했습니다.
- CI와 nightly 워크플로를 추가했습니다 ([PR #16](https://github.com/bluetape4k/bluetape4k-workshop/pull/16)).
- 로컬 OMX 프로젝트 메모리 무시 규칙을 추가했습니다 ([PR #21](https://github.com/bluetape4k/bluetape4k-workshop/pull/21)).

### Changed

- 현재 할당된 GitHub 이슈 기준으로 WIP 스냅샷을 갱신했습니다.
- 워크숍 예제에 Spring Boot 4.0.0을 적용했습니다 ([PR #7](https://github.com/bluetape4k/bluetape4k-workshop/pull/7)).
- `buildSrc` 의존성 선언을 `gradle/libs.versions.toml`로 이전하고 Gradle을 9.5.0으로 업그레이드했습니다 ([PR #15](https://github.com/bluetape4k/bluetape4k-workshop/pull/15)).
- 테스트 코드를 bluetape4k-assertions에서 `bluetape4k-assertions`로 이전했습니다 ([PR #20](https://github.com/bluetape4k/bluetape4k-workshop/pull/20)).

### Fixed

- Spring Boot 4.0.6, AspectJ, Logback override, protobuf `protoc` 호환성 문제를 수정했습니다 ([PR #17](https://github.com/bluetape4k/bluetape4k-workshop/pull/17)).
- GraalVM Native 1.1.0과 `spring-context` 버전 참조 문제를 수정했습니다 ([PR #18](https://github.com/bluetape4k/bluetape4k-workshop/pull/18)).

## Earlier History

- Quarkus 프레임워크 예제를 추가했습니다 ([PR #2](https://github.com/bluetape4k/bluetape4k-workshop/pull/2)).
- Spring Security 예제를 추가했습니다 ([PR #1](https://github.com/bluetape4k/bluetape4k-workshop/pull/1)).
- Exposed 예제를 추가했습니다 ([PR #5](https://github.com/bluetape4k/bluetape4k-workshop/pull/5)).
