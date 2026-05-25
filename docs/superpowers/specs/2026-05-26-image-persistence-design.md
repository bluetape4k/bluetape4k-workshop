# Image Processing Persistence Layer — Design Spec
**Date**: 2026-05-26  
**Issue**: #94 — Image Processing Advanced Persistence: PostgreSQL + Exposed workflow metadata/history 저장  
**Module**: `image-processing/advanced-workflow`  
**Builds on**: Issue #93 (upload → VIPS → S3 → public URLs workflow)

---

## 1. Goal

Extend the existing `advanced-workflow` Spring Boot 4 / Java 25 module to persist:
- Image asset metadata (`image_assets`)
- S3 derivative object records (`image_objects`)
- Processing job lifecycle (`image_processing_jobs`)
- Step-level event log (`image_processing_events`)

Expose two new read endpoints and keep the existing upload workflow backward-compatible.

---

## 2. Design Risks

1. **Long-held DB connection during VIPS processing** — Single-transaction-wrap would hold a HikariCP connection for up to `processingTimeout` (default 60s), starving the pool. Mitigated by saga pattern (3 short transactions).
2. **Failure record lost on rollback** — If failure is recorded inside the same transaction that failed, both records are rolled back. Mitigated by `REQUIRES_NEW` propagation for T3, wrapped in `NonCancellable`.
3. **Duplicate S3 objects on retry** — Re-uploading the same variants creates duplicate S3 object records. Mitigated by composite unique constraint on `(image_asset_id, kind, variant_name)` with `NULLS NOT DISTINCT` + `batchUpsert`.
4. **`forkEvery=1` test isolation** — Each Gradle test fork spawns a new JVM and reconnects to the container. Spring context restarts per fork. Mitigated by `PostgreSQLServer.Launcher.postgres` Testcontainers reuse pattern.
5. **`coroutines + @Transactional` incompatibility** — Spring's `@Transactional` proxy does not work on `suspend` functions in Spring MVC. Mitigated by blocking persistence service methods bridged via `withContext(Dispatchers.IO)`.
6. **FAILED asset blocks retry** — If a first attempt fails after T1 commits and leaves `status=FAILED`, a retry would hit the `checksum UNIQUE` constraint and crash with a DB exception. Mitigated by explicit retry recovery path in T1 (see §3.3).
7. **Concurrent same-checksum uploads cause `DataIntegrityViolationException`** — Two simultaneous uploads of identical bytes both pass the READY short-circuit check and race to insert. The loser gets an uncaught DB exception. Mitigated by catching `DataIntegrityViolationException` in T1 and re-reading the asset row.
8. **NULL idempotency hole in `image_objects`** — PostgreSQL standard `UNIQUE` treats two NULL values as distinct, so two ORIGINAL rows with `variant_name=NULL` satisfy the constraint and both insert. Mitigated by `NULLS NOT DISTINCT` clause (supported from PostgreSQL 15; confirmed with `PostgreSQLServer.Launcher` using `postgres:18-alpine`) on the UNIQUE constraint.
9. **Job orphan (crash between T1 and T2)** — T1 commits but the process dies before VIPS or T2. Job stays `RUNNING` permanently. Mitigated by stale-job monitoring query (see §3.6).

---

## 3. Architecture Decision

### 3.1 Transaction Model: Saga (4 Short Transactions + Event Mini-Tx)

All saga transactions are `REQUIRES_NEW` — no implicit enlistment in a possible future outer transaction.

| Step | Transaction | Propagation | What is written |
|------|-------------|-------------|-----------------|
| T1 (before VIPS) | `REQUIRES_NEW` | Unconditional | `image_assets` (status=PROCESSING) + `image_processing_jobs` (status=RUNNING) |
| T2 (after S3 success) | `REQUIRES_NEW` | Unconditional | Upsert `image_objects`, update asset→READY, update job→SUCCEEDED |
| T3 (on any failure) | `REQUIRES_NEW` + `NonCancellable` | Catch block | Update job→FAILED + asset→FAILED |
| Event mini-tx (any step) | `REQUIRES_NEW` | Per event append | Single `image_processing_events` row; survives T2 or T3 rollback |

