# Image Processing Persistence Layer — Implementation Plan

**Date**: 2026-05-26
**Spec**: `docs/superpowers/specs/2026-05-26-image-persistence-design.md`
**Issue**: #94
**Module**: `image-processing/advanced-workflow`

---

## Phase 1 — Foundation (14 tasks)

### T1 — Add Exposed + PostgreSQL + Testcontainers dependencies to build.gradle.kts
- **File(s)**: `image-processing/advanced-workflow/build.gradle.kts`
- **Complexity**: low
- **Dependencies**: none
- **Details**:
  - Add `implementation(libs.exposed.core)`, `implementation(libs.exposed.jdbc)`,
    `implementation(libs.exposed.jackson3)`, `implementation(libs.jetbrains.exposed.java.time)`,
    `implementation(libs.jetbrains.exposed.spring.boot4.starter)`,
    `implementation(libs.jetbrains.exposed.spring7.transaction)`,
    `implementation(libs.hikaricp)`, `runtimeOnly(libs.postgresql.driver)`
  - Add test: `testImplementation(libs.bluetape4k.testcontainers)`,
    `testImplementation(libs.testcontainers.postgresql)`
  - Do NOT touch `forkEvery` — already set to `1`
  - Do NOT touch `testImplementation.extendsFrom` — already configured
- **Acceptance criteria**: `./gradlew :image-processing-advanced-workflow:dependencies` resolves all new artifacts without conflict

### T2 — Add spring.datasource + HikariCP config to application.yml
- **File(s)**: `image-processing/advanced-workflow/src/main/resources/application.yml`
- **Complexity**: low
- **Dependencies**: none
- **Details**:
  - Add `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password`,
    `spring.datasource.driver-class-name: org.postgresql.Driver`
  - Add `spring.datasource.hikari.connection-init-sql: "SET statement_timeout='10000'"`,
    `maximum-pool-size: 10`, `connection-timeout: 30000`, `leak-detection-threshold: 30000`
- **Acceptance criteria**: YAML parses without error; Spring Boot binds `DataSourceProperties` on startup

### T3 — Create ImageAssetStatus enum
- **File(s)**: `src/main/kotlin/.../advanced/persistence/schema/ImageAssetStatus.kt`
- **Complexity**: low
- **Dependencies**: none
- **Details**: `enum class ImageAssetStatus { PROCESSING, READY, FAILED }`
- **Acceptance criteria**: Compiles; used by `ImageAssetTable.status` column

### T4 — Create ImageJobStatus enum
- **File(s)**: `src/main/kotlin/.../advanced/persistence/schema/ImageJobStatus.kt`
- **Complexity**: low
- **Dependencies**: none
- **Details**: `enum class ImageJobStatus { RUNNING, SUCCEEDED, FAILED }`
- **Acceptance criteria**: Compiles; used by `ImageProcessingJobTable.status` column

### T5 — Create ImageProcessingStep enum
- **File(s)**: `src/main/kotlin/.../advanced/persistence/schema/ImageProcessingStep.kt`
- **Complexity**: low
- **Dependencies**: none
- **Details**: `enum class ImageProcessingStep { VALIDATION, VIPS_PROCESSING, S3_UPLOAD, JOB_COMPLETED, JOB_FAILED }`
- **Acceptance criteria**: Compiles; matches spec §3.7 step table; no `JOB_STARTED`

### T6 — Create ImageProcessingEventStatus enum
- **File(s)**: `src/main/kotlin/.../advanced/persistence/schema/ImageProcessingEventStatus.kt`
- **Complexity**: low
- **Dependencies**: none
- **Details**: `enum class ImageProcessingEventStatus { COMPLETED, FAILED, SKIPPED }` — no `STARTED`
- **Acceptance criteria**: Compiles; distinct from `ImageAssetStatus` and `ImageJobStatus`

### T7 — Create ImageObjectKind enum
- **File(s)**: `src/main/kotlin/.../advanced/model/ImageObjectKind.kt`
- **Complexity**: low
- **Dependencies**: none
- **Details**: `enum class ImageObjectKind { ORIGINAL, VARIANT }`
- **Note**: Spec claims this exists from issue #93 but does NOT exist in codebase (grep-verified). Must be created.
- **Acceptance criteria**: Compiles; referenced by `ImageObjectTable.kind` and `ImageObjectInput.kind`

