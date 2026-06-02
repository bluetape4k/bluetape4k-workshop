# docker-compose-plugin example

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **docker-compose-plugin example** as a runnable Docker Compose test infrastructure workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.docker` as the source of truth when comparing this README with the code.

![docker-compose-plugin example architecture diagram](../../docs/images/readme-diagrams/docker-compose-plugin-demo-diagram-01.png)

## Flow Diagram

1. Prepare the local runtime required by `docker-compose-plugin-demo`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

이 예제는 [Gradle docker-compose-plugin](https://github.com/avast/gradle-docker-compose-plugin) 을 사용하여 testcontainers 없이도
gradle build script
만으로 docker-compose 를 실행하는 방법을 보여줍니다.

![docker-compose-plugin example diagram](../../docs/images/readme-diagrams/docker-compose-plugin-demo-diagram-01.png)

Custom Server를 구성하고, 테스트 할 때에는 이 방식이 가장 편리합니다.

## Docker Compose Yaml 파일 사전 검증

yml 파일 설정이 제대로 되었는지 확인 합니다.

```shell
$ docker compose -f docker-compose.yml config
$ docker compose -f docker-compose-multiple.yml config
```

다음으로 실제로 dockerized 서비스를 실행해 봅니다.

```shell
$ docker compose -f docker-compose.yml up
```

```shell
$ docker compose -f docker-compose-multiple.yml up
```

## 참고

* [Gradle docker-compose-plugin](https://github.com/avast/gradle-docker-compose-plugin)
* [Docker with Gradle: Getting started with Docker Compose](https://bmuschko.com/blog/gradle-docker-compose/)
