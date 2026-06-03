# docker compose-demo

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **docker compose-demo**를 실행 가능한 Docker Compose 테스트 인프라 워크샵 조각으로 다룹니다. 개발자가 가장 먼저 확인할 흐름인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리/프레임워크 API 관찰에 초점을 둡니다.

## 아키텍처 다이어그램

![docker compose-demo architecture diagram](../../docs/images/readme-diagrams/docker-compose-demo-diagram-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성을 중심으로 구성됩니다. 이 README를 코드와 비교할 때는 `io.bluetape4k.workshop.docker` 패키지를 기준으로 삼습니다.

![docker compose-demo Graphviz architecture diagram](../../docs/images/readme-diagrams/docker-compose-demo-readme-architecture-01.png)

## 흐름 다이어그램

1. `docker-compose-demo`에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 작업을 bluetape4k 유틸리티 또는 Spring/Kotlin 통합 기능에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, 메트릭, 트레이스 또는 테스트 기대값으로 보이는 결과를 검증합니다.

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크샵 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 이 모듈에 전용 시퀀스 자산이 있으면 아래 이미지가 상호작용 순서를 보여 줍니다. 그렇지 않으면 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

# FIXME

현재 `DockerComposeContainer`가 정상적으로 실행되지 않습니다.
`docker-compose-plugin` 사용을 권장합니다.

## 개요

이 예제는 `DockerComposeContainer`와 Testcontainers를 사용해 `docker-compose.yml` 파일에서 여러 컨테이너를 실행하는 방법을 보여 줍니다.

![compose demo Architecture diagram](../../docs/images/readme-diagrams/docker-compose-demo-diagram-01.png)

## 참고 자료

* [Docker Compose Module](https://www.testcontainers.org/modules/docker_compose/)
* [How to run Docker Compose with Testcontainers](https://codeal.medium.com/how-to-run-docker-compose-with-testcontainers-7d1ba73afeeb)
* [Simple and Powerful Integration Tests with Gradle and Docker-Compose](https://codeal.medium.com/guide-simple-and-powerful-integration-tests-with-gradle-and-docker-compose-7a27bd06a0cd)

## 문제 해결

### Q. `Container startup failed for image alpine/socat:1.7.4.3-r0` 예외가 발생할 때

[arm64 플랫폼이 없는 오래된 버전으로 alpine/socat 컨테이너가 고정된 문제](https://github.com/testcontainers/testcontainers-java/issues/5279)를 참고하세요.
`~/.testcontainers.properties` 파일에 `socat.container.image=alpine/socat:latest`를 추가합니다.

```shell
$ grep socat ~/.testcontainers.properties
socat.container.image=alpine/socat:latest
```

### Q. linux/amd64 아키텍처만 지원하는 이미지를 사용할 때

Apple Silicon M1에서 linux/amd64 플랫폼용 Docker 이미지를 실행하려면 필요한 라이브러리를 `build.gradle.kts`에 추가하세요.

```kotlin
testImplementation(Libs.jna)
testImplementation(Libs.jna_platform)
```
