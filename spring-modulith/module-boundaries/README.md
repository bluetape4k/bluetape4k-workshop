# Spring Modulith Module Boundaries

[한국어](README.ko.md) | English

This workshop shows how Spring Modulith turns package structure into an architectural safety net. The example uses four small modules: `catalog`, `ordering`, `payment`, and `notification`.

The central rule is explicit: `ordering` may read only `catalog :: api`; `payment` and `notification` may consume only `ordering :: events`. A test-only invalid fixture proves that a direct import into `ordering.internal` is rejected by `ApplicationModules.verify()`.

![Spring Modulith module boundary architecture](../../docs/images/readme-diagrams/spring-modulith-module-boundaries-readme-architecture-01.png)

SVG source: [spring-modulith-module-boundaries-readme-architecture-01.svg](../../docs/images/readme-diagrams/spring-modulith-module-boundaries-readme-architecture-01.svg)

## What You Learn

| Topic | Code to read | What to check |
|---|---|---|
| Named interfaces | `catalog/api/ModuleMetadata.kt`, `ordering/events/ModuleMetadata.kt` | `@NamedInterface` marks the packages other modules may use. |
| Allowed dependencies | `ordering/ModuleMetadata.kt`, `payment/ModuleMetadata.kt`, `notification/ModuleMetadata.kt` | `@ApplicationModule(allowedDependencies = [...])` makes dependency direction executable. |
| Boundary verification | `ApplicationModuleBoundaryTest.kt` | The valid graph passes and the invalid fixture fails with `Violations`. |
| Event handoff | `OrderingService.kt`, `PaymentEventHandler.kt`, `NotificationEventHandler.kt` | Payment and notification react to `OrderPlacedEvent` instead of calling ordering internals. |
| Refactoring signal | `invalid/payment/PaymentBoundaryLeak.kt` | A direct `ordering.internal` import is a design smell, not an implementation shortcut. |

## Sequence

The sequence diagram separates an allowed catalog API lookup from the event contract consumed by downstream modules.

![Spring Modulith module boundary sequence](../../docs/images/readme-diagrams/spring-modulith-module-boundaries-readme-sequence-01.png)

SVG source: [spring-modulith-module-boundaries-readme-sequence-01.svg](../../docs/images/readme-diagrams/spring-modulith-module-boundaries-readme-sequence-01.svg)

## Run the Example Tests

```bash
./gradlew :spring-modulith-module-boundaries:test --console=plain --max-workers=1
```

The test suite verifies:

- the production module graph passes Spring Modulith verification;
- a test-only `payment -> ordering.internal` dependency fails with `Violations`;
- placing an order publishes `OrderPlacedEvent`;
- payment and notification update their own in-memory state from that event;
- invalid order input does not publish downstream side effects.

## Design Notes

`catalog.api` is intentionally small. It exports read-only item snapshots so `ordering` can validate a request without depending on catalog internals.

`ordering.events` is the event contract. `payment` and `notification` do not call `OrderingService`, do not import `ordering.internal`, and do not share mutable state with ordering.

The invalid fixture mirrors a common refactoring mistake: a downstream module reaches into another module's internal repository to "just check one thing." The boundary test makes that dependency visible immediately and gives learners a concrete repair path: move the needed data into an exported API or event contract.

## Safety Rule

Events are module contracts. Keep them stable, minimal, and reader-safe. Publish identifiers and simple business facts, not private objects, secrets, or mutable aggregate internals.