**T3 exception handling contract** (MANDATORY in implementation):
```kotlin
// In ImageDerivativeWorkflowService catch block
withContext(NonCancellable + Dispatchers.IO) {
    try {
        persistence.recordJobFailure(jobId, assetId, errorCode, sanitizedMessage)
    } catch (t3Ex: Exception) {
        // T3 failure must NOT swallow the original exception
        originalException.addSuppressed(t3Ex)
        log.error(t3Ex) { "Failed to record job failure: assetId=$assetId jobId=$jobId" }
        meterRegistry.counter(METRIC_FAILURE_RECORD_FAILED).increment()
    }
}
// Always rethrow the original exception after T3 completes or fails
throw originalException
```

`REQUIRES_NEW` for T2 (previously `REQUIRED`) ensures saga independence if a coordinator transaction is ever introduced.

### 3.2 JDBC Pattern (not R2DBC)

Use blocking `@Transactional` methods on `ImagePersistenceServiceImpl`, bridged from suspend callers via `withContext(Dispatchers.IO)`. R2DBC is excluded because it requires a separate JDBC-based DDL initializer, uses a different transaction model, and conflicts with the existing coroutine + VIPS + S3 design.

`CancellationException` handling in service layer:
```kotlin
// Do NOT use runCatching {} around withContext(Dispatchers.IO) { ... } — it swallows cancellation.
// Use try/catch with explicit CancellationException rethrow:
try {
    withContext(Dispatchers.IO) { persistence.recordJobStart(...) }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    // handle non-cancellation errors
}
```

### 3.3 Idempotency Strategy

**Two-level idempotency + retry recovery:**

1. **Asset-level (checksum)**:
   - `image_assets.checksum` has a UNIQUE constraint (SHA-256 of raw bytes).
   - T1 `recordJobStart` logic:
     - Query `image_assets` by checksum.
     - If `status=READY`: short-circuit → return existing `JobStartResult(alreadyExists=true)`.
     - If `status=PROCESSING`: a concurrent or stale run exists → log + create new job on same asset.
     - If `status=FAILED`: **retry recovery** — update status→PROCESSING, create new job row.
     - If no row: insert new asset.
   - If `DataIntegrityViolationException` on insert (lost insert race): catch **OUTSIDE the `@Transactional` method** (the transaction has already rolled back before the exception propagates to the caller), re-read by checksum, branch on status.

   > **FAILED-retry concurrent race**: If two retries on the same FAILED asset run concurrently, both read `status=FAILED`, both update→PROCESSING, and both insert a new job row, resulting in two `RUNNING` jobs on the same asset. This is an accepted race condition for the workshop scope. In production, mitigate with a `SELECT … FOR UPDATE` lock on the `image_assets` row before updating status. The duplicate-job scenario is detected by the stale-job monitoring query (§3.6) and resolved via manual remediation or an idempotency window.

2. **Object-level (composite unique)**:
   - `image_objects` has `UNIQUE NULLS NOT DISTINCT (image_asset_id, kind, variant_name)`.
   - `NULLS NOT DISTINCT` ensures ORIGINAL rows (where `variant_name IS NULL`) are treated as equal.
   - `recordJobSuccess` uses `batchUpsert` with `(image_asset_id, kind, variant_name)` as key columns.
   - On retry: updates existing rows instead of creating duplicates.

### 3.7 Event Lifecycle — Step Enum and Writer

`ImageProcessingEventRepository.appendEvent()` (called with `REQUIRES_NEW`) writes one row per workflow step. The caller is always `ImageDerivativeWorkflowService` (not repositories or mappers).