### T8 — Create ImageAssetTable (Exposed AuditableLongIdTable)
- **File(s)**: `src/main/kotlin/.../advanced/persistence/schema/ImageAssetTable.kt`
- **Complexity**: low
- **Dependencies**: T3
- **Details**:
  - Extends `AuditableLongIdTable("image_assets")`
  - Columns: `externalId` (varchar 36, uniqueIndex), `originalFilename` (varchar 255, nullable),
    `contentType` (varchar 100, nullable), `byteSize` (long, nullable),
    `width` (integer, nullable), `height` (integer, nullable),
    `checksum` (varchar 64, uniqueIndex), `status` (varchar 20, default "PROCESSING")
  - Inherits `createdBy`, `createdAt`, `updatedBy`, `updatedAt` from `AuditableIdTable`
  - Use top-level Exposed 1.2+ operators only — NEVER `SqlExpressionBuilder.eq`
- **Acceptance criteria**: Object compiles; column types match spec §4 DDL

### T9 — Create ImageObjectTable (Exposed AuditableLongIdTable)
- **File(s)**: `src/main/kotlin/.../advanced/persistence/schema/ImageObjectTable.kt`
- **Complexity**: medium
- **Dependencies**: T7, T8
- **Details**:
  - Extends `AuditableLongIdTable("image_objects")`
  - Columns: `imageAssetId` (reference to `ImageAssetTable`, `ON DELETE CASCADE`),
    `kind` (varchar 20), `variantName` (varchar 100, nullable),
    `s3Key` (varchar 512), `publicUrl` (text), `width` (integer, nullable),
    `height` (integer, nullable), `byteSize` (long, nullable), `format` (varchar 20, nullable)
  - `UNIQUE NULLS NOT DISTINCT` composite index on `(imageAssetId, kind, variantName)` — via raw SQL in DatabaseInitializer (T14) since Exposed DSL does not support `NULLS NOT DISTINCT` natively
  - Regular index on `imageAssetId` for FK lookup
- **Acceptance criteria**: Object compiles; FK cascade declared; NULLS NOT DISTINCT handled via initializer

### T10 — Create ImageProcessingJobTable (Exposed LongIdTable)
- **File(s)**: `src/main/kotlin/.../advanced/persistence/schema/ImageProcessingJobTable.kt`
- **Complexity**: low
- **Dependencies**: T4, T8
- **Details**:
  - Extends `LongIdTable("image_processing_jobs")` — NOT auditable (explicit timestamps per spec)
  - Columns: `imageAssetId` (reference, `ON DELETE CASCADE`),
    `status` (varchar 20), `requestedVariants` (`jacksonb<List<String>>()`),
    `startedAt` (timestamp, defaultExpression CurrentTimestamp),
    `finishedAt` (timestamp, nullable), `durationMs` (long, nullable),
    `errorCode` (varchar 100, nullable), `errorMessage` (text, nullable)
  - Index on `imageAssetId`
- **Acceptance criteria**: Object compiles; uses `jacksonb<List<String>>()` from bluetape4k-exposed-jackson3

### T11 — Create ImageProcessingEventTable (Exposed LongIdTable)
- **File(s)**: `src/main/kotlin/.../advanced/persistence/schema/ImageProcessingEventTable.kt`
- **Complexity**: low
- **Dependencies**: T5, T6, T10
- **Details**:
  - Extends `LongIdTable("image_processing_events")`
  - Columns: `jobId` (reference to `ImageProcessingJobTable`, `ON DELETE CASCADE`),
    `step` (varchar 100), `status` (varchar 20), `message` (text, nullable),
    `payloadJson` (`jacksonb<Map<String, Any?>>()`, nullable),
    `createdAt` (timestamp, defaultExpression CurrentTimestamp)
  - Index on `jobId`
- **Acceptance criteria**: Object compiles; `payloadJson` uses `jacksonb` column type

### T12 — Create ImagePersistenceModels.kt (DTOs + sealed JobStartResult)
- **File(s)**: `src/main/kotlin/.../advanced/model/ImagePersistenceModels.kt`
- **Complexity**: low
- **Dependencies**: T7, T3, T4, T5, T6
- **Details**:
  - Data classes: `AssetMetadataInput`, `ImageDimensions`, `JobIdentity`, `JobFailureReason`,
    `ImageObjectInput` — all with `Serializable` + `serialVersionUID`
  - `sealed interface JobStartResult` with 4 subtypes: `NewAsset`, `AlreadyReady`,
    `ConcurrentProcessing`, `RecoveredFromFailed` — all carry `assetId: Long`, `jobId: Long`, `externalId: String`
    (externalId added per spec gap R10 — POST response `imageId` must match persisted `external_id`)
  - `AlreadyReady.jobId` defaults to `-1L` (no new job created in short-circuit path)
  - Response DTOs: `ImageAssetDTO`, `ImageObjectDTO`, `ImageProcessingJobDTO`,
    `ImageProcessingEventDTO`, `ImageAssetDetailResponse`, `ImageAssetHistoryResponse`,
    `ImageJobWithEventsDTO`
  - `ImageAssetNotFoundException(val checksum: String)` custom exception
