# exposed/mvc-jdbc

[한국어](README.ko.md) | English

`exposed/mvc-jdbc` is a Spring MVC example that runs JetBrains Exposed over
blocking JDBC. It demonstrates two persistence styles in the same application:
bluetape4k repository inheritance for simple Author/Book CRUD, and explicit
Exposed SQL for order placement where row locks and rollback behavior matter.

## Architecture

![exposed-mvc-jdbc architecture diagram](../../docs/images/readme-diagrams/exposed-mvc-jdbc-readme-architecture-01.png)

The module keeps the HTTP layer thin. Controllers delegate to Spring services,
services define the transaction boundary, and repositories own the Exposed table
access. PostgreSQL is the runtime database in the sample configuration and in
the Testcontainers-backed tests.

| Area | Implementation | Reader contract |
|---|---|---|
| Author and Book CRUD | `AuthorRepository`, `BookRepository` | `LongAuditableJdbcRepository` and `LongJdbcRepository` provide inherited CRUD, paging, counting, existence checks, delete, and batch helpers. |
| Author audit columns | `AuthorTable : AuditableLongIdTable` | `id`, primary key, and audit columns come from bluetape4k instead of being repeated in the example. |
| Book lookup by author | `BookRepository.findByAuthorId` | Uses `findBy({ BookTable.authorId eq EntityID(...) })` instead of handwritten select boilerplate. |
| Order placement | `OrderService.placeOrder` | Creates the order, sorts lines by `productId`, locks each product row, inserts order lines, and decrements stock in one transaction. |
| Stock conflict | `InsufficientStockException` | Insufficient stock aborts the transaction, so partial order lines and stock changes are rolled back. |

## Order Placement Flow

![exposed-mvc-jdbc order placement sequence](../../docs/images/readme-diagrams/exposed-mvc-jdbc-readme-sequence-01.png)

`placeOrder()` intentionally sorts requested lines by `productId` before taking
locks. Each product is read through `ProductRepository.findByIdForUpdate()`,
which issues Exposed's `forUpdate()` query. The service only writes an order
line and decrements stock after the locked row proves enough inventory exists.

## Schema

![exposed-mvc-jdbc schema ERD](../../docs/images/readme-diagrams/exposed-mvc-jdbc-readme-erd-01.png)

| Table | Key columns | Purpose |
|---|---|---|
| `authors` | `id`, `first_name`, `last_name`, `email`, audit columns | Audited CRUD example backed by `AuditableLongIdTable`. |
| `books` | `id`, `title`, `publish_date`, `author_id` | Non-audited CRUD example with a typed FK to `authors`. |
| `products` | `id`, `name`, `price`, `stock` | Product catalog rows locked during order placement. |
| `orders` | `id`, `customer_id`, `order_date`, `status` | Order header row inserted before processing sorted order lines. |
| `order_lines` | `id`, `order_id`, `product_id`, `quantity`, `unit_price` | Line rows inserted only after the locked product row has enough stock. |

## Useful Code Paths

| File | What to inspect |
|---|---|
| `author/schema/AuthorTable.kt` | bluetape4k audited table inheritance. |
| `author/repository/AuthorRepository.kt` | Minimal repository implementation over inherited CRUD. |
| `author/repository/BookRepository.kt` | `findBy` predicate usage for a typed author lookup. |
| `order/service/OrderService.kt` | `@Transactional`, lock ordering, stock check, rollback trigger, and cancel-row check. |
| `order/repository/ProductRepository.kt` | `forUpdate()` and atomic stock decrement expression. |
| `config/DatabaseInitializer.kt` | Schema creation and seed data for the runnable workshop. |

## Running

```bash
docker run -p 5432:5432 -e POSTGRES_PASSWORD=postgres postgres:15

./gradlew :exposed-mvc-jdbc:bootRun
# http://localhost:8080/swagger-ui/index.html
```

## Tests

```bash
./gradlew :exposed-mvc-jdbc:test
```

The tests start PostgreSQL through `PostgreSQLServer.Launcher` and cover Author,
Book, Product, Order, rollback, and concurrent stock-conflict scenarios.
