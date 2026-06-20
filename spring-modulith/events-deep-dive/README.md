# Spring Modulith Events Deep Dive

[한국어](README.ko.md) | English

This module is a focused Spring Modulith event workshop. It starts with plain
Spring application events, moves through aggregate events and transactional
listeners, and finishes with a before/after comparison of direct module calls
versus event-based module boundaries.

## Architecture

![Spring Modulith events architecture](../../docs/images/readme-diagrams/spring-modulith-events-deep-dive-readme-architecture-01.png)

Each package is intentionally small so tests can show one event concern at a
time.

| Package | What it demonstrates |
|---|---|
| `a/fundamentals/quickstart` | `ApplicationEventPublisher`, `ApplicationListener`, and `@EventListener`. |
| `a/fundamentals/springdata` | `StringAggregate.registerEvent(...)` published when a Spring Data repository saves the aggregate. |
| `b/transactions` | `@Transactional` order completion, plain `@EventListener`, and `@TransactionalEventListener` behavior. |
| `c/architecture/before` | Order service directly depends on inventory and calls it inside the completion flow. |
| `d/architecture/after` | Order publishes `OrderCompleted`; inventory reacts through an event listener. |

## Event Flow

![Spring Modulith events sequence](../../docs/images/readme-diagrams/spring-modulith-events-deep-dive-readme-sequence-01.png)

The after architecture keeps the order module free from an inventory dependency.
Module tests verify that boundary, while integration tests verify the event
delivery behavior.

## Test Map

| Test | Purpose |
|---|---|
| `OrderEventPublicationTests` | Verifies publication from quickstart, Spring Data aggregate, and transaction examples. |
| `OrderManagementTest` | Shows the direct before-architecture dependency and failure coupling. |
| `OrderModuleTest` | Verifies Modulith module structure. |
| `OrderIntegrationTest` | Verifies event-driven inventory handling in the after architecture. |

## Build and Test

```bash
./gradlew :spring-modulith:events-deep-dive:test
```