- **Acceptance criteria**: All data classes compile; `JobStartResult` subtypes are exhaustive in `when`; `externalId` present on all 4 subtypes

### T13 — Create ImagePersistenceMappers.kt (ResultRow extension functions)
- **File(s)**: `src/main/kotlin/.../advanced/persistence/mapper/ImagePersistenceMappers.kt`
- **Complexity**: low
- **Dependencies**: T8, T9, T10, T11, T12
- **Details**:
  - `ResultRow.toImageAssetDTO()`: maps `ImageAssetTable` row → `ImageAssetDTO`
  - `ResultRow.toImageObjectDTO()`: maps `ImageObjectTable` row → `ImageObjectDTO`
  - `ResultRow.toImageProcessingJobDTO()`: maps `ImageProcessingJobTable` row → `ImageProcessingJobDTO`
  - `ResultRow.toImageProcessingEventDTO()`: maps `ImageProcessingEventTable` row → `ImageProcessingEventDTO`
  - Use top-level Exposed 1.2+ operators; never `SqlExpressionBuilder.eq`
- **Acceptance criteria**: Each mapper function compiles; maps all columns including nullable ones

### T14 — Create ImagePersistenceDatabaseInitializer
- **File(s)**: `src/main/kotlin/.../advanced/persistence/config/ImagePersistenceDatabaseInitializer.kt`
- **Complexity**: medium
- **Dependencies**: T8, T9, T10, T11
- **Details**:
  - Implements `ApplicationRunner`; `@Component`
  - `@Transactional` on `run()` method
  - Calls `SchemaUtils.create(ImageAssetTable, ImageObjectTable, ImageProcessingJobTable, ImageProcessingEventTable)`
  - After `SchemaUtils.create`, execute raw SQL:
    ```sql
    CREATE UNIQUE INDEX IF NOT EXISTS uq_image_objects_asset_kind_variant
    ON image_objects (image_asset_id, kind, variant_name) NULLS NOT DISTINCT
    ```
  - Must NOT wrap `SchemaUtils.create()` in try/catch — startup DDL failure must propagate
  - **UserContext note**: All auditable table writes must use `UserContext.withUser("image-processing-service") { ... }` — NOT Spring `AuditorAware` bean (bluetape4k-exposed uses `UserContext.getCurrentUser()` via `clientDefault` in `AuditableIdTable`)
- **Acceptance criteria**: All 4 tables created on app startup; `NULLS NOT DISTINCT` index exists; no try/catch around DDL

---

## Phase 2 — Repository Layer (4 tasks)

### T15 — Create ImageAssetRepository
- **File(s)**: `src/main/kotlin/.../advanced/persistence/repository/ImageAssetRepository.kt`
- **Complexity**: medium
- **Dependencies**: T8, T12, T13
- **Details**:
  - `@Component` class; implements `LongAuditableJdbcRepository<ImageAssetDTO, ImageAssetTable>`
  - `override val table = ImageAssetTable`
  - `override fun ResultRow.toEntity()` delegates to `toImageAssetDTO()`
  - Custom methods:
    - `findByChecksum(checksum: String): ImageAssetDTO?`
    - `findByExternalId(externalId: String): ImageAssetDTO?`
    - `updateStatus(id: Long, status: ImageAssetStatus)` — uses `auditedUpdateById`
  - Use top-level `eq` operator only
- **Acceptance criteria**: Compiles; `findByChecksum` uses top-level `eq`; `auditedUpdateById` sets `updatedBy`

### T16 — Create ImageObjectRepository
- **File(s)**: `src/main/kotlin/.../advanced/persistence/repository/ImageObjectRepository.kt`
- **Complexity**: medium
- **Dependencies**: T9, T12, T13
- **Details**:
  - `@Component` class; implements `LongAuditableJdbcRepository<ImageObjectDTO, ImageObjectTable>`
  - Custom methods:
    - `batchUpsertObjects(assetId: Long, objects: List<ImageObjectInput>)` — uses
      `table.batchUpsert(objects, keys = listOf(ImageObjectTable.imageAssetId, ImageObjectTable.kind, ImageObjectTable.variantName)) { row, obj -> ... }`
      to hit the `NULLS NOT DISTINCT` unique index
    - `findByAssetId(assetId: Long): List<ImageObjectDTO>`
