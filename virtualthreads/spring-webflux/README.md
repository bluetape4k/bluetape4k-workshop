# Spring WebFlux Dispatcher Comparison

[한국어](README.ko.md) | English

This module compares four coroutine dispatcher choices in a Spring WebFlux
application: `Dispatchers.Default`, `Dispatchers.IO`, a custom fixed pool, and a
virtual-thread-per-task dispatcher. Each controller exposes the same endpoints so
tests and Gatling scenarios can compare behavior by path.

## Architecture

![Spring WebFlux dispatcher architecture](../../docs/images/readme-diagrams/virtualthreads-spring-webflux-readme-architecture-01.png)

## Request Flow

![Spring WebFlux dispatcher request flow](../../docs/images/readme-diagrams/virtualthreads-spring-webflux-readme-flow-01.png)

## Dispatcher Paths

| Path | Controller | Dispatcher |
|---|---|---|
| `/default/*` | `DefaultDispatcherController` | `Dispatchers.Default` |
| `/io/*` | `IODispatcherController` | `Dispatchers.IO` |
| `/custom/*` | `CustomDispatcherController` | `newFixedThreadPoolContext(2 * availableProcessors, "custom")` |
| `/virtual-thread/*` | `VirtualThreadDispatcherController` | `Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()` |

## Shared Endpoints

All dispatcher controllers inherit the same endpoint set from
`AbstractDispatcherController`.

| Endpoint | What it exercises |
|---|---|
| `GET /{path}` | Simple suspend endpoint with a short delay |
| `GET /{path}/suspend` | Suspend function execution on the WebFlux coroutine path |
| `GET /{path}/deferred` | `CoroutineScope(dispatcher).async { ... }` |
| `GET /{path}/sequential-flow?size=4` | Sequential `Flow` emission |
| `GET /{path}/concurrent-flow?size=4` | `flatMapMerge(size)` concurrent `Flow` emission |
| `POST /{path}/request-as-flow` | Request body as `Flow<JsonNode>` |
| `GET /httpbin/delay/mono/{seconds}` | WebClient call subscribed on a virtual-thread Reactor scheduler |
| `GET /httpbin/delay/suspend/{seconds}` | WebClient call awaited inside a virtual-thread coroutine dispatcher |

## Runtime Configuration

`NettyConfig` tunes Reactor Netty resources for the sample:

| Setting | Purpose |
|---|---|
| `ConnectionProvider.maxConnections(10_000)` | Allows high concurrency during Gatling runs |
| `LoopResources.create("event-loop", 4, maxOf(availableProcessors * 8, 64), true)` | Uses a bounded event-loop group for WebFlux |
| Read/write timeout handlers | Prevents stuck connections during load tests |

`AsyncConfig` also exposes a Spring `AsyncTaskExecutor` backed by virtual
threads with inheritable thread-local propagation enabled.

## Run

```bash
./gradlew :virtualthreads-spring-webflux:bootRun
```

Run all dispatcher simulations:

```bash
./gradlew :virtualthreads-spring-webflux:gatlingRun
```

Run only the virtual-thread simulation:

```bash
./gradlew :virtualthreads-spring-webflux:gatlingRun \
  --simulation simulations.VirtualThreadCoroutineSimulation
```

Gatling reports are written under
`virtualthreads/spring-webflux/build/reports/gatling/`.

## Test

```bash
./gradlew :virtualthreads-spring-webflux:test
```