**Step enum values** (`image_processing_events.step` column, `ImageProcessingStep` enum class):

| Step value | When emitted | Status | Payload |
|---|---|---|---|
| `VALIDATION` | After checksum computed, before T1 | `STARTED` or `SKIPPED` | `{checksum, byteSize}` |
| `JOB_STARTED` | After T1 commits | `COMPLETED` | `{assetId, jobId, assetStatus}` |
| `VIPS_PROCESSING` | After VIPS returns (derivative list computed) | `COMPLETED` | `{variantCount, durationMs}` |
| `S3_UPLOAD` | After each S3 `putObject` returns | `COMPLETED` or `FAILED` | `{s3Key, variantName, byteSize}` |
| `JOB_COMPLETED` | After T2 commits | `COMPLETED` | `{assetId, jobId, objectCount}` |
| `JOB_FAILED` | After T3 commits (in catch block) | `FAILED` | `{errorCode, errorMessage}` |

**Event write rules**:
- Each step event is written in its own `REQUIRES_NEW` transaction (mini-tx from §3.1).
- Event write failure (mini-tx throws) is logged and suppressed; it must not prevent the main flow from continuing or the original exception from propagating.
- `payload_json` uses `jacksonb<Map<String, Any?>>()` Exposed column type.
- `message` is a free-text human-readable summary (≤255 chars, no stack traces).
- `VALIDATION` event is emitted as `SKIPPED` when `status=READY` short-circuit fires (no new job created).

### 3.4 NoopImagePersistenceService (test fake — test source only)

`NoopImagePersistenceService` lives in `src/test/kotlin/` — **NOT in `src/main/kotlin/`**. A no-op fake in production source is a Spring bean scanner hazard. The fake must:
- Return a valid `JobStartResult(assetId=0L, jobId=0L, alreadyExists=false)` from `recordJobStart()`.
- All other methods are no-ops (no state stored).

Existing tests inject it by name; existing mocked-S3 / mocked-VIPS tests are unaffected.

### 3.5 Error Message Sanitization

`error_message` stored in `image_processing_jobs` **must not contain**:
- Java/Kotlin exception stack traces
- VIPS native FFM error strings with filesystem paths
- Internal JDBC connection strings or S3 bucket ARNs

Implementation rule: extract a safe user message (e.g., `"VIPS processing failed"`, `"S3 upload failed"`, `"DB error during persistence"`) for storage; log the full exception via `log.error(e) { "..." }` for observability.

### 3.6 Stale-Job Monitoring

T1-crash orphans leave `status=RUNNING` indefinitely. The module does not implement an automatic reaper (out of scope for workshop), but must document the monitoring query:

```sql
SELECT * FROM image_processing_jobs
WHERE status = 'RUNNING'
  AND started_at < NOW() - INTERVAL '5 minutes';
```

This query is documented in `README.md` under an "Operations" section.

---

## 4. Data Model

### image_assets
```sql
id                BIGSERIAL PRIMARY KEY
external_id       VARCHAR(36) NOT NULL UNIQUE  -- UUID v4 (UUID.randomUUID()), public identifier
original_filename VARCHAR(255)
content_type      VARCHAR(100)
byte_size         BIGINT
width             INT
height            INT
checksum          VARCHAR(64) NOT NULL UNIQUE  -- SHA-256 of raw bytes
status            VARCHAR(20) NOT NULL DEFAULT 'PROCESSING'  -- PROCESSING | READY | FAILED
created_by        VARCHAR(255)                 -- "image-processing-service" (fixed identity, no auth context)
created_at        TIMESTAMP NOT NULL
updated_by        VARCHAR(255)
updated_at        TIMESTAMP
```

