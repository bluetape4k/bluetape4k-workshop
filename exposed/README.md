# Exposed Examples

Production-style examples using [JetBrains Exposed](https://github.com/JetBrains/Exposed) ORM with Spring Boot.

## Submodules

| Module | Stack | TX Strategy |
|--------|-------|-------------|
| [mvc-jdbc](./mvc-jdbc/) | Spring MVC + Exposed JDBC | `@Transactional` (Spring declarative) |
| [mvc-virtualthread](./mvc-virtualthread/) | Spring MVC + Virtual Threads + Exposed JDBC | `virtualFuture(executor){ transaction(db){} }` |
| [webflux-r2dbc](./webflux-r2dbc/) | WebFlux + Coroutines + Exposed R2DBC | `suspendTransaction(db=db){ }` |

## Domain

All three modules implement the same Author/Book/Product/Order domain with full CRUD and a
concurrent `placeOrder` use case that demonstrates lock ordering and stock deduction.

```
Author ──< Book
Product
Order ──< OrderLine ──> Product
```

## Key Patterns Demonstrated

- **mvc-jdbc**: Spring declarative `@Transactional`, SELECT FOR UPDATE, rollback verification
- **mvc-virtualthread**: `virtualFuture` VT executor pattern, `ExecutionException` unwrapping, no `@Transactional`
- **webflux-r2dbc**: `Flow<T>` repositories, `suspendTransaction{}`, concurrent order test with coroutines
