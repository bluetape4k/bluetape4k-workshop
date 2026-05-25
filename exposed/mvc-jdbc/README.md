# exposed-mvc-jdbc

Spring MVC + JetBrains Exposed JDBC using **bluetape4k-exposed** table base classes
and repository interfaces for type-safe, boilerplate-free data access.

## Architecture

![Exposed MVC JDBC Architecture](../../docs/images/readme-diagrams/exposed-mvc-jdbc-architecture-01.png)

## Domain Model

```
AuthorTable (AuditableLongIdTable)        BookTable (LongIdTable)
┌──────────────────────────────┐          ┌──────────────────────────────┐
│ id          BIGSERIAL PK     │◄─────────│ id          BIGSERIAL PK     │
│ first_name  VARCHAR(100)     │          │ title       VARCHAR(200)      │
│ last_name   VARCHAR(100)     │          │ publish_date VARCHAR(20)      │
│ email       VARCHAR(255) UQ  │          │ author_id   FK → authors.id  │
│ created_by  VARCHAR(128)     │          └──────────────────────────────┘
│ created_at  TIMESTAMP        │
│ updated_by  VARCHAR(128)?    │
│ updated_at  TIMESTAMP?       │
└──────────────────────────────┘
```

## Used bluetape4k Features

| Feature | Module / Artifact | Code reference | Benefit |
|---------|-------------------|----------------|---------|
| `AuditableLongIdTable` | `bluetape4k-exposed-core` | `AuthorTable.kt` | Audit columns (`createdAt`, `createdBy`, `updatedAt`, `updatedBy`) auto-wired |
| `LongAuditableJdbcRepository` | `bluetape4k-exposed-jdbc` | `AuthorRepository.kt` | `findAll()`, `findById()`, `findPage()`, `count()`, `existsById()`, `deleteById()`, `batchInsert()`, `auditedUpdateById()` all inherited |
| `LongJdbcRepository` | `bluetape4k-exposed-jdbc` | `BookRepository.kt` | Same CRUD inheritance for non-audited table |
| `findBy(vararg filters)` | `bluetape4k-exposed-jdbc` | `BookRepository.findByAuthorId` | Type-safe predicate query — no manual `selectAll().where {}` |
| `KLogging` | `bluetape4k-logging` | Every service/config class | Lazy lambda logging |
| `PostgreSQLServer.Launcher` | `bluetape4k-testcontainers` | `AbstractMvcJdbcTest` | Singleton TC container |

## Before / After

### Table definition

```kotlin
// ❌ Before — manual id + primaryKey + no audit
object AuthorTable : Table("authors") {
    val id = long("id").autoIncrement()
    override val primaryKey = PrimaryKey(id)
}

// ✅ After — bluetape4k AuditableLongIdTable
object AuthorTable : AuditableLongIdTable("authors") {
    // id, primaryKey, createdAt, createdBy, updatedAt, updatedBy are inherited
}
```

### Repository

```kotlin
// ❌ Before — 35 lines of boilerplate CRUD
class AuthorRepository {
    fun findAll() = AuthorTable.selectAll().map { it.toAuthorDTO() }
    fun findById(id: Long) = AuthorTable.selectAll().where { AuthorTable.id eq id }.singleOrNull()?.toAuthorDTO()
    fun deleteById(id: Long) { AuthorTable.deleteWhere { AuthorTable.id eq id } }
    // no findPage(), no batchInsert()
}

// ✅ After — declare intent only
class AuthorRepository : LongAuditableJdbcRepository<AuthorDTO, AuthorTable> {
    override val table = AuthorTable
    override fun extractId(entity: AuthorDTO) = entity.id
    override fun ResultRow.toEntity() = toAuthorDTO()
    // All CRUD + pagination + batch + audited-update inherited
}
```

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