- **Acceptance criteria**: `batchUpsertObjects` upserts with correct key columns; idempotent on retry

### T17 — Create ImageProcessingJobRepository
- **File(s)**: `src/main/kotlin/.../advanced/persistence/repository/ImageProcessingJobRepository.kt`
- **Complexity**: medium
- **Dependencies**: T10, T12, T13
- **Details**:
  - `@Component` class; implements `LongJdbcRepository<ImageProcessingJobDTO>`
  - Custom methods:
    - `insertJob(assetId: Long, requestedVariants: List<String>): Long` — returns generated job ID
    - `markSucceeded(jobId: Long, durationMs: Long)` — updates status, finishedAt, durationMs
    - `markFailed(jobId: Long, errorCode: String, errorMessage: String, durationMs: Long)`
    - `findByAssetId(assetId: Long): List<ImageProcessingJobDTO>` — ordered by startedAt DESC
- **Acceptance criteria**: `insertJob` returns valid ID; `markFailed` populates all error fields

### T18 — Create ImageProcessingEventRepository
- **File(s)**: `src/main/kotlin/.../advanced/persistence/repository/ImageProcessingEventRepository.kt`
- **Complexity**: medium
- **Dependencies**: T11, T12, T13
- **Details**:
  - `@Component` class; implements `LongJdbcRepository<ImageProcessingEventDTO>`
  - Custom methods:
    - `appendEvent(jobId: Long, step: ImageProcessingStep, status: ImageProcessingEventStatus, message: String, payload: Map<String, Any?> = emptyMap())` — simple insert; propagates exceptions to caller
    - `findByJobId(jobId: Long): List<ImageProcessingEventDTO>` — ordered by createdAt ASC
- **Acceptance criteria**: `appendEvent` inserts one row; `payloadJson` serialized via `jacksonb`

---

## Phase 3 — Service Layer (3 tasks)

### T19 — Create ImagePersistenceService interface
- **File(s)**: `src/main/kotlin/.../advanced/persistence/ImagePersistenceService.kt`
- **Complexity**: low
- **Dependencies**: T12
- **Details**:
  - Interface with 6 methods from spec §5:
    `recordJobStart`, `recordJobSuccess`, `recordJobFailure`, `appendEvent`,
    `findAssetByExternalId`, `findAssetHistory`
  - NO `@Transactional` at interface level — implementation uses programmatic `TransactionTemplate`
  - English KDoc on each method documenting transaction propagation contract
  - `appendEvent` contract: propagates exceptions to caller (no suppression here)
- **Acceptance criteria**: Interface compiles; no `@Transactional` annotations on interface

### T20 — Create ImagePersistenceServiceImpl — write paths (T1/T2/T3/event)
- **File(s)**: `src/main/kotlin/.../advanced/persistence/ImagePersistenceServiceImpl.kt`
- **Complexity**: high
- **Dependencies**: T15, T16, T17, T18, T19
- **Details**:
  - `@Service` class implementing `ImagePersistenceService`
  - No `@Transactional` on class — uses `TransactionTemplate(REQUIRES_NEW)` programmatically
  - **UserContext**: All write methods wrap DB operations in `UserContext.withUser("image-processing-service") { ... }`
  - **`recordJobStart()` — T1** (highest complexity):
    - Public method — NOT annotated with `@Transactional`
    - Uses `TransactionTemplate(REQUIRES_NEW)` internally
    - Query asset by checksum; branch on status:
      - `READY` → return `JobStartResult.AlreadyReady(assetId, jobId=-1L, externalId=existing.externalId)`
      - `PROCESSING` → log + create new job on same asset → return `ConcurrentProcessing`
      - `FAILED` → update status→PROCESSING, create new job → return `RecoveredFromFailed`
      - Not found → generate `UUID.randomUUID().toString()` for `externalId`, insert asset + job → return `NewAsset`
    - `DataIntegrityViolationException` caught OUTSIDE the template execute block
      (transaction already rolled back when DIVE propagates):
      re-read by checksum, branch on status.
      If re-read returns null → throw `ImageAssetNotFoundException(checksum)`.
  - **`recordJobSuccess()` — T2**: `TransactionTemplate(REQUIRES_NEW)`:
    upsert image_objects via `batchUpsertObjects`, update asset status→READY, mark job SUCCEEDED
  - **`recordJobFailure()` — T3**: `TransactionTemplate(REQUIRES_NEW)`:
    mark job FAILED with sanitized `errorCode`/`errorMessage`, update asset status→FAILED.
    `withContext(NonCancellable + Dispatchers.IO)` wrapping is the CALLER's responsibility.
  - **`appendEvent()`**: `TransactionTemplate(REQUIRES_NEW)`: single event row insert.
    Propagates exceptions to caller — no suppression inside implementation.
