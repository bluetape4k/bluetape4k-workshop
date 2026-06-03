# exposed/javers-audit

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **exposed/javers-audit** as a runnable Exposed data access workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Flow Diagram

1. Prepare the local runtime required by `exposed-javers-audit`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

JaVers entity change-history auditing integrated with JetBrains Exposed JDBC and an H2 in-memory database.

## Architecture

![javers audit Architecture diagram](../../docs/images/readme-diagrams/exposed-javers-audit-architecture-01.png)

![exposed/javers-audit Graphviz architecture diagram](../../docs/images/readme-diagrams/exposed-javers-audit-readme-architecture-01.png)

## Core Features

| Feature | Description |
|---|---|
| Change tracking | Every `save` / `delete` call creates a JaVers commit; the full snapshot history is queryable by entity id |
| Diff computation | `diff(old, new)` returns a structured `Diff` with typed `ValueChange` entries — no manual audit table required |
| In-memory store | Uses JaVers built-in in-memory repository — no external infrastructure needed for tests or demos |
| Exposed persistence | Side-by-side upsert/delete into an Exposed `ProductTable` shows how audit history coexists with normal JDBC storage |

## Before / After — Manual Audit Table vs JaVers

**Before (manual audit log table)**

```sql
CREATE TABLE product_audit_log (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT       NOT NULL,
    changed_at TIMESTAMP    NOT NULL,
    author     VARCHAR(100) NOT NULL,
    old_price  DECIMAL(19,4),
    new_price  DECIMAL(19,4),
    old_cat    VARCHAR(100),
    new_cat    VARCHAR(100)
);
```

Every time a column changes you add an INSERT to the audit table. You also need to compare old/new values manually and keep the schema in sync with the entity.

**After (JaVers)**

```kotlin
val javers = JaversBuilder.javers().build()
javers.commit("alice", product)           // snapshot stored automatically
val history = javers.findSnapshots(
    QueryBuilder.byInstanceId(productId, Product::class.java).build()
)
val diff = javers.compare(oldProduct, newProduct)
val priceChanges = diff.changesByType<ValueChange>()
    .filter { it.propertyName == "price" }
```

JaVers tracks all properties automatically, generates a queryable snapshot tree, and provides typed diff access — with no additional schema maintenance.

## Usage

```kotlin
// Build JaVers with in-memory repository
val javers = JaversBuilder.javers().build()
val service = ProductAuditService(javers)

// Create and persist a product
val product = Product(id = 1L, name = "Widget", price = BigDecimal("9.99"), category = "Tools")
service.save("alice", product)

// Update price and persist
val updated = product.copy(price = BigDecimal("12.99"))
service.save("alice", updated)

// Query full history
val history = service.getHistory(1L)          // 2 snapshots: INITIAL + UPDATE

// Compute diff between two versions
val diff = service.diff(product, updated)
val changes = diff.changesByType<ValueChange>()  // [ValueChange: price 9.99 → 12.99]

// Latest snapshot
val latest = service.getLatestSnapshot(1L)

// Soft-delete with terminal snapshot
service.delete("alice", updated)
```

## Configuration

No external configuration is required. Pass any `Javers` instance to `ProductAuditService`. For production use, replace the in-memory repository with a JDBC or Redis-backed one from the `bluetape4k-javers` library.

## Dependencies

```kotlin
implementation("io.github.bluetape4k.javers:javers-core")  // bluetape4k JaVers integration
implementation("org.jetbrains.exposed:exposed-core")
implementation("org.jetbrains.exposed:exposed-jdbc")
runtimeOnly("com.h2database:h2")
```
