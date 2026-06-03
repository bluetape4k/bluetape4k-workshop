# docker compose-demo

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **docker compose-demo** as a runnable Docker Compose test infrastructure workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![docker compose-demo architecture diagram](../../docs/images/readme-diagrams/docker-compose-demo-diagram-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.docker` as the source of truth when comparing this README with the code.

![docker compose-demo Graphviz architecture diagram](../../docs/images/readme-diagrams/docker-compose-demo-readme-architecture-01.png)

## Flow Diagram

1. Prepare the local runtime required by `docker-compose-demo`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

# FIXME

Currently `DockerComposeContainer` is not running properly.
We recommend using `docker-compose-plugin`.

## outline

This example shows how to run multiple containers from `docker-compose.yml` files using `DockerComposeContainer` and `Testcontainers`.

![compose demo Architecture diagram](../../docs/images/readme-diagrams/docker-compose-demo-diagram-01.png)

## reference

* [Docker Compose Module](https://www.testcontainers.org/modules/docker_compose/)
* [How to run Docker Compose with Testcontainers](https://codeal.medium.com/how-to-run-docker-compose-with-testcontainers-7d1ba73afeeb)
* [Simple and Powerful Integration Tests with Gradle and Docker-Compose](https://codeal.medium.com/guide-simple-and-powerful-integration-tests-with-gradle-and-docker-compose-7a27bd06a0cd)

## Throuble Shooting

### Q. `Container startup failed for image alpine/socat:1.7.4.3-r0` When an exception occurs

[alpine/socat container pinned at old version lacking arm64 platform](https://github.com/testcontainers/testcontainers-java/issues/5279)
Refer to and add `socat.container.image=alpine/socat:latest` to the `~/.testcontainers.properties` file.

```shell
$ grep socat ~/.testcontainers.properties
socat.container.image=alpine/socat:latest
```

### Q. When using an image that is only supported by linux/amd64 architecture

Please add the necessary libraries to `build.gradle.kts` to run Docker image for linux/amd64 platform on Apple Silicon M1.

```kotlin
testImplementation(Libs.jna)
testImplementation(Libs.jna_platform)
```