- **Acceptance criteria**:
  - `recordJobStart` handles all 4 branches + DIVE catch + null re-read
  - No `@Transactional` annotation on class
  - UserContext set before all DB writes
  - Error messages sanitized (no stack traces, no VIPS paths, no JDBC URLs)

### T21 — Implement read paths (findAssetByExternalId, findAssetHistory)
- **File(s)**: `src/main/kotlin/.../advanced/persistence/ImagePersistenceServiceImpl.kt` (same file as T20)
- **Complexity**: medium
- **Dependencies**: T20
- **Details**:
  - **`findAssetByExternalId()`**: Read transaction:
    query asset by externalId, query image_objects by assetId.
    Assemble `ImageAssetDetailResponse(asset, original, variants)`.
    Return null if asset not found (caller maps to 404).
    Handle FAILED asset with no objects: return `original=null, variants=emptyList()` (not NPE).
  - **`findAssetHistory()`**: Read transaction:
    query asset by externalId, query jobs by assetId, for each job query events.
    Assemble `ImageAssetHistoryResponse(asset, jobs+events)`.
    Return null if asset not found.
- **Acceptance criteria**: Read methods return correct DTO structure; null-safe for FAILED assets

---

## Phase 4 — API Layer (3 tasks)

### T22 — Add GET /api/images/{externalId} endpoint
- **File(s)**: `src/main/kotlin/.../advanced/web/ImageDerivativesController.kt`
- **Complexity**: medium
- **Dependencies**: T19, T21
- **Details**:
  - Inject `ImagePersistenceService` into controller
  - `@GetMapping("/{externalId}")` suspend function:
    `withContext(Dispatchers.IO) { persistence.findAssetByExternalId(externalId) }`
    `?: throw NoSuchElementException("Image asset not found: $externalId")`
  - Rethrow `CancellationException` before any broad catch
- **Acceptance criteria**: Returns 200 JSON body; 404 via exception handler for unknown IDs

### T23 — Add GET /api/images/{externalId}/history endpoint
- **File(s)**: `src/main/kotlin/.../advanced/web/ImageDerivativesController.kt`
- **Complexity**: medium
- **Dependencies**: T19, T21
- **Details**:
  - `@GetMapping("/{externalId}/history")` suspend function
  - Response type: `ImageAssetHistoryResponse`
  - Same null → 404 pattern as T22
- **Acceptance criteria**: Returns 200 with jobs + events; 404 for unknown IDs

### T24 — Add NoSuchElementException handler to ImageProcessingExceptionHandler
- **File(s)**: `src/main/kotlin/.../advanced/web/ImageProcessingExceptionHandler.kt`
- **Complexity**: low
- **Dependencies**: none
- **Details**:
  - Add `@ExceptionHandler(NoSuchElementException::class)` method returning
    `ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.message ?: "Resource not found")`
  - Content-Type: `application/problem+json`
- **Acceptance criteria**: GET with unknown externalId returns 404 `application/problem+json`

---

## Phase 5 — Integration & Workflow (3 tasks)

### T25 — Wire ImageDerivativeWorkflowService to use ImagePersistenceService
- **File(s)**: `src/main/kotlin/.../advanced/service/ImageDerivativeWorkflowService.kt`
- **Complexity**: high
- **Dependencies**: T19, T20
- **Details**:
  - Add `ImagePersistenceService` as required constructor parameter (not nullable — use `NoopImagePersistenceService` in existing tests)
  - In `processUploadInternal()`:
    1. Compute SHA-256 checksum of `bytes`
    2. `withContext(Dispatchers.IO) { persistence.recordJobStart(assetMetadata, requestedVariants) }`
       with explicit `CancellationException` rethrow before broad catch
    3. `if result is AlreadyReady` → skip processing, query `persistence.findAssetByExternalId(result.externalId)`, return cached response
    4. Use `imageId = result.externalId` from `JobStartResult` (not `UUID.randomUUID()`)
    5. Emit events per step:
       - `VALIDATION` (after T1 commits, jobId known)
       - `VIPS_PROCESSING` (after VIPS returns)
       - `S3_UPLOAD` (after each putObject: `COMPLETED` or `FAILED`)
       - If any `S3_UPLOAD FAILED`: emit `S3_UPLOAD` event with `FAILED` status, immediately invoke T3. No more variant uploads — terminal.
       - `JOB_COMPLETED` (after T2 commits)
       - `JOB_FAILED` (in catch block, after T3 commits)
    6. Each `appendEvent` wrapped in try/catch:
       suppress via `log.warn(e) { "Event append failed: step=$step jobId=$jobId" }` +
       `meterRegistry.counter(METRIC_EVENT_APPEND_FAILED).increment()`
    7. T3 in catch block: `withContext(NonCancellable + Dispatchers.IO) { ... }` per spec §3.1.
       Catch `Throwable` for T3 failure; `originalException.addSuppressed(t3Ex)`.
       Always rethrow `originalException`.
  - Metric constants: `METRIC_EVENT_APPEND_FAILED = "image.processing.event.append.failed"`,
    `METRIC_FAILURE_RECORD_FAILED = "image.processing.failure.record.failed"`
