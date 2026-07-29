# Issue #88: Async/Reactive Advanced README 개선

## 개요

bluetape4k-workshop의 6개 모듈 README를 `Used bluetape4k Features` 표,
`Before/After` code snippet, Mermaid sequence diagram, cancellation/structured-concurrency
섹션으로 강화했다. code 변경은 없고 문서 변경만 수행했다.

## 갱신한 모듈

| Module | Action | Key bluetape4k APIs |
|---|---|---|
| `vertx/coroutines` | Added Mermaid diagram + cancellation section | `suspendHandler`, `coAwait()`, `CoroutineVerticle` scope |
| `vertx/vertx-sqlclient` | Added Mermaid diagram + cancellation section | `withSuspendTransaction`, `testWithSuspendTransaction`, `MySQL8Server.Launcher` |
| `vertx/vertx-webclient` | Added Mermaid diagram + cancellation section | `suspendHandler`, `Jackson`, `coAwait()` |
| `spring-data/r2dbc-examples` | Full rewrite with table, Before/After, Mermaid, cancellation | `connectionFactoryInitializer`, `buildExampleMatcher`, `KLoggingChannel` |
| `spring-data/mongodb-coroutines` | Full rewrite with table, Before/After, Mermaid, cancellation | `MongoDBServer.Launcher`, `Flow.log()`, `runSuspendIO`, `KLoggingChannel` |
| `spring-data/mongodb-transactions` | Full rewrite with table, Before/After, Mermaid, cancellation | `MongoDBServer.Launcher`, `uninitialized()`, `@Transactional suspend fun` |

## 방법론: 실제 import에 근거

feature table과 Before/After snippet은 각 모듈 source file의 실제
`import io.bluetape4k.*` 분석 결과를 바탕으로 작성했다. task brief는
`bluetape4k-mongodb` API를 언급했지만 build file에는 그런 dependency가 없었다.
따라서 code가 실제로 import하는 항목만 문서화했다.

## 주요 발견 사항

### `spring-data/r2dbc-examples`

- `bluetape4k-r2dbc`는 lambda로 `ConnectionFactoryInitializer` bean을 만드는
  `connectionFactoryInitializer { }` DSL을 제공해 boilerplate를 줄인다.
- `bluetape4k-spring-boot4-r2dbc`는 type-safe QBE(Query-by-Example) matcher
  구성을 위한 `buildExampleMatcher(vararg props)`를 제공한다.
- R2DBC `CoroutineCrudRepository`의 Flow cancellation은 R2DBC publisher로 다시
  전파되어 scope cancellation 시 connection을 pool에 반환한다.

### `spring-data/mongodb-coroutines`

- `MongoDBServer.Launcher.mongoDB` singleton은 `MongoClientConfig`와
  `ReactiveMongoConfig`에서 사용되며, 모든 테스트가 하나의 Testcontainers instance를
  공유한다.
- `io.bluetape4k.coroutines.flow.extensions.log`(`Flow.log("label")`)는
  `FlowAndCoroutineTest`에서 element별 debug-level logging에 사용된다.
- `io.bluetape4k.junit5.coroutines.runSuspendIO`는 flow/coroutine 테스트의 test runner다.
- `Flux<Person>`으로 노출된 `@Tailable` cursor는 backpressure를 유지한 채 `Flow`로
  bridge할 수 있다.

### `spring-data/mongodb-transactions`

- `ReactiveMongoTransactionManager`와 함께 쓰는 `@Transactional suspend fun`은
  `kotlinx-coroutines-reactor`가 Reactor Context(session)를 `ReactorContext` element를
  통해 coroutine context로 bridge하므로 동작한다.
- `io.bluetape4k.support.uninitialized()`는 `@Autowired` test field의 `lateinit var`를
  대체한다.
- `@Transactional suspend fun` 실행 중 coroutine cancellation이 발생하면 rollback이
  trigger된다. Spring AOP가 `CancellationException`을 감지하고 transaction을 abort한다.

### Vertx modules (보강)

- `CoroutineVerticle` scope cancellation on undeploy cancels all child `launch { }` coroutines.
- `coAwait()` cancellation propagates to the underlying Vert.x `Future` — aborts in-flight
  DB queries and HTTP requests, returning resources to their pools.
- `vertx.dispatcher()` in `coroutineContext` keeps Vert.x API calls on the event loop thread.

## 적용한 문서화 원칙

1. **실제 code 근거**: import 분석에 기반하고, 존재하지 않는 API를 만들지 않는다.
2. **정직한 Before/After**: Before는 standard library 접근을 보여주고, After는
   bluetape4k 이점을 보여준다.
3. **한국어 section header**: workspace CLAUDE.md policy에 따라 기존 한국어 style을
   보존했다.
4. **vertx는 additive 방식**: 기존 vertx table/Before-After를 보존하고, issue #86에서
   이미 작성된 내용을 흐트러뜨리지 않으면서 Mermaid와 cancellation 섹션을 추가했다.
