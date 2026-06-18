# exposed/javers-audit

[한국어](README.ko.md) | English

This module demonstrates a small audit boundary: the same immutable `Product`
value is committed to JaVers for history and diff queries, while Exposed stores
the current row in `ProductTable`.

![exposed/javers-audit architecture diagram](../../docs/images/readme-diagrams/exposed-javers-audit-readme-architecture-01.png)

## Runtime Flow

![exposed/javers-audit sequence diagram](../../docs/images/readme-diagrams/exposed-javers-audit-readme-sequence-01.png)

## What This Module Shows

| Operation | Source-backed behavior |
|---|---|
| `save(author, product)` | Validates the author, commits the product to JaVers, then upserts the current row through Exposed JDBC |
| `delete(author, product)` | Records a JaVers terminal snapshot with `commitShallowDelete`, then deletes the Exposed row |
| `getHistory(productId)` | Queries JaVers snapshots by instance id and returns them oldest-first |
| `getLatestSnapshot(productId)` | Uses bluetape4k `latestSnapshotOrNull<Product>()` for the current audit state |
| `diff(old, new)` | Compares two values without writing to JaVers or the database |

## Product Schema

`ProductTable` is intentionally small so the audit behavior is easy to inspect.

| Column | Type | Notes |
|---|---|---|
| `id` | `long` | Primary key and JaVers entity id |
| `name` | `varchar(255)` | Current product name |
| `price` | `decimal(19,4)` | Decimal storage, no floating-point rounding |
| `category` | `varchar(100)` | Used by diff tests |

## Usage

```kotlin
val javers = JaversBuilder.javers().build()
val service = ProductAuditService(javers)

val product = Product(1L, "Widget", BigDecimal("9.99"), "Tools")
service.save("alice", product)

val updated = product.copy(price = BigDecimal("12.99"))
service.save("alice", updated)

val history = service.getHistory(1L)
val diff = service.diff(product, updated)
val latest = service.getLatestSnapshot(1L)

service.delete("alice", updated)
```

## Tests

```bash
./gradlew :exposed-javers-audit:test
```

The test suite covers initial/update/terminal snapshots, latest snapshot lookup,
price and category diffs, and the unchanged-value no-diff case.