### image_objects
```sql
id             BIGSERIAL PRIMARY KEY
image_asset_id BIGINT NOT NULL REFERENCES image_assets(id) ON DELETE CASCADE
kind           VARCHAR(20) NOT NULL   -- ORIGINAL | VARIANT
variant_name   VARCHAR(100)           -- null for ORIGINAL
s3_key         VARCHAR(512) NOT NULL
public_url     TEXT NOT NULL
width          INT
height         INT
byte_size      BIGINT
format         VARCHAR(20)
created_by     VARCHAR(255)
created_at     TIMESTAMP NOT NULL
updated_by     VARCHAR(255)
updated_at     TIMESTAMP
UNIQUE NULLS NOT DISTINCT (image_asset_id, kind, variant_name)  -- PostgreSQL 15+ required; idempotency key
INDEX ON (image_asset_id)  -- FK lookup optimization
```

### image_processing_jobs
```sql
id                 BIGSERIAL PRIMARY KEY
image_asset_id     BIGINT NOT NULL REFERENCES image_assets(id) ON DELETE CASCADE
status             VARCHAR(20) NOT NULL  -- RUNNING | SUCCEEDED | FAILED
requested_variants JSONB                 -- jacksonb<List<String>>
started_at         TIMESTAMP NOT NULL
finished_at        TIMESTAMP
duration_ms        BIGINT
error_code         VARCHAR(100)          -- sanitized, e.g. "VIPS_FAILED", "S3_UPLOAD_FAILED"
error_message      TEXT                  -- sanitized, no stack traces or internal paths
INDEX ON (image_asset_id)
```

### image_processing_events
```sql
id           BIGSERIAL PRIMARY KEY
job_id       BIGINT NOT NULL REFERENCES image_processing_jobs(id) ON DELETE CASCADE
step         VARCHAR(100) NOT NULL
status       VARCHAR(20) NOT NULL
message      TEXT
payload_json JSONB                   -- jacksonb<Map<String, Any?>>
created_at   TIMESTAMP NOT NULL
INDEX ON (job_id)
```

**FK Cascade**: `ON DELETE CASCADE` on all child tables (image_objects, image_processing_jobs, image_processing_events). Enables clean test teardown via parent row deletion.

---

## 5. Component Design

### New Files

```
src/main/kotlin/.../advanced/
  model/
    ImagePersistenceModels.kt           -- DTOs: ImageAssetDTO, ImageObjectDTO,
                                           ImageProcessingJobDTO, ImageProcessingEventDTO,
                                           ImageAssetDetailResponse, ImageAssetHistoryResponse,
                                           ImageJobWithEventsDTO, JobStartResult
  persistence/
    config/
      ImagePersistenceDatabaseInitializer.kt  -- ApplicationRunner + SchemaUtils.create() (NO catch)
    mapper/
      ImagePersistenceMappers.kt              -- ResultRow extension functions
    repository/
      ImageAssetRepository.kt                 -- LongAuditableJdbcRepository
      ImageObjectRepository.kt               -- LongAuditableJdbcRepository, batchUpsert
      ImageProcessingJobRepository.kt         -- LongJdbcRepository, explicit lifecycle cols
      ImageProcessingEventRepository.kt       -- LongJdbcRepository, append-only, REQUIRES_NEW
    schema/
      ImageAssetStatus.kt                     -- enum PROCESSING | READY | FAILED
      ImageJobStatus.kt                       -- enum RUNNING | SUCCEEDED | FAILED
      ImageAssetTable.kt                      -- AuditableLongIdTable + index declarations
      ImageObjectTable.kt                     -- AuditableLongIdTable + NULLS NOT DISTINCT unique
      ImageProcessingJobTable.kt              -- Table (plain, explicit timestamps)
      ImageProcessingEventTable.kt            -- Table (plain, append-only, index)
    ImagePersistenceService.kt                -- interface
    ImagePersistenceServiceImpl.kt            -- @Service, @Transactional(REQUIRES_NEW) methods

src/test/kotlin/.../advanced/           ← NoopImagePersistenceService HERE (not main)
  persistence/
    AbstractImagePersistenceTest.kt           -- @SpringBootTest + PostgreSQLServer.Launcher
    ImagePersistenceServiceImplTest.kt        -- all saga/idempotency/recovery integration tests
    NoopImagePersistenceService.kt            -- test fake (src/test only)
  web/
    ImageAssetEndpointTest.kt                 -- GET /images/{id} and /history integration tests
```

