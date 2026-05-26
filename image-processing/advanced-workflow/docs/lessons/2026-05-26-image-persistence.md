# 2026-05-26 Image Persistence Layer — Lessons Learned

## Context

**Branch**: `feat/issue-94-image-persistence`
**Module**: `image-processing/advanced-workflow`
**Issue**: Add full image persistence layer (DB schema + repository + service + controller GET endpoints)

---

## Lesson 1 — ScopedValue binary incompatibility between Java 21 preview and Java 25

### Problem

`NoSuchMethodError: 'java.lang.Object java.lang.ScopedValue$Carrier.call(java.util.concurrent.Callable)'`
at runtime when `ImagePersistenceServiceImpl` called `UserContext.withUser(AUDIT_USER)`.

### Root cause

`ScopedValue.Carrier` changed from a **class** in Java 21 preview (`invokevirtual` bytecode)
to a **sealed interface** in Java 25 finalized (`invokeinterface` bytecode).
The `bluetape4k-exposed-core` library was compiled with Java 21 preview, so its
`UserContext.withUser` bytecode emits `invokevirtual` — which fails at runtime on Java 25.

### Fix

Use `UserContext.withThreadLocalUser(user)` instead of `UserContext.withUser(user)` when the
service is invoked from coroutine dispatchers (`Dispatchers.IO`, etc.).
`withThreadLocalUser` is backed by `InheritableThreadLocal`, which is compatible with any JVM.

### Rule to apply

> When a service runs on `Dispatchers.IO` (virtual or platform threads) AND the library was
> compiled with Java 21 preview, prefer `withThreadLocalUser` over `withUser` until the library
> is recompiled with Java 25.

---

## Lesson 2 — Exposed queries must run inside a transaction (including reads)

### Problem

`IllegalStateException: No transaction in context` at `findAssetByExternalId` and `findAssetHistory`.

### Root cause

All JetBrains Exposed SQL operations — including read-only `selectAll()` / `where {}` queries —
require an active transaction. The two query methods were not wrapped in any transaction.

### Fix

Added a `readTxTemplate: TransactionTemplate` with `isReadOnly = true` and
`PROPAGATION_REQUIRED` (joins existing TX or creates a new one).
Wrapped both query methods with `readTxTemplate.execute { ... }`.

### Rule to apply

> Every Exposed DB operation (read or write) must execute inside a `TransactionTemplate` or a
> `@Transactional` boundary. Never call repository methods bare from a service method.

---

## Lesson 3 — `runTest` + `withTimeout` + `Dispatchers.IO` = spurious TimeoutCancellationException

### Problem

`TimeoutCancellationException: Timed out after 5s of _virtual_ (kotlinx.coroutines.test) time`
appeared in `ImageDerivativeWorkflowServiceTest` when persistence calls were wrapped in
`withContext(Dispatchers.IO)`.

### Root cause

`runTest` (and `runTest(UnconfinedTestDispatcher())`) use a `TestCoroutineScheduler` that manages
**virtual time**. When a coroutine suspends into `Dispatchers.IO`, the scheduler considers itself
idle and auto-advances virtual time to the next scheduled event — which is the `withTimeout(5s)`
boundary. The timeout fires before IO work returns, even if the actual elapsed wall-clock time
is milliseconds.

Switching `processingTimeout` to `Duration.ofHours(1)` also fails — 1h virtual time still
advances instantly.

### Fix

Replace `runTest` (or `runTest(UnconfinedTestDispatcher())`) with `runSuspendIO` from
`io.bluetape4k.junit5.coroutines.CoroutineSupport`. `runSuspendIO` uses **real wall-clock time**,
so `Dispatchers.IO` suspensions are transparent and `withTimeout(5s)` measures actual elapsed time.

### Rule to apply

> When the service under test uses `withContext(Dispatchers.IO)` or `withTimeout(real duration)`,
> use `runSuspendIO` instead of `runTest`. Reserve `runTest` for pure suspend logic with virtual
> time control (e.g., delay-based tests, StateFlow, Flow timing).

---

## Lesson 4 — Spring Boot 4 moved `@WebMvcTest` to a new package

### Problem

`Unresolved reference 'web'` when importing
`org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest`.

### Root cause

Spring Boot 4.x reorganized the test autoconfigure packages. `@WebMvcTest` is now at:

```
org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
```

The artifact that provides it is `spring-boot-webmvc-test` (pulled in by the
`spring-boot-starter-webmvc-test` starter).

### Fix

Update the import to:
```kotlin
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
```

### Rule to apply

> When migrating to Spring Boot 4, audit all `org.springframework.boot.test.autoconfigure.*`
> imports. Many slices have moved to technology-specific packages such as:
> - `org.springframework.boot.webmvc.test.autoconfigure.*`
> - `org.springframework.boot.webflux.test.autoconfigure.*`
> - `org.springframework.boot.data.jpa.test.autoconfigure.*`

---

## Lesson 5 — Coroutine-backed MVC suspend handlers need async-dispatch in MockMvc

### Problem

Testing `suspend fun` controller methods with `mockMvc.perform(get(...)).andExpect(status().isOk)`
does not work — the result is not yet resolved synchronously.

### Root cause

Spring MVC + Kotlin coroutines executes suspend functions asynchronously via the servlet async
mechanism. `MockMvc.perform()` starts the request but the response body is produced in a
background thread/coroutine. `status().isOk` on the raw `MvcResult` checks the interim 202 async
state, not the final response.

### Fix

Use the two-step async dispatch pattern:
```kotlin
val asyncResult = mockMvc.perform(get("/api/images/{imageId}", imageId))
    .andExpect(request().asyncStarted())
    .andReturn()

mockMvc.perform(asyncDispatch(asyncResult))   // import: MockMvcRequestBuilders.asyncDispatch
    .andExpect(status().isOk)
    .andExpect(jsonPath("$.imageId").value(imageId))
```

### Rule to apply

> All `@RestController` methods that are `suspend fun` require the async-dispatch pattern in
> `MockMvc` tests. Non-suspend controller methods can use the single-step pattern.

---

## Summary

| # | Lesson | Applies when |
|---|--------|--------------|
| 1 | Use `withThreadLocalUser` instead of `withUser` | Service on coroutine dispatcher + library compiled with Java 21 preview |
| 2 | Wrap ALL Exposed queries in a transaction | Any Exposed repository call from a service |
| 3 | Use `runSuspendIO` instead of `runTest` | Service uses `Dispatchers.IO` or real-time `withTimeout` |
| 4 | `@WebMvcTest` package changed in Spring Boot 4 | Migrating to Spring Boot 4 |
| 5 | MockMvc async-dispatch pattern for suspend controllers | Testing `suspend fun` MVC handlers |