- **Acceptance criteria**:
  - Existing tests pass with `NoopImagePersistenceService`
  - Saga flow: T1 → process → events → T2 on success; T3 on failure
  - S3_UPLOAD FAILED is terminal
  - CancellationException always rethrown; no `runCatching` around suspend calls

### T26 — Create NoopImagePersistenceService (test fake)
- **File(s)**: `src/test/kotlin/.../advanced/persistence/NoopImagePersistenceService.kt`
- **Complexity**: low
- **Dependencies**: T19
- **Details**:
  - Lives in `src/test/kotlin/` ONLY — never in production source
  - Implements `ImagePersistenceService`
  - `recordJobStart()` returns `JobStartResult.NewAsset(assetId=0L, jobId=0L, externalId=UUID.randomUUID().toString())`
  - All other methods: no-ops (empty body; return null for queries)
- **Acceptance criteria**: Test fake compiles; no DB interaction; returns stable result from `recordJobStart`

### T27 — Update existing ImageDerivativeWorkflowServiceTest with NoopImagePersistenceService
- **File(s)**: `src/test/kotlin/.../advanced/service/ImageDerivativeWorkflowServiceTest.kt`
- **Complexity**: medium
- **Dependencies**: T25, T26
- **Details**:
  - Update `service()` factory method to pass `NoopImagePersistenceService()` as required `persistence` param
  - All 4 existing tests must pass without PostgreSQL
- **Acceptance criteria**: All 4 existing tests pass green; no PostgreSQL container needed

---

## Phase 6 — Tests (4 tasks)

### T28 — Create AbstractImagePersistenceTest (test base class)
- **File(s)**: `src/test/kotlin/.../advanced/persistence/AbstractImagePersistenceTest.kt`
- **Complexity**: medium
- **Dependencies**: T1, T14
- **Details**:
  - `@SpringBootTest` with `@TestInstance(TestInstance.Lifecycle.PER_CLASS)`
  - `PostgreSQLServer.Launcher.postgres` singleton (NEVER `GenericContainer` directly)
  - `@DynamicPropertySource` companion method to inject:
    `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password`
  - `forkEvery=1` in build config already handles JVM isolation; each fork reconnects to shared container
  - Helper methods: teardown by assetId via `ON DELETE CASCADE`
- **Acceptance criteria**: Base class starts Spring context with PostgreSQL; all 4 tables exist

### T29 — Create ImagePersistenceServiceImplTest (14 scenarios)
- **File(s)**: `src/test/kotlin/.../advanced/persistence/ImagePersistenceServiceImplTest.kt`
- **Complexity**: high
- **Dependencies**: T28, T20, T21
- **Details**: Covers spec §10 scenarios:
  1. **T1-creates**: `recordJobStart` creates PROCESSING asset + RUNNING job
  2. **T2-success**: `recordJobSuccess` → READY + SUCCEEDED + correct image_objects count
  3. **T3-status**: `recordJobFailure` → FAILED status on job + asset
  4. **T3-fields**: errorCode + errorMessage non-null/non-blank after failure
  5. **T3-sanitization**: errorMessage contains no stack traces (no `\n`, no `"at io."`, no `"Exception"`)
  6. **Idempotency-checksum**: Same checksum twice → no duplicate image_objects
  7. **Idempotency-short-circuit**: Same checksum + READY → returns `AlreadyReady`
  8. **FAILED-retry**: Seed FAILED asset → `recordJobStart` → status=PROCESSING, new job
  9. **Idempotency-null-original**: `recordJobSuccess` twice with ORIGINAL → exactly 1 ORIGINAL row
  10. **DB-init**: All 4 tables queryable via `SELECT COUNT(*)` on Spring context start
  11. **Event-success-path**: Full saga emits VALIDATION → VIPS_PROCESSING → S3_UPLOAD(×N) → JOB_COMPLETED; total = 3+N events
  12. **Event-failure-path**: After T3, one event with step=JOB_FAILED, status=FAILED
  13. **Concurrent-checksum-race-deterministic**: MockK spy on repository insert throws `DataIntegrityViolationException`; verify re-read path taken
  14. **Concurrent-checksum-race-probabilistic**: `MultithreadingTester` with 2 threads, same checksum; assert no exception; asset count=1
  - All tests use AAA pattern with descriptive backtick names
  - `MultithreadingTester` (bluetape4k-junit5) for T29-14; raw Thread/Executors/coroutineScope launch prohibited
