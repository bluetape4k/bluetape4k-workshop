# Gatling Load Testing Tutorial for Kotlin

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Gatling Load Testing Tutorial for Kotlin** as a runnable load-test execution workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Flow Diagram

1. Prepare the local runtime required by `gatling-virtualthread-simulation`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

Original: [github: mdportnov/kotlin-gatling-tutorial](https://github.com/mdportnov/kotlin-gatling-tutorial)

The original uses MySQL, but here, for convenience, Testcontainers + MongoDB are used.

This repository contains the code examples and resources for the
article ["Gatling Load Testing Tutorial"](https://medium.com/@mdportnov/stress-testing-with-gatling-kotlin-part-2-1eb13d489dc9),
which provides an introduction to Gatling, a popular open-source load testing tool for web applications.

## Architecture

![Gatling Load Testing Tutorial for Kotlin Graphviz architecture diagram](../../docs/images/readme-diagrams/gatling-virtualthread-simulation-readme-architecture-01.png)

![Gatling Load Testing Tutorial for Kotlin Diagram 1](../../docs/images/readme-diagrams/gatling-virtualthread-simulation-readme-flow-01.png)

![Gatling Load Testing Tutorial for Kotlin diagram](../../docs/images/readme-diagrams/gatling-virtualthread-simulation-diagram-01.png)

## Simulation Structure

| Simulation Class | Endpoint | Injection Profile | Description |
|---|---|---|---|
| `SyncTaskSimulation` | `/sync/{id}` | ramp 10→20 concurrent users over 10s | Synchronous blocking task on Virtual Thread |
| `AsyncTaskSimulation` | `/async/{id}` | ramp 10→20 concurrent users over 10s | Asynchronous non-blocking task |

```kotlin
// SyncTaskSimulation — ramp load profile
init {
    setUp(
        scn.injectClosed(rampConcurrentUsers(10).to(20).during(10.seconds.toJavaDuration()))
    ).protocols(httpProtocol)
}
```

## Running the Simulations

### Step 1 — Start the Application

The application requires MongoDB (started automatically via Testcontainers):

```bash
./gradlew :gatling-virtualthread-simulation:bootRun
```

Wait until you see `Started KotlinGatlingApplication` in the log.

### Step 2 — Run the Load Tests

```bash
# Run all Gatling simulations
./gradlew :gatling-virtualthread-simulation:gatlingRun

# Run a specific simulation by class name
./gradlew :gatling-virtualthread-simulation:gatlingRun --simulation simulations.SyncTaskSimulation
./gradlew :gatling-virtualthread-simulation:gatlingRun --simulation simulations.AsyncTaskSimulation
```

### Step 3 — View the Report

Reports are generated in `build/reports/gatling/`. Open the `index.html` in the latest timestamped directory.

```
build/reports/gatling/
└── synctasksimulation-<timestamp>/
    └── index.html       ← open this
```

## Interpreting Results

### Key Metrics in the Report

| Metric | Good | Investigate |
|---|---|---|
| **Response time (mean)** | < 200ms | > 500ms |
| **Response time (95th percentile)** | < 500ms | > 1000ms |
| **Requests/sec (throughput)** | Stable plateau | Gradual decline |
| **Error %** | 0% | > 1% |
| **Active users** | Tracks injection profile | Flat-lines below target |

### Load Profile Annotations

The Gatling report charts show the following phases:

1. **Ramp-up**: users increase from 10 to 20 over 10 seconds
2. **Plateau**: (not configured in this simulation — ramp ends after 10s)
3. **Response time distribution**: histogram shows p50/p75/p95/p99

### Virtual Thread Impact

With Virtual Threads enabled (configured in `TomcatConfig`), blocking I/O in `SyncTaskService`
does not pin OS threads. The Sync endpoint should handle the same load as the Async endpoint with similar latency.

```
Platform thread model (before Virtual Threads):
    10 concurrent requests → needs 10 OS threads → thread pool exhaustion at ~200 threads

Virtual thread model:
    10 concurrent requests → 10 virtual threads on 2–4 OS threads → no pool exhaustion
```

### Stop Conditions

Gatling simulations stop when:
1. The injection profile is complete (normal termination)
2. A threshold assertion fails (if `assertions` block is configured)
3. The JVM is interrupted (Ctrl+C)

To add stop conditions:

```kotlin
init {
    setUp(
        scn.injectClosed(rampConcurrentUsers(10).to(20).during(10.seconds.toJavaDuration()))
    ).protocols(httpProtocol)
     .assertions(
         global().responseTime().max().lt(1000),   // fail if any response > 1s
         global().successfulRequests().percent().gt(99.0)  // fail if error rate > 1%
     )
}
```

## Used bluetape4k Features

| Feature | Artifact | Code Location | Benefit |
|---|---|---|---|
| `KLogging` | `bluetape4k-logging` | `SyncTaskController`, `AsyncTaskController` | Kotlin DSL lazy logging |
| `KLoggingChannel` | `bluetape4k-logging` | `AsyncTaskService` | Coroutine context-aware logging |
| Testcontainers MongoDB wrapper | `bluetape4k-testcontainers` | `AbstractGatlingTest` | MongoDB singleton container, reused across tests |
| Jackson 3.x support | `bluetape4k-jackson3` | REST API serialization | Spring Boot 4 + Jackson 3 auto-configuration |

## Before / After

### Tomcat Virtual Thread Configuration

```kotlin
// Before — no Virtual Thread configuration (platform thread pool)
// Tomcat defaults: max 200 threads → bottleneck at high concurrency

// After — Virtual Thread per request
@Configuration
class TomcatConfig {
    @Bean
    fun protocolHandlerVirtualThreadExecutorCustomizer(): TomcatProtocolHandlerCustomizer<*> {
        return TomcatProtocolHandlerCustomizer<ProtocolHandler> { protocolHandler ->
            protocolHandler.executor = Executors.newVirtualThreadPerTaskExecutor()
        }
    }
}
```

### Async Configuration with Virtual Threads

```kotlin
// Before — standard async executor (fixed thread pool)
@Configuration
@EnableAsync
class AsyncConfig {
    @Bean
    fun asyncTaskExecutor(): AsyncTaskExecutor =
        SimpleAsyncTaskExecutor()

// After — Virtual Thread per async task
@Configuration(proxyBeanMethods = false)
@EnableAsync
class AsyncConfig {
    @Bean(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    fun asyncTaskExecutor(): AsyncTaskExecutor {
        val factory = Thread.ofVirtual().name("async-vt-exec-", 0).factory()
        return TaskExecutorAdapter(Executors.newThreadPerTaskExecutor(factory)).apply {
            setTaskDecorator(LoggingTaskDecorator())
        }
    }
}
```

## Simulation Results

### Sync Task Simulation

![Sync Task Simulation Results](./doc/sync-task-simulation.png)
![Sync Task Simulation Results RPS](./doc/sync-task-simulation-rps.png)

### Async Task Simulation

![Async Task Simulation Results](./doc/async-task-simulation.png)
![Async Task Simulation Results RPS](./doc/async-task-simulation-rps.png)

## Prerequisites

- Docker (for Testcontainers MongoDB)
- JDK 25
- Port 8080 available when running `bootRun`

## Resources

- [Gatling official website](https://gatling.io/)
- [Gatling documentation](https://gatling.io/docs/)
- [Gatling Gradle Plugin](https://docs.gatling.io/reference/extensions/build-tools/gradle-plugin/)
- [Stress Testing with Gatling & Kotlin - Part 2](https://medium.com/@mdportnov/stress-testing-with-gatling-kotlin-part-2-1eb13d489dc9)
- [Gatling community resources](https://gatling.io/community/)
