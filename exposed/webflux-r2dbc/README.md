# exposed/webflux-r2dbc

[한국어](README.ko.md) | English

`exposed/webflux-r2dbc` is a Spring WebFlux example that uses Kotlin coroutines with Exposed R2DBC.

The module keeps transaction ownership in the service layer. Repositories expose `Flow<T>` reads and `suspend` writes, while services wrap those calls with `suspendTransaction(db = r2dbcDatabase)`. The only JDBC path is startup schema initialization through Hikari.

## Architecture

![exposed/webflux-r2dbc architecture](../../docs/images/readme-diagrams/exposed-webflux-r2dbc-readme-architecture-01.png)

| Area | Implementation | Reader contract |
|---|---|---|
| Web API | `AuthorController`, `BookController`, `ProductController`, and `OrderController` expose suspend WebFlux endpoints. | Request handling stays coroutine-first. |
| R2DBC runtime | `ExposedR2dbcConfig` creates `ConnectionFactoryOptions`, a `ConnectionPool`, and `R2dbcDatabase`. | Exposed R2DBC work runs through the pool-backed database with `Dispatchers.IO`. |
| Service transaction boundary | `AuthorService`, `BookService`, and `OrderService` call `suspendTransaction(db = db)`. | Flow reads are collected inside the transaction before writes on the same connection. |
| Repository primitives | Repositories return `Flow<DTO>` for selects and provide suspend insert/update/delete methods. | Repositories stay thin and do not decide transaction lifetime. |
| Book cursor pagination | `BookRepository.findCursorPage`, `GET /api/books/cursor` | Exposed 2.0.0 `LongR2dbcRepository` keyset pagination returns `nextCursor`/`hasNext` with a bounded `pageSize + 1` query; cursor token encoding, signing, expiry, and scope remain caller-owned. |
| Schema initialization | `DatabaseInitializer` converts the R2DBC URL to JDBC and uses Hikari once at startup. | Blocking JDBC is limited to schema creation, not request processing. |

## Order Placement Flow

![exposed/webflux-r2dbc order placement sequence](../../docs/images/readme-diagrams/exposed-webflux-r2dbc-readme-sequence-01.png)

`OrderService.placeOrder()` opens one coroutine R2DBC transaction. Inside that transaction it inserts the order header, sorts request lines by `productId`, locks each product row with `FOR UPDATE`, writes the order line, and decrements stock.

If stock is short, `InsufficientStockException` is thrown inside the same `suspendTransaction` scope and WebFlux returns a conflict response through `GlobalExceptionHandler`.

## Schema

![exposed/webflux-r2dbc schema ERD](../../docs/images/readme-diagrams/exposed-webflux-r2dbc-readme-erd-01.png)

| Table | Purpose |
|---|---|
| `authors`, `books` | Author/book CRUD uses plain Exposed `Table` definitions and service-owned R2DBC transactions. |
| `products` | Product stock is locked and decremented during order placement. |
| `orders`, `order_lines` | Order placement writes the order header and line rows in the same R2DBC transaction. |

## Key Code Paths

| File | What to inspect |
|---|---|
| `config/ExposedR2dbcConfig.kt` | Pool-backed `R2dbcDatabase` and coroutine dispatcher configuration. |
| `config/DatabaseInitializer.kt` | One-shot JDBC schema creation through Hikari. |
| `author/service/*Service.kt` | `suspendTransaction` ownership and Flow collection rules. |
| `order/service/OrderService.kt` | Product lock ordering, stock conflict, and order write transaction. |
| `author/repository/BookRepository.kt` | `LongR2dbcRepository` adapter, Flow CRUD, and the suspend `findCursorPage` extension. |
| `author/controller/BookController.kt` | Cursor endpoint parameters and the `BookCursorPageResponse` wire shape. |
| `*/schema/*Table.kt` | Exposed table definitions; `BookTable : LongIdTable` supplies the typed keyset ID. |

## Running

The application expects PostgreSQL at `r2dbc:postgresql://localhost:5432/exposedwebflux` with `postgres/postgres`.

```bash
./gradlew :exposed-webflux-r2dbc:bootRun
# http://localhost:8080/swagger-ui/index.html
```

## Tests

```bash
./gradlew :exposed-webflux-r2dbc:test
```

| Test class | Coverage |
|---|---|
| `AuthorControllerTest` | Author/book CRUD plus the bounded cursor endpoint and invalid page-size response. |
| `CursorPaginationRepositoryTest` | sparse ID insert/delete boundaries, page-size guard, and cancellation/resource release with a size-one R2DBC pool. |
| `OrderControllerTest` | Order placement, cancellation, 404, and stock-conflict cases. |
| `ConcurrentPlaceOrderTest` | Concurrent coroutine requests where only one request can consume the last stock item. |

## Cursor Pagination

```text
GET /api/books/cursor?pageSize=2&cursor=3&sortOrder=ASC
```

`BookRepository.findCursorPage` adapts the Exposed 2.0.0 `LongR2dbcRepository`
extension. The primary-key predicate and `pageSize + 1` sentinel keep the query
bounded without a count query; `suspendTransaction` owns the connection and
cooperative cancellation releases it. `nextCursor` is `null` when `hasNext` is
`false`. This workshop sends the raw ID as a transparent token only: a production
caller must encode, sign, expire, and scope the token and reuse the same sort/filter
contract. The repository test covers sparse IDs, insert/delete boundaries, invalid
sizes, and cancellation with a size-one pool.