### `ImagePersistenceService` Interface Signatures

```kotlin
interface ImagePersistenceService {
    /**
     * T1: Record job start (REQUIRES_NEW).
     * Creates or recovers image_assets row + new image_processing_jobs row.
     */
    fun recordJobStart(
        checksum: String,
        originalFilename: String,
        contentType: String,
        byteSize: Long,
        width: Int?,
        height: Int?,
        requestedVariants: List<String>,
    ): JobStartResult

    /**
     * T2: Record job success (REQUIRES_NEW).
     * Upserts image_objects rows, updates asset→READY, job→SUCCEEDED.
     */
    fun recordJobSuccess(
        jobId: Long,
        assetId: Long,
        objects: List<ImageObjectInput>,
    ): Unit

    /**
     * T3: Record job failure (REQUIRES_NEW + NonCancellable context in caller).
     * Updates job→FAILED + asset→FAILED.
     */
    fun recordJobFailure(
        jobId: Long,
        assetId: Long,
        errorCode: String,
        errorMessage: String,
    ): Unit

    /**
     * Event mini-tx: Append one event row (REQUIRES_NEW).
     * Failure suppressed by caller (see §3.7).
     */
    fun appendEvent(
        jobId: Long,
        step: ImageProcessingStep,
        status: ImageProcessingEventStatus,
        message: String,
        payload: Map<String, Any?> = emptyMap(),
    ): Unit

    /** Query — returns null when not found (caller maps to 404). */
    fun findAssetByExternalId(externalId: String): ImageAssetDetailResponse?

    /** Query — returns null when not found (caller maps to 404). */
    fun findAssetHistory(externalId: String): ImageAssetHistoryResponse?
}

data class JobStartResult(
    val assetId: Long,
    val jobId: Long,
    val alreadyExists: Boolean,  // true → READY short-circuit; caller skips processing
) : Serializable {
    companion object { const val serialVersionUID = 1L }
}

data class ImageObjectInput(
    val kind: ImageObjectKind,        // ORIGINAL | VARIANT
    val variantName: String?,         // null for ORIGINAL
    val s3Key: String,
    val publicUrl: String,
    val width: Int?,
    val height: Int?,
    val byteSize: Long,
    val format: String?,
) : Serializable {
    companion object { const val serialVersionUID = 1L }
}
```

**Status enum clarification** (by design — intentional separation):
- `ImageAssetStatus` (`image_assets.status`): `PROCESSING | READY | FAILED`
- `ImageJobStatus` (`image_processing_jobs.status`): `RUNNING | SUCCEEDED | FAILED`
- `ImageProcessingEventStatus` (`image_processing_events.status`): `STARTED | COMPLETED | FAILED | SKIPPED`

The `event.status` enum is distinct because events are progress markers (they can be `STARTED`, `COMPLETED`, or `FAILED` for the *step*), whereas asset/job status tracks the overall lifecycle state.

### Modified Files

- `build.gradle.kts` — add Exposed + PostgreSQL + Testcontainers dependencies
- `src/main/resources/application.yml` — add `spring.datasource` + HikariCP statement_timeout
- `service/ImageDerivativeWorkflowService.kt` — inject `ImagePersistenceService`, add checksum, saga calls
- `web/ImageDerivativesController.kt` — add GET endpoints
- `web/ImageProcessingExceptionHandler.kt` — add 404 handler for `NoSuchElementException`
- `service/ImageDerivativeWorkflowServiceTest.kt` — inject `NoopImagePersistenceService` (test source path)

---

## 6. API Specification

