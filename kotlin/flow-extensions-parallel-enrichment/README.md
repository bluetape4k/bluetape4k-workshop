# Flow Extensions Parallel Enrichment

[한국어](README.ko.md) | English

This example demonstrates `Flow.parallel(...)` with enriched order processing:

- parallel fan-out for customer profile, inventory, and promotion lookups
- `Flow` pipeline filtering and transformation in `ParallelFlow`
- exception propagation for missing customers/products
- sequential fallback path for comparison

## Scenario

![Scenario](../../docs/images/readme-diagrams/kotlin-flow-extensions-parallel-enrichment-readme-scenario-01.png)

Each order enters a stream. Valid orders are sent into a parallel rail, where each rail:

1. Loads customer profile
2. Loads stock snapshot for requested SKUs
3. Calculates discount based on loyalty grade
4. Combines into an `EnrichedOrder`

Orders that are invalid (`orderId`, `customerId`, or items missing) are filtered out before enrichment.

## Architecture

![Architecture](../../docs/images/readme-diagrams/kotlin-flow-extensions-parallel-enrichment-readme-architecture-01.png)

`OrderEnrichmentPipeline` owns the orchestration:

- `parallel` splits candidate orders into rails.
- `map` runs enrichment in each rail.
- `sequential` merges rail outputs for downstream collection.

## Domain model

![Domain ERD](../../docs/images/readme-diagrams/kotlin-flow-extensions-parallel-enrichment-readme-erd-01.png)

Domain objects are in-memory for the example and intentionally tiny:

- `OrderCommand` — incoming order (`orderId`, `customerId`, items)
- `CustomerProfile` — customer grade
- `InventorySnapshot` — stock-per-SKU fulfillment checks
- `EnrichedOrder` — output used by downstream readers

`UnknownCustomerException` and `UnknownProductException` represent hard-fail paths.

## Class model

![Class diagram](../../docs/images/readme-diagrams/kotlin-flow-extensions-parallel-enrichment-readme-class-diagram-01.png)

Core classes:

- `OrderEnrichmentPipeline`
- `CustomerProfileService`
- `InventoryService`
- `PromotionService`
- domain model types and exceptions

## Sequence model

![Sequence](../../docs/images/readme-diagrams/kotlin-flow-extensions-parallel-enrichment-readme-sequence-01.png)

`parallel` enriches each order independently and then `sequential` emits a single unified flow.

## Code example

```kotlin
val services = OrderEnrichmentPipeline(
    customerProfileService = CustomerProfileService(customerGrades),
    inventoryService = InventoryService(stockBySku),
    promotionService = PromotionService()
)

val out = services.enrichInParallel(
    source = orders,
    parallelism = 4,
    runOn = { i -> executors[i] }
).toList()
```

## Build and test

```bash
./gradlew :kotlin-flow-extensions-parallel-enrichment:test
```

## References

- [bluetape4k-coroutines flow extensions (parallel)](../../../../bluetape4k-projects/bluetape4k/coroutines/src/main/kotlin/io/bluetape4k/coroutines/flow/extensions/parallel/ParallelFlowSupport.kt)
