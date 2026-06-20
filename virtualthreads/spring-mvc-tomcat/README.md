# Spring MVC on Virtual Threads

[한국어](README.ko.md) | English

This module shows a blocking Spring MVC application running on Java virtual
threads with embedded Tomcat. It keeps the MVC programming model, but moves
request handling, `@Async` work, and selected parallel tasks onto virtual-thread
executors.

## Architecture

![Spring MVC virtual thread architecture](../../docs/images/readme-diagrams/virtualthreads-spring-mvc-tomcat-readme-architecture-01.png)

## Request Flow

![Spring MVC virtual thread request sequence](../../docs/images/readme-diagrams/virtualthreads-spring-mvc-tomcat-readme-sequence-01.png)

## What To Look At

| Area | Code | What it demonstrates |
|---|---|---|
| Tomcat request executor | `config/TomcatConfig` | Replaces Tomcat's protocol handler executor with `newVirtualThreadPerTaskExecutor()` |
| Spring `@Async` | `config/AsyncConfig` | Runs async methods on a virtual-thread executor and keeps MDC context |
| Explicit executor bean | `config/VirtualThreadConfig` | Provides a named virtual-thread-per-task executor for helper APIs |
| Parallel blocking work | `controller/VirtualThreadController` | Uses `structuredTaskScopeAll` and `virtualFutureAll` for many blocking tasks |
| MVC + JPA endpoints | `controller/MemberController`, `controller/TeamController` | Runs repository calls from MVC endpoints and virtual-thread helpers |
| Blocking HTTP call | `controller/HttpbinController` | Calls the Testcontainers-backed httpbin service from a blocking MVC endpoint |

## Endpoints

| Endpoint | Purpose |
|---|---|
| `GET /virtual-thread` | Confirms the request is handled in the virtual-thread setup and triggers `@Async` work |
| `GET /virtual-thread/multi` | Runs 100 blocking subtasks with `structuredTaskScopeAll` |
| `GET /virtual-thread/virtualFutureAll` | Runs 100 blocking subtasks with `virtualFutureAll` |
| `GET /member`, `GET /member/{id}` | Reads JPA members through the MVC controller |
| `POST /member/search` | Runs the Querydsl-backed member search |
| `GET /team`, `GET /team/{id}`, `GET /team/name/{name}` | Reads team data through the MVC controller |
| `GET /httpbin/block/{seconds}` | Exercises a blocking external HTTP call through the httpbin test server |

## Configuration

```yaml
spring:
  threads:
    virtual:
      enabled: true

server:
  tomcat:
    threads:
      max: 800
      min-spare: 20
```

`TomcatConfig` is the decisive sample code: it customizes the embedded Tomcat
protocol handler so each request can run on a virtual thread. The application
uses an in-memory H2 database by default and initializes sample `Team` and
`Member` rows at startup.

## Run

```bash
./gradlew :virtualthreads-spring-mvc-tomcat:bootRun
```

Run the JPA load scenario after the application starts:

```bash
./gradlew :virtualthreads-spring-mvc-tomcat:gatlingRun --simulation simulations.JpaSimulation
```

Gatling reports are written under
`virtualthreads/spring-mvc-tomcat/build/reports/gatling/`.

## Test

```bash
./gradlew :virtualthreads-spring-mvc-tomcat:test
```