### GET /api/images/{externalId}
- **Response 200**: `ImageAssetDetailResponse`
  ```json
  {
    "asset": { "externalId": "uuid-v4", "status": "READY", ... },
    "original": { "kind": "ORIGINAL", "publicUrl": "https://...", ... },
    "variants": [{ "kind": "VARIANT", "variantName": "thumb", "publicUrl": "https://..." }]
  }
  ```
- **Response 404**: `ProblemDetail` (`NoSuchElementException` → 404 via exception handler)

### GET /api/images/{externalId}/history
- **Response 200**: `ImageAssetHistoryResponse`
  ```json
  {
    "asset": { ... },
    "jobs": [{ "job": { "status": "SUCCEEDED", "durationMs": 1234, ... }, "events": [...] }]
  }
  ```
- **Response 404**: `ProblemDetail`

---

## 7. Security Assumptions

This module is a **workshop/demo** running in a trusted local context. The following security limitations are intentional:

1. **No authentication guard on GET endpoints** — Any caller who knows an `externalId` can retrieve asset metadata. Production adaptations must add bearer token validation and owner-scoped queries.
2. **Global checksum deduplication** — No per-user scoping. Two users uploading identical bytes share the same `image_assets` row and `externalId`. Acceptable for a single-user/trusted demo; production must scope `UNIQUE` to `(checksum, owner_id)`.
3. **UUID v4 required** — `external_id` is populated by `UUID.randomUUID()`. Do not substitute v1/v3/v5 UUIDs as they are partially predictable.
4. **Error message sanitization is required** — `error_message` stored in DB is a safe user string. Full exception detail is logged only.

---

## 8. Configuration

### application.yml additions
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/image_processing
    username: postgres
    password: postgres
    driver-class-name: org.postgresql.Driver
    hikari:
      connection-init-sql: "SET statement_timeout='10000'"  # 10s per statement
      maximum-pool-size: 10
      connection-timeout: 30000
```

**Pool sizing analysis**:
- Each upload request uses at most: T1 (1 connection) + up to 6 event mini-tx (1 each, sequential) + T2 (1 connection) = serial usage, max 1 connection held at any time per request.
- Concurrent test forks: `forkEvery=1` spawns isolated JVMs; the Testcontainers container is shared via reuse but each fork gets its own HikariCP pool (`maximum-pool-size: 10`). Single-fork concurrent test parallelism is bounded by `junit.jupiter.execution.parallel.config.fixed.parallelism` (typically 1 for serial test mode per `TestMutexService`).
- **Workshop conclusion**: `maximum-pool-size: 10` is sufficient for single-user serial tests. Documented assumption. `leak-detection-threshold: 30000` added for connection leak detection.

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/image_processing
    username: postgres
    password: postgres
    driver-class-name: org.postgresql.Driver
    hikari:
      connection-init-sql: "SET statement_timeout='10000'"  # 10s per statement
      maximum-pool-size: 10
      connection-timeout: 30000
      leak-detection-threshold: 30000  # log connection leak if held > 30s
```

### DatabaseInitializer requirement
`ImagePersistenceDatabaseInitializer.run()` must **NOT** catch exceptions from `SchemaUtils.create()`. If DDL initialization fails, the application must not start. Wrapping in a no-op `try/catch` is prohibited.

---

## 9. Bluetape4k Features Used