- **Acceptance criteria**: All 14 scenarios pass; DB state verified via direct queries after each operation

### T30 — Create ImageDerivativeWorkflowSagaTest (2 scenarios)
- **File(s)**: `src/test/kotlin/.../advanced/persistence/ImageDerivativeWorkflowSagaTest.kt`
- **Complexity**: high
- **Dependencies**: T25, T28
- **Details**: Covers spec §10 scenarios:
  1. **T3-propagate**: MockK spy on `recordJobFailure()` to throw; trigger upload with failing VIPS mock →
     assert caught exception is original; `suppressed[0]` is T3 exception
  2. **Event-mini-tx-suppression**: MockK spy on `appendEvent` to throw; `processUpload()` succeeds;
     Micrometer counter `image.processing.event.append.failed` incremented ≥ 1
  - `@SpringBootTest` context with spied/mocked `ImagePersistenceService`
- **Acceptance criteria**: T3 failure propagation verified with `suppressed` check; event suppression verified with counter

### T31 — Create ImageAssetEndpointTest (4 scenarios)
- **File(s)**: `src/test/kotlin/.../advanced/web/ImageAssetEndpointTest.kt`
- **Complexity**: medium
- **Dependencies**: T22, T23, T24, T28
- **Details**: Covers spec §10 scenarios:
  1. **GET-asset**: Seed data → GET /api/images/{externalId} → verify body, status 200
  2. **GET-404**: GET unknown ID → 404, Content-Type `application/problem+json`
  3. **GET-history**: GET /api/images/{externalId}/history → jobs non-empty, events with step names
  4. **GET-asset-failed**: Seed FAILED asset with no objects → 200 with `original=null`, `variants=[]`
  - Use `WebTestClient` or `MockMvc`; extends `AbstractImagePersistenceTest`
- **Acceptance criteria**: All 4 endpoint scenarios pass; correct HTTP status codes and content types

---

## Phase 7 — Documentation (2 tasks)

### T32 — Update README.md (ERD + sequence diagram + endpoints + operations)
- **File(s)**: `image-processing/advanced-workflow/README.md`
- **Complexity**: low
- **Dependencies**: T20, T22, T23
- **Details**:
  - ERD diagram (SVG+PNG via `bluetape4k-diagram` skill) — all 4 tables with FK relationships
  - Updated persistence sequence diagram — saga flow (T1 → VIPS → T2/T3)
  - New endpoint documentation: GET /api/images/{externalId} and /history
  - "Used Bluetape4k Features" table from spec §9
  - "Operations" section with stale-job monitoring query from spec §3.6
  - Store diagram assets under `docs/images/readme-diagrams/`; embed only `.png` in README
- **Acceptance criteria**: README includes ERD, sequence diagram, endpoint docs, features table, monitoring query

### T33 — Update README.ko.md with matching content
- **File(s)**: `image-processing/advanced-workflow/README.ko.md`
- **Complexity**: low
- **Dependencies**: T32
- **Details**:
  - Korean translation of all new README sections
  - Same diagrams (shared PNG assets)
  - Language toggle link between README.md and README.ko.md
- **Acceptance criteria**: README.ko.md structurally aligned with README.md; same diagrams referenced

---

## Summary

| Phase | Description | Task Count | IDs |
|-------|-------------|-----------|-----|
| Phase 1 | Foundation | 14 | T1–T14 |
| Phase 2 | Repository Layer | 4 | T15–T18 |
| Phase 3 | Service Layer | 3 | T19–T21 |
| Phase 4 | API Layer | 3 | T22–T24 |
| Phase 5 | Integration & Workflow | 3 | T25–T27 |
| Phase 6 | Tests | 4 | T28–T31 |
| Phase 7 | Documentation | 2 | T32–T33 |
| **Total** | | **33** | |

