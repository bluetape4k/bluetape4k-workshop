# Issue #88: Async/Reactive Advanced README Enhancement

## Overview

Enhanced 6 module READMEs in bluetape4k-workshop with `Used bluetape4k Features` tables,
`Before/After` code snippets, Mermaid sequence diagrams, and cancellation/structured-concurrency
sections. No code changes — documentation only.

## Updated Modules

| Module | Action | Key bluetape4k APIs |
|---|---|---|
| `vertx/coroutines` | Added Mermaid diagram + cancellation section | `suspendHandler`, `coAwait()`, `CoroutineVerticle` scope |
| `vertx/vertx-sqlclient` | Added Mermaid diagram + cancellation section | `withSuspendTransaction`, `testWithSuspendTransaction`, `MySQL8Server.Launcher` |
| `vertx/vertx-webclient` | Added Mermaid diagram + cancellation section | `suspendHandler`, `Jackson`, `coAwait()` |
| `spring-data/r2dbc-examples` | Full rewrite with table, Before/After, Mermaid, cancellation | `connectionFactoryInitializer`, `buildExampleMatcher`, `KLoggingChannel` |
| `spring-data/mongodb-coroutines` | Full rewrite with table, Before/After, Mermaid, cancellation | `MongoDBServer.Launcher`, `Flow.log()`, `runSuspendIO`, `KLoggingChannel` |
| `spring-data/mongodb-transactions` | Full rewrite with table, Before/After, Mermaid, cancellation | `MongoDBServer.Launcher`, `uninitialized()`, `@Transactional suspend fun` |

## Methodology: Grounded on Real Imports

Features tables and Before/After snippets were authored from real `import io.bluetape4k.*`
analysis of each module's source files. The task brief mentioned `bluetape4k-mongodb` API but
no such dependency exists in the build files; documented only what the code actually imports.

## Key Findings

### `spring-data/r2dbc-examples`

- `bluetape4k-r2dbc` provides `connectionFactoryInitializer { }` DSL for building
  `ConnectionFactoryInitializer` beans with a lambda — reduces boilerplate.
- `bluetape4k-spring-boot4-r2dbc` provides `buildExampleMatcher(vararg props)` for
  type-safe QBE (Query-by-Example) matcher construction.
- R2DBC `CoroutineCrudRepository` Flow cancellation propagates back to the R2DBC publisher,
  returning connections to the pool on scope cancellation.

### `spring-data/mongodb-coroutines`

- `MongoDBServer.Launcher.mongoDB` singleton is used in `MongoClientConfig` and
  `ReactiveMongoConfig` — all tests share one Testcontainers instance.
- `io.bluetape4k.coroutines.flow.extensions.log` (`Flow.log("label")`) is used in
  `FlowAndCoroutineTest` for debug-level per-element logging.
- `io.bluetape4k.junit5.coroutines.runSuspendIO` is the test runner in flow/coroutine tests.
- `@Tailable` cursor exposed as `Flux<Person>` can be bridged to `Flow` with backpressure.

### `spring-data/mongodb-transactions`

- `@Transactional suspend fun` with `ReactiveMongoTransactionManager` works because
  `kotlinx-coroutines-reactor` bridges Reactor Context (session) into coroutine context via
  `ReactorContext` element.
- `io.bluetape4k.support.uninitialized()` replaces `lateinit var` for `@Autowired` test fields.
- Coroutine cancellation during `@Transactional suspend fun` triggers rollback — Spring AOP
  detects `CancellationException` and aborts the transaction.

### Vertx modules (보강)

- `CoroutineVerticle` scope cancellation on undeploy cancels all child `launch { }` coroutines.
- `coAwait()` cancellation propagates to the underlying Vert.x `Future` — aborts in-flight
  DB queries and HTTP requests, returning resources to their pools.
- `vertx.dispatcher()` in `coroutineContext` keeps Vert.x API calls on the event loop thread.

## Documentation Principles Applied

1. **Real code grounding**: import analysis — no fabricated APIs.
2. **Honest Before/After**: Before shows standard library approach; After shows bluetape4k benefit.
3. **Korean section headers**: preserved existing Korean style per workspace CLAUDE.md policy.
4. **Additive for vertx**: existing vertx tables/Before-After preserved; Mermaid and cancellation
   sections added without disturbing the content already written in issue #86.