| Feature | Module/Artifact | Usage | Benefit |
|---------|-----------------|-------|---------|
| `AuditableLongIdTable` | `bluetape4k-exposed-core` | `ImageAssetTable`, `ImageObjectTable` | Free `createdAt`/`updatedAt`/`createdBy` columns |
| `LongAuditableJdbcRepository` | `bluetape4k-exposed-jdbc` | `ImageAssetRepository`, `ImageObjectRepository` | Free CRUD + `auditedUpdateById` + `findPage` |
| `LongJdbcRepository` | `bluetape4k-exposed-jdbc` | `ImageProcessingJobRepository`, `ImageProcessingEventRepository` | Lightweight repo for non-audited tables |
| `jacksonb<T>()` | `bluetape4k-exposed-jackson3` | `requestedVariants`, `payloadJson` columns | Type-safe JSONB without manual ser/deser |
| `ExposedPage<T>` | `bluetape4k-exposed-core` | `findPage()` on repositories | Pagination without custom implementation |
| `PostgreSQLServer.Launcher` | `bluetape4k-testcontainers` | Integration tests | Singleton container, no `@Testcontainers` needed |
| `KLogging` | `bluetape4k-logging` | All new service/repository classes | Structured logging |
| `bluetape4k-assertions` | `bluetape4k-assertions` | All tests | Richer assertions than vanilla JUnit 5 |
| `requireNotBlank` | `bluetape4k-core` | Service input validation | Idiomatic arg validation |

---

## 10. Test Scenarios (Mandatory)

Each DoD criterion must have at least one named integration test.

| # | Test scenario | Test class | Assertion |
|---|--------------|-----------|-----------|
| T1 | Successful upload: T1 commits BEFORE VIPS runs | `ImagePersistenceServiceImplTest` | Query DB after `recordJobStart()`: `image_assets.status=PROCESSING`, `image_processing_jobs.status=RUNNING` |
| T2 | Successful upload: T2 writes READY + SUCCEEDED + image_objects | `ImagePersistenceServiceImplTest` | After `recordJobSuccess()`: asset=READY, job=SUCCEEDED, `image_objects` count = variants+1 |
| T3-status | Failed upload: T3 writes FAILED status | `ImagePersistenceServiceImplTest` | After `recordJobFailure()`: job=FAILED, asset=FAILED |
| T3-fields | Failed upload: error_code + error_message are non-null | `ImagePersistenceServiceImplTest` | `job.errorCode` and `job.errorMessage` are not null or blank |
| T3-propagate | T3 DB failure: original exception still propagates | `ImagePersistenceServiceImplTest` | Disconnect DB before T3 → verify original exception (not T3 DB exception) is thrown |
| Idempotency-checksum | Retry same checksum → no duplicate image_objects | `ImagePersistenceServiceImplTest` | Upload same bytes twice → `COUNT(image_objects WHERE image_asset_id=X)` = expected variants+1 |
| Idempotency-short-circuit | Same checksum + READY → no re-processing | `ImagePersistenceServiceImplTest` | `recordJobStart()` returns `alreadyExists=true` on second call |
| FAILED-retry | FAILED asset + same checksum → retry succeeds | `ImagePersistenceServiceImplTest` | Seed a FAILED asset → call `recordJobStart()` → asset status=PROCESSING, new job created |
| GET-asset | GET /images/{id} returns data from DB | `ImageAssetEndpointTest` | Seed data via persistence service → call endpoint → verify URLs match DB rows |
| GET-404 | GET /images/{unknown-id} → 404 ProblemDetail | `ImageAssetEndpointTest` | Response status 404, Content-Type `application/problem+json` |
| GET-history | GET /history returns job + events | `ImageAssetEndpointTest` | Response jobs list non-empty; first job has events with step names |
| DB-init | DatabaseInitializer creates all 4 tables | `ImagePersistenceServiceImplTest` | On Spring context start, all 4 tables queryable via `SELECT COUNT(*) FROM ...` |
| Event-success-path | Successful upload emits all 6 step events in order | `ImagePersistenceServiceImplTest` | After full saga: `image_processing_events` rows for `VALIDATION→JOB_STARTED→VIPS_PROCESSING→S3_UPLOAD(×N)→JOB_COMPLETED`, ordered by `created_at`; each has non-null `step`, `status=COMPLETED` |
| Event-failure-path | Failed upload emits `JOB_FAILED` event | `ImagePersistenceServiceImplTest` | After T3: one `image_processing_events` row with `step=JOB_FAILED`, `status=FAILED`, non-null `message` |
| Event-mini-tx-suppression | Event mini-tx throws → main flow unaffected | `ImagePersistenceServiceImplTest` | Spy/mock `appendEvent` to throw; call `recordJobStart()` → no exception propagated; main saga result is valid |
| Concurrent-checksum-race | Two simultaneous uploads of same checksum → graceful | `ImagePersistenceServiceImplTest` | Launch 2 coroutines calling `recordJobStart()` with same checksum concurrently; one gets `alreadyExists=true` or same `assetId`; no unhandled DB exception; `image_assets` count = 1 |