---

## Critical Path

```
T1 (gradle) ─────────────────────────────────────────────────────────────────────────────+
T2 (application.yml) ────────────────────────────────────────────────────────────────────+
T3–T7 (enums) ───────────────────────────────────────────────────────────────────────────+
                                                                                          |
T12 (models/DTOs) ──────────────────────+─── T8–T11 (tables) ──── T13 (mappers) ──── T15–T18 (repos)
                                         |                                                |
                                         +── T14 (DB initializer)                         |
                                                                                          v
T19 (svc interface) ─────────────────────────────────────────────────────── T20 (svc write)
                                                                                          |
T22–T24 (API) ←── T21 (svc read) ←──────────────────────────────────────────────────────+
T25 (workflow rewire) ←── T20                                                             |
T26 (NoopPersistence) ←── T19                                                             |
T27 (existing test fix) ←── T25, T26                                                      |
T28 (test base) ←── T1, T14                                                               |
T29 (persistence tests) ←── T28, T20, T21                                                 |
T30 (saga tests) ←── T25, T28                                                             |
T31 (endpoint tests) ←── T22–T24, T28                                                     |
T32–T33 (docs) ←── T20, T22, T23                                                          |
```

**Critical path**: T1 → T3–T7 → T8–T11 → T13 → T15–T18 → T20 → T25 → T29/T30

---

## Risks and Sequencing Concerns

| ID | Risk | Mitigation |
|----|------|-----------|
| R1 | `ImageObjectKind` missing from #93 | T7 creates it as new file; T9/T12/T16 depend on it |
| R2 | Self-invocation of `@Transactional` | Plan uses `TransactionTemplate` programmatically — no `@Transactional` on impl class |
| R3 | Concurrent FAILED-retry race | Accepted for workshop; `MultithreadingTester` documents behavior (T29-14) |
| R4 | Stale-job orphans (no reaper) | Monitoring query in README only (T32); no implementation |
| R5 | `NULLS NOT DISTINCT` requires PG 15+ | `PostgreSQLServer.Launcher` uses `postgres:18-alpine` — confirmed |
| R6 | `forkEvery=1` already present | T1 must NOT duplicate; each fork reconnects to shared Testcontainers PG |
| R7 | `UserContext` vs `AuditorAware` | Plan uses `UserContext.withUser("image-processing-service")` in all write paths (verified source) |
| R8 | Deterministic DIVE test design | MockK `spyk` on repository to throw on first insert call |
| R9 | Backward compat of existing tests | T26 creates `NoopImagePersistenceService`; T27 wires into test factory |
| R10 | `externalId` missing from `JobStartResult` | T12 adds `externalId: String` to all 4 subtypes |

---

## Critical Implementation Rules

1. **Exposed 1.2+**: ONLY top-level `eq`, `and`, `inList` — NEVER `SqlExpressionBuilder.eq` (ERROR-level deprecated)
2. **All saga transactions**: `REQUIRES_NEW` via `TransactionTemplate` — independent of outer context
3. **T3 catch block**: `withContext(NonCancellable + Dispatchers.IO)` + catch `Throwable` (not `Exception`)
4. **CancellationException**: Always rethrow before broad `catch(Exception)`; `runCatching {}` prohibited around suspend calls
5. **No `!!` operator**; prefer `val`; all `data class` implement `Serializable` + `serialVersionUID`
6. **`SchemaUtils.create()`**: Must NOT be wrapped in try/catch — startup DDL failure must propagate
7. **`NoopImagePersistenceService`**: In `src/test/kotlin/` ONLY — never in production source
8. **`appendEvent`**: Propagates exceptions to caller; `ImageDerivativeWorkflowService` suppresses with `log.warn` + metric counter
9. **S3_UPLOAD FAILED is terminal**: Immediately invoke T3; no more variant uploads
10. **VALIDATION event**: Emitted AFTER T1 commits (jobId is now known)
11. **`ImageProcessingEventStatus`**: `COMPLETED | FAILED | SKIPPED` — no `STARTED`
12. **Error message sanitization**: No stack traces, no VIPS FFM paths, no JDBC connection strings
13. **`PostgreSQLServer.Launcher.postgres`**: Singleton for Testcontainers — never `GenericContainer` directly
14. **`MultithreadingTester`**: From bluetape4k-junit5 for concurrent race tests
15. **HikariCP `leak-detection-threshold: 30000`** in application.yml
16. **`UserContext.withUser("image-processing-service")`**: Wrap all DB writes in service impl; not Spring `AuditorAware`
