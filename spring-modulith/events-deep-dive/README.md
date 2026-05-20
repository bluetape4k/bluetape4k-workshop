# Spring Modulith Events Deep Dive

[English](README.md) | [한국어](README.ko.md)

Spring Modulith event publication examples that move from basic application events to transactional publication and module-boundary verification.

## Architecture

![Spring Modulith events architecture](../../docs/images/readme-diagrams/spring-modulith-events-deep-dive-diagram-01.png)

## What This Module Shows

- Quickstart publication with `ApplicationEventPublisher` and `OrderCompleted`.
- Spring Data repository-based completion flow.
- Transactional publication behavior around order completion.
- A before/after architecture comparison for direct inventory calls versus module events.
- Modulith tests that verify module structure and integration behavior.

## Running

```bash
./gradlew :spring-modulith-events-deep-dive:test
```

## Source Map

- `a/fundamentals/quickstart` publishes events directly from `OrderManagement`.
- `a/fundamentals/springdata` persists completed orders through Spring Data.
- `b/transactions` demonstrates transactional event publication.
- `c/architecture/before` couples order completion directly to inventory updates.
- `d/architecture/after` separates order and inventory behavior through a module boundary.
- `src/test/kotlin/.../events` contains the Modulith verification and integration tests.