---

## 11. Acceptance Criteria (DoD)

- [ ] `POST /api/images/derivatives` response includes `imageId` that is persisted in `image_assets`
- [ ] `GET /api/images/{imageId}` returns asset metadata and derivative URLs reloaded from PostgreSQL
- [ ] `GET /api/images/{imageId}/history` returns at least one completed job with events
- [ ] Failed processing creates a queryable `image_processing_jobs` row with `status=FAILED` + `error_code`/`error_message`
- [ ] Retry (same checksum) is idempotent: no duplicate `image_objects` rows
- [ ] FAILED asset + same checksum retry succeeds (no `DataIntegrityViolationException`)
- [ ] Concurrent same-checksum uploads handled gracefully (no unhandled DB exception)
- [ ] All existing tests (`ImageDerivativeWorkflowServiceTest`, unit tests) pass without PostgreSQL
- [ ] Integration tests cover all 16 scenarios in §10 (including 4 new event lifecycle + race scenarios)
- [ ] `README.md` and `README.ko.md` include ERD + updated persistence sequence diagram
- [ ] `README.md` includes "Used Bluetape4k features" table
- [ ] `README.md` includes stale-job monitoring query in Operations section
- [ ] All public APIs have English KDoc
- [ ] `error_message` in DB contains no stack traces or internal paths

---

## 12. Constraints

- Exposed 1.2+ operators: top-level `eq`, `and`, `inList` — NOT `SqlExpressionBuilder.eq`
- No `!!` operator; prefer `val`; all `data class` implement `Serializable` + `serialVersionUID`
- `withContext(Dispatchers.IO)` for all blocking Exposed JDBC calls from suspend context
- All saga transactions use `REQUIRES_NEW` (not `REQUIRED`)
- `CancellationException` must be re-thrown before any broad `catch(Exception)`; `runCatching {}` prohibited around suspend calls
- `NoopImagePersistenceService` in `src/test/kotlin/` only — never in production source
- `SchemaUtils.create()` failure must propagate and prevent application startup
- `created_by`/`updated_by` audit columns populated with `"image-processing-service"` (fixed identity)
- PostgreSQL 15+ required for `NULLS NOT DISTINCT` on `image_objects` unique constraint; confirmed via `PostgreSQLServer.Launcher` using `postgres:18-alpine`
- No ktlint hooks
- No Flyway/Liquibase — `SchemaUtils.create()` (workshop convention)
- `forkEvery=1` remains in build config for VIPS FFM isolation

---

## Appendix: Review Iteration Log

| Round | Phase 1 P0/P1 | Silent Failure P0/P1 | Security P0/P1 | Test P0/P1 | 6-Tier Advisor P1 | P0/P1 applied | Commit |
|-------|--------------|---------------------|---------------|-----------|------------------|---------------|--------|
| 1 | 3 HIGH / 4 MEDIUM | 4 HIGH / 4 MEDIUM | 0 HIGH / 3 MEDIUM | 8 HIGH / 6 MEDIUM | — | All HIGH addressed in spec rev | committed |
| 2 (advisor) | (pending) | (pending) | (pending) | (pending) | P0=0, P1=5 (Tier3+5+6) | §3.7 event lifecycle, interface sigs, pool analysis, 4 new §10 scenarios | committed |
