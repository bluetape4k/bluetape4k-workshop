# exposed/mvc-virtualthread

Spring MVC + Virtual Threads + Exposed JDBC — **no `@Transactional`**.

## Architecture

```
Controller → Service (virtualFuture) → Repository (virtualFuture) → transaction(db) → PostgreSQL
             └─ VT executor (TomcatConfig)
```

## Used Bluetape4k Features

| Feature | Module | Usage |
|---------|--------|-------|
| `KLogging` | `bluetape4k-logging` | Companion object logging |
| `virtualFuture(executor) { }` | `bluetape4k-virtualthread-api` | Submits DB work to VT executor |
| `ShutdownQueue.register(executor)` | `bluetape4k-virtualthread-api` | Graceful shutdown of VT executor |
| `bluetape4k-virtualthread-jdk21` | `bluetape4k-virtualthread-jdk21` | JDK 21 VT runtime |
| `bluetape4k-junit5` | `bluetape4k-junit5` | `Fakers.faker` in tests |
| `bluetape4k-assertions` | `bluetape4k-assertions` | `shouldBeEqualTo`, comparison matchers |
| `bluetape4k-testcontainers` | `bluetape4k-testcontainers` | `PostgreSQLServer.Launcher.postgres` singleton |

## Key Patterns

- **VT Executor bean**: `@Bean fun virtualThreadExecutor(): ExecutorService` in `TomcatConfig` + `TomcatProtocolHandlerCustomizer`.
- **TX pattern**: `virtualFuture(executor) { transaction(db) { ... } }.get()` — NOT `@Transactional`.
- **Exception unwrapping**: `GlobalExceptionHandler` handles `ExecutionException`/`CompletionException` wrapping from `Future.get()`.
- **`@Transactional` free**: verified via `rg "@Transactional" src/main/` → 0 results.

## Running

```bash
./gradlew :exposed-mvc-virtualthread:bootRun
# http://localhost:8081/swagger-ui/index.html
```

## Tests

```bash
./gradlew :exposed-mvc-virtualthread:test
```

| Test Class | Coverage |
|-----------|---------|
| `AuthorControllerTest` | Author + Book CRUD |
| `ProductControllerTest` | Product CRUD |
| `OrderControllerTest` | Order place, cancel, 404/409 cases |
| `PlaceOrderRollbackTest` | Rollback on stock failure |
| `ConcurrentPlaceOrderTest` | N=10 VT threads, stock=1 → 1 success, 9 conflicts (409) |
