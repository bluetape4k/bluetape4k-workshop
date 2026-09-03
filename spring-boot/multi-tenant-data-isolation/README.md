# Multi-Tenant Data Isolation

[한국어](README.ko.md) | English

This module demonstrates tenant-safe reads, writes, cache keys, lock keys,
rate-limit buckets, Micrometer tags, and the `2.0.0` tenant context carriers.
It deliberately keeps runtime storage simple with H2 and in-memory helpers so
the isolation contract is easy to inspect in tests.

## Architecture Diagram

![Multi-Tenant Data Isolation architecture diagram](../../docs/images/readme-diagrams/spring-boot-multi-tenant-data-isolation-readme-architecture-01.png)

## Isolation Scenario

![Tenant Data Isolation scenario](../../docs/images/readme-diagrams/spring-boot-multi-tenant-data-isolation-readme-scenario-01.png)

Advanced Spring Boot workshop for tenant-safe data access, cache keys, lock keys, rate-limit buckets, and metrics tags.

## Overview

Tenant isolation fails when a repository query, cache key, lock key, or rate-limit bucket uses only a shared resource ID. This module keeps the infrastructure lightweight with H2, in-memory locks, and fixed-window rate-limit buckets, then proves the isolation boundary with executable tests.

## Main Components

| Class | Role |
|---|---|
| `TenantId` | Normalized tenant value used by all isolation boundaries |
| `InvoiceTable` | Shared table with mandatory `tenant_id` column |
| `TenantInvoiceRepository` | Safe `LongJdbcRepository` implementation with tenant predicates |
| `UnsafeInvoiceRepository` | Baseline path that omits tenant predicates |
| `TenantKeyFactory` | Tenant-prefixed cache, lock, and rate-limit keys |
| `TenantInvoiceService` | Composes repository, cache, lock, rate-limit, and metrics behavior |
| `TenantContextCarrierService` | Connects ThreadLocal, ScopedValue, and Reactor context to the safe service |

## Used Bluetape4k Features

| Feature | Module/artifact | Code reference | Benefit |
|---|---|---|---|
| Exposed JDBC repository helper | `bluetape4k-exposed-jdbc` | `TenantInvoiceRepository : LongJdbcRepository` | Reuses Bluetape4k repository defaults while keeping tenant predicates explicit |
| Spring Boot support | `bluetape4k-spring-boot4-core` | `build.gradle.kts` | Aligns with Spring Boot 4 workshop modules |
| Logging | `bluetape4k-logging` | `KLogging` companions | Uses the common Bluetape4k logging convention |
| Metrics bridge | `bluetape4k-micrometer` | `TenantMetrics` | Keeps bounded tenant-fingerprint Micrometer counters in the Bluetape4k dependency set |
| Tenant context | `bluetape4k-tenant` | `TenantContextCarrierService` | Supplies a lexical ThreadLocal or ScopedValue tenant at blocking and virtual-thread boundaries |
| Reactor tenant context | `bluetape4k-tenant-reactor` | `TenantContextCarrierService` | Keeps tenant state immutable and subscription-local across scheduler hops |
| JUnit support and assertions | `bluetape4k-junit5`, `bluetape4k-assertions` | `TenantIsolationTest` | Uses repository-standard test lifecycle and matchers |

## Before / After

### Repository

```kotlin
// Before: ID-only lookup can leak rows across tenants.
InvoiceTable.selectAll()
    .where { InvoiceTable.id eq invoiceId }
    .firstOrNull()

// After: tenant and ID must both match.
InvoiceTable.selectAll()
    .where {
        (InvoiceTable.tenantId eq tenantId.value) and (InvoiceTable.id eq invoiceId)
    }
    .firstOrNull()
```

### Cache, Lock, and Rate Limit Keys

```kotlin
// Before
invoice:42

// After
tenant:tenant-alpha:invoice:42
tenant:tenant-alpha:lock:invoice:42
tenant:tenant-alpha:rate-limit:reader
```

## TenantContext carriers (2.0.0)

`TenantContextCarrierService` keeps the existing explicit `TenantId` repository
predicates and adds a safe source for that value at each execution boundary.
The module uses only the root `platform(libs.bluetape4k.dependencies)` BOM;
the `bluetape4k-tenant` and `bluetape4k-tenant-reactor` aliases are versionless.

### Spring MVC / blocking request

```kotlin
carrier.withMvcTenant(tenantId) {
    carrier.findInvoiceWithMvcTenant(invoiceId)
}
```

`ThreadLocalTenantContext` restores the previous nested value and removes the
binding after normal completion or an exception. A missing binding propagates
`MissingTenantContextException`; it is never replaced with a default tenant.

### Virtual thread

```kotlin
carrier.withVirtualThreadTenant(tenantId) {
    carrier.findInvoiceWithVirtualThreadTenant(invoiceId)
}
```

`ScopedValueTenantContext` is lexical and safe to use inside a JDK 25 virtual
thread. The next task starts unbound, so a pooled executor cannot inherit a
stale tenant accidentally.

### Reactor

```kotlin
carrier.findInvoiceWithReactorTenant(tenantId, invoiceId)
    .publishOn(Schedulers.parallel())
```

The `withReactorTenant` helper stores the value with `ReactorTenantContext.withTenant`. Reactor's
immutable `ContextView` survives the scheduler hop and keeps concurrent
subscriptions isolated. Cancellation ends only that subscription scope.
If a downstream operator also needs to read the tenant, compose that operator
inside the publisher passed to `withReactorTenant`; `contextWrite` scopes upstream operators.

Operational metrics use an eight-byte SHA-256 `tenant_fingerprint` tag instead
of the tenant value itself. The fingerprint is stable for aggregation but does
not expose the tenant string in logs or metrics.

## Run

```bash
./gradlew :spring-boot-multi-tenant-data-isolation:test
```

## What the Tests Prove

- Baseline ID-only repository and cache paths leak tenant data.
- Tenant-scoped reads return `null` for another tenant's invoice ID.
- Tenant-scoped writes do not update another tenant's invoice.
- Cache keys, lock keys, and rate-limit buckets use tenant scope.
- ThreadLocal and ScopedValue nesting restores the previous binding and cleans up after failure.
- Reactor context survives a scheduler hop, isolates concurrent subscriptions, and is safe on cancellation.
- Metrics expose only a bounded `tenant_fingerprint`, never the raw tenant value.

## Gap Notes

This module demonstrates lock and rate-limit isolation with in-memory state.
Real Redis/Redisson locks, Bucket4j backends, distributed transactions,
tenant-specific authentication, and schema changes are deliberately out of
scope. The carrier example focuses on request-boundary propagation and cleanup.
