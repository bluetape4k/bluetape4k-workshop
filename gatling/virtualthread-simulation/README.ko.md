# Gatling Virtual Thread Simulation

[English](README.md) | 한국어

## 이 모듈이 보여주는 것

이 모듈은 Spring Boot 애플리케이션이 virtual thread를 사용할 때 두 HTTP
endpoint를 Gatling 부하로 비교합니다.

- `GET /sync/{seconds}`는 request path에서 blocking `Thread.sleep(...)`을 실행합니다.
- `GET /async/{seconds}`는 같은 delay를 virtual-thread executor 기반 `@Async` method로 위임합니다.

두 endpoint는 측정한 elapsed time을 millisecond로 반환합니다. Gatling
simulation은 `/sync/1` 또는 `/async/1`을 호출하고, closed concurrent users를
10초 동안 10명에서 20명으로 ramp합니다.
`{seconds}` path variable은 1..10초로 제한해 부하 테스트 예제가 무제한
request-path sleep을 만들지 않도록 합니다.

## 아키텍처

![Gatling virtual thread architecture](../../docs/images/readme-diagrams/gatling-virtualthread-simulation-readme-architecture-01.png)

`TomcatConfig`는 `Executors.newVirtualThreadPerTaskExecutor()`를 Tomcat
protocol handler executor로 설정합니다. `AsyncConfig`도 Spring
`applicationTaskExecutor`를 virtual-thread executor로 노출하고,
`LoggingTaskDecorator`로 MDC를 보존합니다.

## 부하 테스트 흐름

![Gatling virtual thread flow](../../docs/images/readme-diagrams/gatling-virtualthread-simulation-readme-sequence-01.png)

Gatling을 실행하기 전에 애플리케이션이 `localhost:8080`에서 실행 중이어야
합니다. Simulation class는 `src/gatling/kotlin/simulations` 아래에 있습니다.

## Simulation 구조

| Simulation | Endpoint sequence | Injection profile |
|---|---|---|
| `SyncTaskSimulation` | `/sync/1`, `/sync/2` | `rampConcurrentUsers(10).to(20).during(10.seconds)` |
| `AsyncTaskSimulation` | `/async/1`, `/async/2` | `rampConcurrentUsers(10).to(20).during(10.seconds)` |

## 실행

Spring Boot 애플리케이션을 먼저 실행합니다.

```bash
./gradlew :gatling-virtualthread-simulation:bootRun
```

모든 Gatling simulation을 실행합니다.

```bash
./gradlew :gatling-virtualthread-simulation:gatlingRun
```

하나의 simulation만 실행할 수도 있습니다.

```bash
./gradlew :gatling-virtualthread-simulation:gatlingRun --simulation simulations.SyncTaskSimulation
./gradlew :gatling-virtualthread-simulation:gatlingRun --simulation simulations.AsyncTaskSimulation
```

리포트는 `build/reports/gatling/` 아래에 생성됩니다.

## 결과 이미지

### Sync Task Simulation

![Sync Task Simulation Results](./doc/sync-task-simulation.png)
![Sync Task Simulation Results RPS](./doc/sync-task-simulation-rps.png)

### Async Task Simulation

![Async Task Simulation Results](./doc/async-task-simulation.png)
![Async Task Simulation Results RPS](./doc/async-task-simulation-rps.png)

## bluetape4k 사용 지점

| Library | Usage |
|---|---|
| `bluetape4k-logging` | application, config, controllers, services의 `KLogging` |
| `bluetape4k-io` | `gatling` configuration을 통해 Gatling source set에서 사용 가능 |
| `bluetape4k-jackson3` | Spring Boot JSON serialization support |
| `bluetape4k-coroutines` | Suspend-style WebTestClient assertion test support |
| `bluetape4k-core` | Bounded delay request를 위한 `requireInRange()` validation |

## 소스 기준점

- `src/main/kotlin/io/bluetape4k/workshop/gatling/config/TomcatConfig.kt`
- `src/main/kotlin/io/bluetape4k/workshop/gatling/config/AsyncConfig.kt`
- `src/main/kotlin/io/bluetape4k/workshop/gatling/controller/SyncTaskController.kt`
- `src/main/kotlin/io/bluetape4k/workshop/gatling/controller/AsyncTaskController.kt`
- `src/main/kotlin/io/bluetape4k/workshop/gatling/validation/DelayRequestValidation.kt`
- `src/main/kotlin/io/bluetape4k/workshop/gatling/web/RequestValidationAdvice.kt`
- `src/gatling/kotlin/simulations/SyncTaskSimulation.kt`
- `src/gatling/kotlin/simulations/AsyncTaskSimulation.kt`

## 참고 자료

- [Gatling Documentation](https://gatling.io/docs/)
- [Gatling Gradle Plugin](https://docs.gatling.io/reference/extensions/build-tools/gradle-plugin/)
- [Stress Testing with Gatling & Kotlin - Part 2](https://medium.com/@mdportnov/stress-testing-with-gatling-kotlin-part-2-1eb13d489dc9)
