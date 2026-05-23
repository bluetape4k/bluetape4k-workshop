# exposed/mvc-jdbc

Spring MVC + Exposed JDBC + Spring declarative transactions example.

## Architecture

```
Controller → Service (@Transactional) → Repository → Exposed JDBC → PostgreSQL
```

## Used Bluetape4k Features

| Feature | Module | Usage |
|---------|--------|-------|
| `KLogging` | `bluetape4k-logging` | Companion object logging in every class |
| `bluetape4k-junit5` | `bluetape4k-junit5` | `Fakers.faker` in tests |
| `bluetape4k-assertions` | `bluetape4k-assertions` | `shouldBeEqualTo`, comparison matchers |
| `bluetape4k-testcontainers` | `bluetape4k-testcontainers` | `PostgreSQLServer.Launcher.postgres` singleton |

## Key Patterns

- **Declarative TX**: `@Transactional(readOnly = true)` on read methods, `@Transactional` on mutations.
- **SELECT FOR UPDATE**: `ProductTable.selectAll().where{...}.forUpdate()` for TOCTOU prevention.
- **Stock check**: `if (stock < quantity) throw InsufficientStockException(productId)` — NOT `require()`.
- **cancelOrder rows check**: `val rows = update{...}; if (rows == 0) throw NoSuchElementException(...)`.
- **Lock ordering**: `req.lines.sortedBy { it.productId }` before iterating to prevent deadlock.

## Running

```bash
# Requires PostgreSQL — use Docker:
docker run -p 5432:5432 -e POSTGRES_PASSWORD=postgres postgres:15

./gradlew :exposed-mvc-jdbc:bootRun
# http://localhost:8080/swagger-ui/index.html
```

## Tests

```bash
./gradlew :exposed-mvc-jdbc:test
# Testcontainers PostgreSQL launched automatically
```

| Test Class | Coverage |
|-----------|---------|
| `AuthorControllerTest` | Author + Book CRUD |
| `ProductControllerTest` | Product CRUD |
| `OrderControllerTest` | Order place, cancel, 404 cases |
| `PlaceOrderRollbackTest` | 3-table rollback on stock failure |
| `ConcurrentPlaceOrderTest` | N=10 threads, stock=1 → exactly 1 success, 9 conflicts |
