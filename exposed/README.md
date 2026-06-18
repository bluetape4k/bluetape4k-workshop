# Exposed Examples

[한국어](README.ko.md) | English

## Module Guide

The **Exposed Examples** group compares the data-access choices used in this
workshop: plain Spring MVC with Exposed JDBC, the same JDBC model on Java
virtual threads, WebFlux with Exposed R2DBC, and a small JaVers audit slice.
Use this README to choose the module that matches the HTTP and transaction model
you want to study first.

## Architecture

![Exposed Examples module architecture](../docs/images/readme-diagrams/exposed-readme-architecture-01.png)

The modules intentionally keep a similar domain shape so the transaction style
is easy to compare. The JDBC modules demonstrate row locking and stock
deduction in a blocking database transaction. The WebFlux module keeps the same
use case inside `suspendTransaction`, while `javers-audit` focuses on object
history and diffing around an Exposed table.

## Modules

| Module | Stack | Transaction boundary | What to inspect |
|---|---|---|---|
| [javers-audit](./javers-audit/) | JaVers + Exposed JDBC + H2 | `javers.commit(...)` plus `transaction { ... }` | Product audit history, latest snapshot, diff, and shallow delete |
| [mvc-jdbc](./mvc-jdbc/) | Spring MVC + Exposed JDBC + PostgreSQL | Spring `@Transactional` | Blocking CRUD, `SELECT FOR UPDATE`, rollback behavior |
| [mvc-virtualthread](./mvc-virtualthread/) | Spring MVC + virtual threads + Exposed JDBC + PostgreSQL | `virtualFuture(executor) { transaction(db) { ... } }` | Running blocking Exposed work on virtual threads without `@Transactional` |
| [webflux-r2dbc](./webflux-r2dbc/) | WebFlux + coroutines + Exposed R2DBC + PostgreSQL | `suspendTransaction(db = db) { ... }` | Suspend services, `Flow` repositories, and non-blocking order placement |

## Domain

The MVC and WebFlux modules implement the same Author/Book/Product/Order domain
with CRUD endpoints and a concurrent `placeOrder` use case that demonstrates
product-id lock ordering and stock deduction. The audit module narrows the
domain to Product so the JaVers snapshot contract stays obvious.

![Exposed Examples domain ERD](../docs/images/readme-diagrams/exposed-readme-erd-01.png)

```
Author ──< Book
Product
Order ──< OrderLine ──> Product
```

## Key Patterns Demonstrated

- **Audit before persistence**: `javers-audit` records Product changes and then upserts or deletes the Exposed row.
- **Spring-managed JDBC transactions**: `mvc-jdbc` keeps the unit of work at the service layer with `@Transactional`.
- **Virtual-thread JDBC isolation**: `mvc-virtualthread` runs Exposed JDBC work inside `virtualFuture` tasks and explicit `transaction(db)` blocks.
- **Coroutine R2DBC transactions**: `webflux-r2dbc` wraps suspend services in `suspendTransaction` and exposes repository results as `Flow`.
