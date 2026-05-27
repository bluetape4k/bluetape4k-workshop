# Issue 228: domain-specific bluetape4k module adoption

Issue: #228
Date: 2026-05-27

## Context

Issue #228 audited whether workshop example areas should adopt more
domain-specific `bluetape4k-*` modules. The acceptance criteria required an
adopt/defer/reject decision for each candidate and explicitly prohibited unused
dependencies.

## Decision or Finding

Keep the current dependency graph for this pass. The audited areas already use
the matching bluetape4k modules where the examples exercise those domains, and
the remaining candidates would be unused or would require a behavior-changing
rewrite.

Do not add a bluetape4k module only to satisfy a pattern scan. Add it when the
example source imports an API from that module or when a focused follow-up
changes code to use that module's contract.

## Candidate Matrix

| Area | Decision | Source-backed rationale |
| --- | --- | --- |
| `json/*` | Reject standalone adoption | `bluetape4k-json` is an interface-level module that arrives transitively through concrete JSON integrations. Workshop JSON examples should continue to use concrete integrations such as `bluetape4k-jackson3` unless an interface-level JSON example is added. |
| `messaging/transactional-outbox` | Defer `bluetape4k-kafka4` | The module currently depends on Kafka clients and Spring Kafka directly in `messaging/transactional-outbox/build.gradle.kts`. `OutboxPublisher.publishEvent` intentionally blocks on `kafkaTemplate.send(...).get()` before marking a row published, preserving the transactional outbox success contract. `bluetape4k-kafka4` provides coroutine and Spring `suspendSend` helpers, but adopting those would require a suspend/reactive publisher design instead of a dependency-only edit. |
| `observability/micrometer-observation` | Adopted already | `observability/micrometer-observation/build.gradle.kts` already uses `libs.bluetape4k.micrometer`; the tests now use bluetape4k assertions from the root default test dependency. No additional `bluetape4k-coroutines` dependency is needed because this module does not model coroutine execution. |
| `redis/cluster-demo` | Adopted already | `redis/cluster-demo/build.gradle.kts` already uses `bluetape4k-lettuce`, `bluetape4k-testcontainers`, `bluetape4k-coroutines`, and `bluetape4k-idgenerators`. Redisson is commented out because the cluster demo uses Spring Data Redis with Lettuce; adding `bluetape4k-redisson` would be unused. |
| `vertx/*` | Adopted already | `vertx/coroutines`, `vertx/vertx-sqlclient`, and `vertx/vertx-webclient` already depend on `bluetape4k-vertx`; the coroutine examples also use `bluetape4k-coroutines` and concrete Vert.x dependencies. |
| `spring-data/r2dbc-*` | Adopted already | `spring-data/r2dbc-examples`, `spring-data/r2dbc-coroutines`, and `spring-data/r2dbc-webflux` already use `bluetape4k-r2dbc`; the Exposed WebFlux R2DBC module uses the Exposed R2DBC helper modules instead, matching its domain boundary. |
| `spring-data/jpa-querydsl` | Adopted already | `spring-data/jpa-querydsl/build.gradle.kts` already uses `bluetape4k-hibernate` because the example domain is JPA/Hibernate based. |
| `spring-data/mongodb-*` | Defer `bluetape4k-mongodb` | The MongoDB examples currently use Spring Data MongoDB, Kotlin MongoDB drivers, Testcontainers, and coroutine/reactor helpers directly. There is no existing import from `bluetape4k-mongodb` in the example source, and no local source evidence that a dependency-only addition would be used. A follow-up should first identify a concrete `bluetape4k-mongodb` API to demonstrate. |

## Outcome

No Gradle dependency was added. The issue result is a source-backed adoption
matrix that prevents dependency churn while leaving clear follow-up gates for
Kafka and MongoDB.

## Verification

- Inspected candidate build files and source imports for `messaging`,
  `observability`, `redis`, `vertx`, and `spring-data`.
- Inspected local `bluetape4k-kafka4` sources and confirmed the available
  coroutine/Spring suspend-send helpers.

## Future Guidance

When a future issue revisits a deferred area:

1. Start from source usage, not the version catalog.
2. Add a bluetape4k dependency only with an accompanying source import or
   behavior-preserving migration.
3. Keep using the root `bluetape4k-dependencies` BOM; do not pin individual
   bluetape4k module versions.
4. For `messaging/transactional-outbox`, preserve the guarantee that an outbox
   row is marked `PUBLISHED` only after Kafka send success is known.
5. For MongoDB, first verify the current `bluetape4k-mongodb` API and choose a
   focused example before changing Gradle dependencies.
