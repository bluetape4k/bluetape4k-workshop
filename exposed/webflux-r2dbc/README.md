# exposed/webflux-r2dbc

WebFlux + Coroutines + Exposed R2DBC — fully reactive/coroutine data access.

## Architecture

```
Controller (suspend) → Service (suspendTransaction) → Repository (Flow<T>) → R2DBC → PostgreSQL
```

## Used Bluetape4k Features

| Feature | Module | Usage |
|---------|--------|-------|
| `KLoggingChannel` | `bluetape4k-logging` | Coroutine-aware logging in companion objects |
| `bluetape4k-coroutines` | `bluetape4k-coroutines` | Coroutine utilities |
| `bluetape4k-junit5` | `bluetape4k-junit5` | `Fakers.faker` in tests |
| `bluetape4k-assertions` | `bluetape4k-assertions` | `shouldBeEqualTo`, comparison matchers |
| `bluetape4k-testcontainers` | `bluetape4k-testcontainers` | `PostgreSQLServer.Launcher.postgres` singleton |

## Key Patterns

- **Repository layer**: `fun findAll(): Flow<T>`, `suspend fun findById(): T?` — no TX at repo level.
- **Service layer**: `suspendTransaction(db = db) { repo.findAll().toList() }` — Flow consumed INSIDE TX.
- **Schema init**: One-shot JDBC via HikariDataSource + `try/finally { ds.close() }` at startup.
- **Concurrent test**: `runBlocking { coroutineScope { List(N) { async(Dispatchers.IO) { ... } }.awaitAll() } }`.
- **Open-cursor fix**: `.toList()` before mutations — never stream Flow while issuing deletes on same connection.

## Running

```bash
./gradlew :exposed-webflux-r2dbc:bootRun
# http://localhost:8080/swagger-ui/index.html
```

## Tests

```bash
./gradlew :exposed-webflux-r2dbc:test
```

| Test Class | Coverage |
|-----------|---------|
| `AuthorControllerTest` | Author + Book CRUD |
| `OrderControllerTest` | Order place, cancel, 404/409 cases |
| `ConcurrentPlaceOrderTest` | N=10 coroutines, stock=1 → 1 success + 9 conflicts (409) |
