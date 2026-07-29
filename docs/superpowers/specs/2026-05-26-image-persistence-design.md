# 이미지 처리 지속성 레이어 - 디자인 사양
**날짜**: 2026-05-26
**문제**: #94 — 이미지 처리 고급 지속성: PostgreSQL + Exposed 워크플로 metadata/history
**모듈**: `image-processing/advanced-workflow`
**빌드 기반**: Issue #93 (업로드 → VIPS → S3 → 공개 URL 워크플로)

---

## 1. 목표

지속하려면 기존 `advanced-workflow` Spring Boot 4 / Java 25 모듈을 확장하세요.
- 이미지 자산 메타데이터(`image_assets`)
- S3 파생 개체 레코드(`image_objects`)
- 처리 작업 수명 주기(`image_processing_jobs`)
- 단계 수준 이벤트 로그(`image_processing_events`)

두 개의 새로운 읽기 엔드포인트를 노출하고 기존 업로드 워크플로를 이전 버전과 호환되도록 유지합니다.

---

## 2. 설계 위험

1. **VIPS 처리 중 오랫동안 유지되는 DB 연결** — Single-transaction-wrap은 최대 `processingTimeout`(기본값 60초) 동안 HikariCP 연결을 유지하여 풀을 고갈시킵니다. 사가 패턴(3개의 짧은 트랜잭션)으로 완화됩니다.
2. **롤백 시 실패 기록 손실** — 실패한 동일한 트랜잭션 내에서 실패가 기록되면 두 레코드가 모두 롤백됩니다. `NonCancellable`에 래핑된 T3에 대한 `REQUIRES_NEW` 전파로 완화되었습니다.
3. **재시도 시 중복된 S3 개체** — 동일한 변형을 다시 업로드하면 중복된 S3 개체 레코드가 생성됩니다. `NULLS NOT DISTINCT` + `batchUpsert`를 사용하여 `(image_asset_id, kind, variant_name)`에 대한 복합 고유 제약 조건으로 완화되었습니다.
4. **`forkEvery=1` 테스트 격리** — 각 Gradle 테스트 포크는 새로운 JVM를 생성하고 컨테이너에 다시 연결합니다. Spring 컨텍스트는 포크마다 다시 시작됩니다. `PostgreSQLServer.Launcher.postgres` Testcontainers 재사용 패턴으로 완화되었습니다.
5. **`coroutines + @Transactional` 비호환성** — Spring의 `@Transactional` 프록시는 Spring MVC의 `suspend` 함수에서 작동하지 않습니다. `withContext(Dispatchers.IO)`을 통해 연결된 지속성 서비스 메서드를 차단하여 완화되었습니다.
6. **FAILED 자산 블록 재시도** — T1이 `status=FAILED`을 커밋하고 떠난 후 첫 번째 시도가 실패하는 경우 재시도는 `checksum UNIQUE` 제약 조건에 도달하고 DB 예외와 함께 충돌합니다. T1의 명시적인 재시도 복구 경로로 완화됩니다(§3.3 참조).
7. **동시 동일한 체크섬 업로드로 인해 `DataIntegrityViolationException` 발생** — 동일한 바이트를 동시에 업로드하는 2개 모두 READY 단락 검사를 통과하고 삽입 경쟁을 벌입니다. 패자는 잡히지 않는 DB 예외를 받습니다. T1에서 `DataIntegrityViolationException`을 포착하고 자산 행을 다시 읽으면 완화됩니다.
8. **NULL `image_objects`의 멱등성 구멍 — PostgreSQL 표준 `UNIQUE`은 두 개의 NULL 값을 고유한 값으로 처리하므로 `variant_name=NULL`가 있는 두 개의 ORIGINAL 행이 제약 조건을 충족하고 둘 다 삽입됩니다. UNIQUE 제약 조건에서 `NULLS NOT DISTINCT` 절(PostgreSQL 15에서 지원, `postgres:18-alpine`을 사용하여 `PostgreSQLServer.Launcher`로 확인됨)로 완화되었습니다.
9. **작업 고아(T1과 T2 사이 충돌)** — T1이 커밋되지만 프로세스는 VIPS 또는 T2 전에 종료됩니다. 작업은 `RUNNING` 영구적으로 유지됩니다. 오래된 작업 모니터링 쿼리로 완화됩니다(§3.6 참조).

---

## 3. 아키텍처 결정

### 3.1 거래 모델: Saga(4개의 짧은 거래 + 이벤트 미니 Tx)

모든 saga 트랜잭션은 `REQUIRES_NEW`입니다. 향후 가능한 외부 트랜잭션에 암시적으로 참여하지 않습니다.

| 단계 | 거래 | 전파 | 쓰여진 내용 |
|------|-------------|-------------|-----------------|
| T1(VIPS 이전) | `REQUIRES_NEW` | 무조건 | `image_assets` (상태=PROCESSING) + `image_processing_jobs` (상태=RUNNING) |
| T2 (S3 성공 후) | `REQUIRES_NEW` | 무조건 | `image_objects` 업서트, 자산 업데이트→READY, 작업 업데이트→SUCCEEDED |
| T3 (실패 시) | `REQUIRES_NEW` + `NonCancellable` | 캐치 블록 | 작업 업데이트→FAILED + 자산→FAILED |
| 이벤트 미니-tx(모든 단계) | `REQUIRES_NEW` | 이벤트별 추가 | 단일 `image_processing_events` 행; T2 또는 T3 롤백에서 살아남음 |

**T3 예외 처리 계약** (MANDATORY 구현 중):
```kotlin
// In ImageDerivativeWorkflowService catch block
// CRITICAL: throw originalException MUST execute unconditionally even if addSuppressed/log/counter throw.
// Use nested try-catch guards inside the T3 catch block:
try {
    withContext(NonCancellable + Dispatchers.IO) {
        try {
            persistence.recordJobFailure(
                identity = JobIdentity(jobId, assetId),
                reason = JobFailureReason(errorCode, sanitizedMessage),
            )
        } catch (t3Ex: Throwable) {  // Throwable (not Exception) to catch Error subclasses
            // Each subsequent call is guarded individually — during OOM, addSuppressed/log/counter
            // can themselves throw. Guard each to ensure originalException is always rethrown.
            try { originalException.addSuppressed(t3Ex) } catch (_: Throwable) {}
            try { log.error(t3Ex) { "Failed to record job failure: assetId=$assetId jobId=$jobId" } } catch (_: Throwable) {}
            try { meterRegistry.counter(METRIC_FAILURE_RECORD_FAILED).increment() } catch (_: Throwable) {}
        }
    }
} catch (outerEx: Throwable) {
    // withContext itself threw (e.g., coroutine dispatch failure during OOM)
    try { originalException.addSuppressed(outerEx) } catch (_: Throwable) {}
}
// Always rethrow the original exception after T3 completes or fails
throw originalException
```

T2(이전의 `REQUIRED`)에 대한 `REQUIRES_NEW`은 코디네이터 트랜잭션이 도입된 경우 사가 독립성을 보장합니다.

### 3.2 JDBC 패턴(R2DBC 아님)

`ImagePersistenceServiceImpl`에서 프로그래밍 방식으로 `TransactionTemplate(REQUIRES_NEW)`을 통해 JDBC 차단 메서드를 사용하고 `withContext(Dispatchers.IO)`를 통해 일시 중지 호출자와 연결됩니다. R2DBC는 별도의 JDBC 기반 DDL 이니셜라이저가 필요하고, 다른 트랜잭션 모델을 사용하며, 기존 코루틴 + VIPS + S3 디자인과 충돌하므로 제외됩니다.

> **구현 규칙**: `ImagePersistenceServiceImpl`은 클래스나 해당 메서드에 NOT 주석을 사용해야 합니다. 단일 필드 `private val txTemplate = TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }`를 선언합니다(`PlatformTransactionManager`가 삽입됨). 이를 통해 트랜잭션이 이미 롤백된 `DataIntegrityViolationException` OUTSIDE `txTemplate.execute { }`을 포착할 수 있습니다.

`CancellationException` 서비스 계층에서 처리:
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

### 3.3 멱등성 전략

**2단계 멱등성 + 재시도 복구:**

1. **자산 수준(체크섬)**:
   - `image_assets.checksum`에는 UNIQUE 제약 조건(SHA-256 원시 바이트)이 있습니다.
   - T1 `recordJobStart` 논리:
     - 체크섬으로 `image_assets`을 쿼리합니다.
     - `status=READY`인 경우: 단락 → `JobStartResult.AlreadyReady(assetId=existing.id, jobId=-1L, externalId=existing.externalId)`을 반환합니다.
     - `status=PROCESSING`인 경우: 동시 실행 또는 부실 실행이 존재 → 동일한 자산에 대해 로그 + 새 작업 생성.
     - `status=FAILED`: **복구 재시도** — 상태 업데이트→PROCESSING인 경우 새 작업 행을 생성합니다.
     - 행이 없는 경우: 새 자산을 삽입합니다.
   - 삽입 시 `DataIntegrityViolationException`인 경우(삽입 경주 손실): **OUTSIDE `TransactionTemplate.execute { }` 블록**을 포착합니다(예외가 호출자에게 전파되기 전에 트랜잭션이 이미 롤백됨). 체크섬으로 다시 읽고 상태에 따라 분기합니다.
   - **다시 읽기 후 Null 분기**(동시 삭제): 다시 읽기에서 null(삽입 실패와 다시 읽기 사이에 자산 삭제됨)을 반환하는 경우 `ImageAssetNotFoundException(checksum)`을 던지고 NOT null을 반환하고 NOT 원본 `DataIntegrityViolationException`을 삼키고 NOT 사가를 계속 진행합니다. 여기서 Null은 복구할 수 없는 별개의 조건입니다.

   > **FAILED-동시 경쟁 재시도**: 동일한 FAILED 자산에 대한 두 번의 재시도가 동시에 실행되는 경우 둘 다 `status=FAILED`를 읽고, 둘 다 업데이트→PROCESSING를 읽고, 둘 다 새 작업 행을 삽입하여 동일한 자산에 두 개의 `RUNNING` 작업이 생성됩니다. 이는 워크샵 범위에 대해 허용되는 경쟁 조건입니다. 프로덕션에서는 상태를 업데이트하기 전에 `image_assets` 행에 `SELECT … FOR UPDATE` 잠금을 완화하세요. 중복 작업 시나리오는 오래된 작업 모니터링 쿼리(§3.6)에 의해 감지되고 수동 수정 또는 멱등성 창을 통해 해결됩니다.

2. **객체 수준(복합 고유)**:
   - `image_objects`에는 `UNIQUE NULLS NOT DISTINCT (image_asset_id, kind, variant_name)`이 있습니다.
   - `NULLS NOT DISTINCT`은 ORIGINAL 행(여기서 `variant_name IS NULL`)이 동일하게 처리되도록 보장합니다.
   - `recordJobSuccess`은 `(image_asset_id, kind, variant_name)`와 함께 `batchUpsert`을 키 열로 사용합니다.
   - 재시도 시: 중복 항목을 생성하는 대신 기존 행을 업데이트합니다.

### 3.7 이벤트 수명주기 - 단계 열거형 및 작성기

`ImageProcessingEventRepository.appendEvent()`(`REQUIRES_NEW`로 호출)은 워크플로 단계당 하나의 행을 씁니다. 호출자는 항상 `ImageDerivativeWorkflowService`입니다(저장소나 매퍼가 아님).

**단계 열거형 값**(`image_processing_events.step` 열, `ImageProcessingStep` 열거형 클래스):

| 단계 값 | 방출될 때 | 상태 | 페이로드 |
|---|---|---|---|
| `VALIDATION` | T1 커밋 직후(jobId가 이제 알려짐) | `COMPLETED` | `{checksum, byteSize, originalFilename}` |
| `VIPS_PROCESSING` | VIPS이 반환된 후(계산된 파생 목록) | `COMPLETED` | `{variantCount, durationMs}` |
| `S3_UPLOAD` | 각 S3 `putObject`이 반환된 후 | `COMPLETED` 또는 `FAILED` | `{s3Key, variantName, byteSize}` |
| `JOB_COMPLETED` | T2 커밋 후 | `COMPLETED` | `{assetId, jobId, objectCount}` |
| `JOB_FAILED` | T3 커밋 후(catch 블록에서) | `FAILED` | `{errorCode, errorMessage}` |

> **참고**: `JOB_STARTED`은(는) T1 메타데이터에 병합되었습니다. 작업 이벤트 로그의 첫 번째 이벤트는 `VALIDATION`이며, T1 바로 다음에 발생하므로 `jobId`은(는) 항상 사용할 수 있습니다.
>
> **READY 단락** (`JobStartResult.AlreadyReady`): 새 작업이 생성되지 않고 이벤트가 발생하지 않습니다.
>
> **FAILED-재시도 경로** (`JobStartResult.RecoveredFromFailed`): 새 작업이 생성된 후 `status=COMPLETED`(새 업로드와 동일)와 함께 `VALIDATION` 단계 이벤트가 발생합니다.
>
> **S3_UPLOAD FAILED는 터미널입니다**: S3 `putObject` 중 하나라도 실패하면 호출자는 NOT 나머지 변형을 업로드해야 합니다. 실패한 변형에 대해 `status=FAILED`가 포함된 `S3_UPLOAD` 이벤트를 내보낸 다음 즉시 T3을 호출합니다. 부분 성공 경로(업로드된 일부 변형, SUCCEEDED로 표시된 작업)는 금지됩니다.

**이벤트 쓰기 규칙**:
- 각 단계 이벤트는 자체 `REQUIRES_NEW` 미니 트랜잭션(§3.1부터)으로 작성됩니다.
- 이벤트 쓰기 실패(mini-tx throws)는 **억제**됩니다. `log.warn(e) { "Event append failed: step=$step jobId=$jobId" }`을 통해 throwable을 기록하고 Micrometer 카운터 `image.processing.event.append.failed`를 증가시킵니다. 주요 사가 흐름은 계속되어야 하며 원래 예외(있는 경우)는 변경되지 않고 계속 전파되어야 합니다.
- `payload_json`은 `jacksonb<Map<String, Any?>>()` Exposed 열 유형을 사용합니다.
- `message`은 사람이 읽을 수 있는 자유 텍스트 요약입니다(255자 이하, 스택 추적 없음).
- `ImageProcessingEventStatus` 열거형 값: `COMPLETED | FAILED | SKIPPED`. (`STARTED` 제거됨 - 어떤 단계에서도 이를 사용하지 않습니다. `SKIPPED`는 재시도 시 이미 존재하는 S3 개체의 향후 중복 제거를 위해 예약되어 있습니다.)

### 3.4 NoopImagePersistenceService (테스트 가짜 - 테스트 소스만 해당)

`NoopImagePersistenceService`은(는) `src/test/kotlin/`에 거주합니다. — **NOT은(는) `src/main/kotlin/`**에 거주합니다. 프로덕션 소스의 무작동 가짜는 Spring 빈 스캐너 위험입니다. 가짜는 다음을 충족해야 합니다.
- `recordJobStart()`에서 `JobStartResult.NewAsset(assetId=0L, jobId=0L, externalId=UUID.randomUUID().toString())`를 반환합니다.
  (externalId은 호출별로 비결정적입니다. 가짜 테스트에 허용됩니다. T30 사가 테스트가 두 번 호출하는 경우 이를 문서화하세요.)
- 다른 모든 방법은 작동하지 않습니다(상태가 저장되지 않음).

기존 테스트에서는 이름으로 주입합니다. 기존 mocked-S3 / mocked-VIPS 테스트는 영향을 받지 않습니다.

### 3.5 오류 메시지 삭제

`error_message`은 `image_processing_jobs`에 저장됨 **다음을 포함해서는 안 됨**:
- Java/Kotlin 예외 스택 추적
- VIPS 기본 FFM 파일 시스템 경로가 포함된 오류 문자열
- 내부 JDBC 연결 문자열 또는 S3 버킷 ARN

구현 규칙: 저장을 위해 안전한 사용자 메시지(예: `"VIPS processing failed"`, `"S3 upload failed"`, `"DB error during persistence"`)를 추출합니다. 관찰 가능성을 위해 `log.error(e) { "..." }`을 통해 전체 예외를 기록합니다.

### 3.6 오래된 작업 모니터링

T1-충돌 고아는 `status=RUNNING`을 무기한 떠납니다. 모듈은 자동 수확기(워크샵 범위 밖)를 구현하지 않지만 모니터링 쿼리를 문서화해야 합니다.

```sql
SELECT * FROM image_processing_jobs
WHERE status = 'RUNNING'
  AND started_at < NOW() - INTERVAL '5 minutes';
```

이 쿼리는 "작업" 섹션의 `README.md`에 문서화되어 있습니다.

---

## 4. 데이터 모델

### 이미지_자산
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

### 이미지_객체
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

### 이미지_처리_작업
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

### 이미지_처리_이벤트
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

**FK 계단식**: 모든 하위 테이블(image_objects, image_processing_jobs, image_processing_events)의 `ON DELETE CASCADE`. 상위 행 삭제를 통해 완전한 테스트 해제를 활성화합니다.

---

## 5. 부품 설계

### 새 파일

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
    ImagePersistenceServiceImpl.kt            -- @Service, TransactionTemplate(REQUIRES_NEW) for write paths (NO @Transactional)

src/test/kotlin/.../advanced/           ← NoopImagePersistenceService HERE (not main)
  persistence/
    AbstractImagePersistenceTest.kt           -- @SpringBootTest + PostgreSQLServer.Launcher + withTestUser helper
    ImagePersistenceServiceImplTest.kt        -- all saga/idempotency/recovery integration tests
    NoopImagePersistenceService.kt            -- test fake (src/test only)
  service/
    ImageDerivativeWorkflowSagaTest.kt        -- T3-propagate + event-suppression saga scenarios (T30)
  web/
    ImageAssetEndpointTest.kt                 -- GET /images/{id} and /history integration tests
```

### `ImagePersistenceService` 인터페이스 서명

```kotlin
interface ImagePersistenceService {
    /**
     * T1: Record job start (NOT @Transactional — implementation uses TransactionTemplate(REQUIRES_NEW)
     * programmatically, so DataIntegrityViolationException can be caught OUTSIDE the
     * TransactionTemplate.execute { } block after the transaction is already rolled back).
     * Creates or recovers image_assets row + new image_processing_jobs row.
     *
     * May throw ImageAssetNotFoundException if lost-insert race is not recoverable
     * (asset deleted between insert-fail and re-read — do not return null in this case).
     *
     * Note: UserContext.withUser("image-processing-service") { ... } MUST wrap the entire method body,
     * including all TransactionTemplate.execute { } calls and recovery branches.
     */
    fun recordJobStart(
        assetMetadata: AssetMetadataInput,
        requestedVariants: List<String>,
    ): JobStartResult

    /**
     * T2: Record job success (REQUIRES_NEW).
     * Upserts image_objects rows, updates asset→READY, job→SUCCEEDED.
     */
    fun recordJobSuccess(
        identity: JobIdentity,
        objects: List<ImageObjectInput>,
    ): Unit

    /**
     * T3: Record job failure (REQUIRES_NEW + NonCancellable context in caller).
     * Updates job→FAILED + asset→FAILED.
     */
    fun recordJobFailure(
        identity: JobIdentity,
        reason: JobFailureReason,
    ): Unit

    /**
     * Event mini-tx: Append one event row (REQUIRES_NEW).
     * PROPAGATION CONTRACT: this method propagates exceptions to the caller.
     * The CALLER (ImageDerivativeWorkflowService) is responsible for suppressing,
     * logging, and metering the failure (see §3.7). Do NOT swallow inside the implementation.
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

/**
 * Input to T1 (recordJobStart). Wraps 3 adjacent String fields + nullable dimensions
 * to prevent positional mistakes at call sites.
 */
data class AssetMetadataInput(
    val checksum: String,
    val originalFilename: String,
    val contentType: String,
    val byteSize: Long,
    val dimensions: ImageDimensions?,   // null = dimensions unknown at upload time
) : Serializable {
    companion object { const val serialVersionUID = 1L }
}

/**
 * Atomic image dimensions. Invariant: if width is known, height must also be known.
 * Use `ImageDimensions?` instead of `width: Int?, height: Int?` independently.
 */
data class ImageDimensions(val width: Int, val height: Int) : Serializable {
    companion object { const val serialVersionUID = 1L }
}

/**
 * Identifies a (job, asset) pair for T2/T3 operations. Wraps adjacent Long params
 * to prevent transposition at call sites.
 */
data class JobIdentity(val jobId: Long, val assetId: Long) : Serializable {
    companion object { const val serialVersionUID = 1L }
}

/**
 * Wraps failure reason strings to prevent adjacent String transposition at T3 call sites.
 */
data class JobFailureReason(val errorCode: String, val errorMessage: String) : Serializable {
    companion object { const val serialVersionUID = 1L }
}

/**
 * Sealed result of recordJobStart. Expresses all 4 saga branch outcomes explicitly.
 * Callers use exhaustive `when` or `is` checks; the Boolean `alreadyExists` flag is avoided.
 */
sealed interface JobStartResult : Serializable {
    val assetId: Long
    val jobId: Long
    val externalId: String  // UUID v4 string; used as POST response imageId (R10)

    /** Brand-new asset inserted; normal processing. */
    data class NewAsset(
        override val assetId: Long,
        override val jobId: Long,
        override val externalId: String,
    ) : JobStartResult {
        companion object { const val serialVersionUID = 1L }
    }
    /**
     * Asset with READY status found; caller must skip processing and return cached response.
     * jobId = -1L (no new job created). externalId = existing asset's external_id.
     * NO events are emitted in this short-circuit path.
     */
    data class AlreadyReady(
        override val assetId: Long,
        override val jobId: Long = -1L,
        override val externalId: String,
    ) : JobStartResult {
        companion object { const val serialVersionUID = 1L }
    }
    /** A concurrent or stale PROCESSING run exists; new job created on same asset. */
    data class ConcurrentProcessing(
        override val assetId: Long,
        override val jobId: Long,
        override val externalId: String,
    ) : JobStartResult {
        companion object { const val serialVersionUID = 1L }
    }
    /** Previous FAILED asset recovered; status reset to PROCESSING, new job created. Emits VALIDATION event. */
    data class RecoveredFromFailed(
        override val assetId: Long,
        override val jobId: Long,
        override val externalId: String,
    ) : JobStartResult {
        companion object { const val serialVersionUID = 1L }
    }
}

data class ImageObjectInput(
    val kind: ImageObjectKind,        // NEW type created in T7 (not found in issue #93 codebase — grep-verified)
    val variantName: String?,         // null for ORIGINAL
    val s3Key: String,
    val publicUrl: String,
    val dimensions: ImageDimensions?, // null = dimensions not computed for this object
    val byteSize: Long,
    val format: String?,
) : Serializable {
    companion object { const val serialVersionUID = 1L }
}
```

**`ImageObjectKind` 소스**: 기존 `advanced-workflow` 코드 베이스에서 발견된 NOT(이슈 #93 코드에 대해 grep 검증됨). T7에서 새로운 유형으로 생성되어야 합니다. 계획 R1에는 이러한 불일치가 기록되어 있습니다.

**`UserContext` 감사 열**: `AuditableIdTable`(및 그 파생어 `AuditableLongIdTable`)는 `clientDefault` 표현식에서 호출된 `UserContext.getCurrentUser()`를 통해 `created_by`/`updated_by` 열을 채웁니다 — NOT는 Spring Data의 `AuditorAware` 메커니즘을 통해 이루어집니다. `image_assets` 또는 `image_objects` MUST를 터치하는 모든 쓰기 작업은 `UserContext.withUser("image-processing-service") { ... }`에 래핑됩니다. 래퍼 범위 MUST는 전체 메서드 본문(`TransactionTemplate.execute {}` 블록 및 복구 분기 포함)을 포함합니다. NOT `AuditorAware<String>` Spring 빈을 선언하세요.

**상태 열거 설명**(설계상 — 의도적 분리):
- `ImageAssetStatus` (`image_assets.status`): `PROCESSING | READY | FAILED`
- `ImageJobStatus` (`image_processing_jobs.status`): `RUNNING | SUCCEEDED | FAILED`
- `ImageProcessingEventStatus` (`image_processing_events.status`): `COMPLETED | FAILED | SKIPPED` (`STARTED` 제거됨 - 어떤 단계에서도 사용하지 않음)

`event.status` 열거형은 이벤트가 진행률 표시기(*단계*에 대해 `STARTED`, `COMPLETED` 또는 `FAILED`일 수 있음)인 반면, asset/job 상태는 전체 수명 주기 상태를 추적하므로 구별됩니다.

### 수정된 파일

- `build.gradle.kts` — Exposed + PostgreSQL + Testcontainers 종속성 추가
- `src/main/resources/application.yml` — `spring.datasource` + HikariCP 문_시간 초과 추가
- `service/ImageDerivativeWorkflowService.kt` — `ImagePersistenceService` 삽입, 체크섬 추가, 사가 호출
- `web/ImageDerivativesController.kt` — GET 엔드포인트 추가
- `web/ImageProcessingExceptionHandler.kt` — `NoSuchElementException`에 대한 404 핸들러를 추가합니다.
- `service/ImageDerivativeWorkflowServiceTest.kt` — `NoopImagePersistenceService` 삽입(테스트 소스 경로)

---

## 6. API 사양

### GET /api/images/{externalId}
- **응답 200**: `ImageAssetDetailResponse`
  ```json
  {
    "asset": { "externalId": "uuid-v4", "status": "READY", ... },
    "original": { "kind": "ORIGINAL", "publicUrl": "https://...", ... },
    "variants": [{ "kind": "VARIANT", "variantName": "thumb", "publicUrl": "https://..." }]
  }
  ```
- **응답 404**: `ProblemDetail` (`NoSuchElementException` → 예외 처리기를 통한 404)

### GET /api/images/{externalId}/history
- **응답 200**: `ImageAssetHistoryResponse`
  ```json
  {
    "asset": { ... },
    "jobs": [{ "job": { "status": "SUCCEEDED", "durationMs": 1234, ... }, "events": [...] }]
  }
  ```
- **응답 404**: `ProblemDetail`

---

## 7. 보안 가정

이 모듈은 신뢰할 수 있는 로컬 컨텍스트에서 실행되는 **workshop/demo**입니다. 다음 보안 제한은 의도된 것입니다.

1. **GET 엔드포인트에는 인증 가드가 없습니다** — `externalId`을 아는 모든 호출자는 자산 메타데이터를 검색할 수 있습니다. 프로덕션 조정에는 전달자 토큰 검증 및 소유자 범위 쿼리를 추가해야 합니다.
2. **글로벌 체크섬 중복 제거** — 사용자별 범위 지정이 없습니다. 동일한 바이트를 업로드하는 두 사용자는 동일한 `image_assets` 행과 `externalId`을 공유합니다. single-user/trusted 데모에 적합합니다. 프로덕션 범위는 `UNIQUE`에서 `(checksum, owner_id)`이어야 합니다.
3. **UUID v4 필요** — `external_id`은 `UUID.randomUUID()`로 채워집니다. v1/v3/v5 UUID는 부분적으로 예측 가능하므로 대체하지 마세요.
4. **오류 메시지 정리가 필요합니다** — DB에 저장된 `error_message`은(는) 안전한 사용자 문자열입니다. 전체 예외 세부사항은 기록만 됩니다.

---

## 8. 구성

### application.yml 추가

**풀 규모 분석**:
- 각 업로드 요청은 최대 다음을 사용합니다. T1(1개 연결) + 최대 6개의 이벤트 미니 tx(각각 1개, 순차) + T2(1개 연결) = 직렬 사용, 요청당 언제든지 최대 1개의 연결 유지.
- 동시 테스트 포크: `forkEvery=1`은 격리된 JVM을 생성합니다. Testcontainers 컨테이너는 재사용을 통해 공유되지만 각 포크는 자체 HikariCP 풀(`maximum-pool-size: 10`)을 갖습니다. 단일 포크 동시 테스트 병렬 처리는 `junit.jupiter.execution.parallel.config.fixed.parallelism`로 제한됩니다(일반적으로 직렬 테스트 모드의 경우 `TestMutexService`당 1개).
- **워크숍 결론**: `maximum-pool-size: 10`은 단일 사용자 직렬 테스트에 충분합니다. 문서화된 가정. 연결 누출 감지를 위해 `leak-detection-threshold: 30000`이 추가되었습니다.

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

### DatabaseInitializer 요구사항
`ImagePersistenceDatabaseInitializer.run()`은 **NOT** `SchemaUtils.create()`에서 예외를 잡아야 합니다. DDL 초기화에 실패하면 애플리케이션을 시작하면 안 됩니다. 무작동 `try/catch`으로 포장하는 것은 금지되어 있습니다.

---

## 9. 사용된 Bluetape4k 기능

| 기능 | Module/Artifact | 사용법 | 혜택 |
|---------|-----------------|-------|---------|
| `AuditableLongIdTable` | `bluetape4k-exposed-core` | `ImageAssetTable`, `ImageObjectTable` | 여유 `createdAt`/`updatedAt`/`createdBy` 열 |
| `LongAuditableJdbcRepository` | `bluetape4k-exposed-jdbc` | `ImageAssetRepository`, `ImageObjectRepository` | 무료 CRUD + `auditedUpdateById` + `findPage` |
| `LongJdbcRepository` | `bluetape4k-exposed-jdbc` | `ImageProcessingJobRepository`, `ImageProcessingEventRepository` | 감사되지 않은 테이블에 대한 경량 저장소 |
| `jacksonb<T>()` | `bluetape4k-exposed-jackson3` | `requestedVariants`, `payloadJson` 열 | 매뉴얼이 없는 유형 안전 JSONB ser/deser |
| `ExposedPage<T>` | `bluetape4k-exposed-core` | `findPage()` 저장소 | 사용자 정의 구현 없이 페이지 매김 |
| `PostgreSQLServer.Launcher` | `bluetape4k-testcontainers` | 통합 테스트 | 싱글톤 컨테이너, `@Testcontainers` 필요 없음 |
| `KLogging` | `bluetape4k-logging` | 모든 새로운 service/repository 클래스 | 구조화된 로깅 |
| `bluetape4k-assertions` | `bluetape4k-assertions` | 모든 테스트 | 바닐라보다 더 풍부한 검증문 JUnit 5 |
| `requireNotBlank` | `bluetape4k-core` | 서비스 입력 유효성 검사 | 관용적 인수 유효성 검사 |

---

## 10. 테스트 시나리오(필수)

각 DoD 기준에는 명명된 통합 테스트가 하나 이상 있어야 합니다.

| # | 테스트 시나리오 | 테스트 수업 | 검증문 |
|---|--------------|-----------|-----------|
| T1 | 성공적인 업로드: T1 커밋 BEFORE VIPS 실행 | `ImagePersistenceServiceImplTest` | `recordJobStart()` 다음에 DB 쿼리: `image_assets.status=PROCESSING`, `image_processing_jobs.status=RUNNING` |
| T2 | 성공적인 업로드: T2는 READY + SUCCEEDED + image_objects | `ImagePersistenceServiceImplTest` | `recordJobSuccess()` 이후: 자산=READY, 작업=SUCCEEDED, `image_objects` 개수 = 변형+1 |
| T3-상태 | 업로드 실패: T3가 FAILED 상태 쓰기 | `ImagePersistenceServiceImplTest` | `recordJobFailure()` 이후: 작업=FAILED, 자산=FAILED |
| T3-필드 | 업로드 실패: error_code + error_message가 null이 아닙니다 | `ImagePersistenceServiceImplTest` | `job.errorCode` 및 `job.errorMessage`은(는) null이거나 비어 있지 않습니다. |
| T3-전파 | T3 DB 실패: 원래 예외가 계속 전파됩니다 | `ImageDerivativeWorkflowSagaTest` | 스파이 `ImagePersistenceService.recordJobFailure()` 투척; VIPS 모의 실패로 업로드 트리거 → 잡힌 예외 검증문이 원래 VIPS 예외입니다. `suppressed[0]`는 T3 예외입니다 |
| 멱등성 체크섬 | 동일한 체크섬 재시도 → 중복된 image_objects 없음 | `ImagePersistenceServiceImplTest` | 동일한 바이트를 두 번 업로드 → `COUNT(image_objects WHERE image_asset_id=X)` = 예상 변형+1 |
| 멱등성-단락 회로 | 동일한 체크섬 + READY → 재처리 없음 | `ImagePersistenceServiceImplTest` | `recordJobStart()`은 두 번째 호출에서 `JobStartResult.AlreadyReady`를 반환합니다. |
| FAILED-재시도 | FAILED 자산 + 동일한 체크섬 → 재시도 성공 | `ImagePersistenceServiceImplTest` | FAILED 자산 시드 → `recordJobStart()` 호출 → 자산 상태=PROCESSING, 새 작업 생성 |
| GET-자산 | GET /images/{id}는 DB의 데이터를 반환합니다 | `ImageAssetEndpointTest` | 지속성 서비스를 통해 데이터 시드 → 엔드포인트 호출 → URL이 DB 행과 일치하는지 확인 |
| GET-404 | GET /images/{unknown-id} → 404 ProblemDetail | `ImageAssetEndpointTest` | 응답 상태 404, Content-Type `application/problem+json` |
| GET-역사 | GET /history는 작업 + 이벤트를 반환합니다 | `ImageAssetEndpointTest` | 응답 작업 목록은 비어 있지 않습니다. 첫 번째 작업에는 단계 이름이 포함된 이벤트가 있습니다. |
| DB-초기화 | DatabaseInitializer은 4개의 테이블을 모두 생성 | `ImagePersistenceServiceImplTest` | Spring 컨텍스트 시작 시 `SELECT COUNT(*) FROM ...` |을 통해 쿼리 가능한 4개 테이블 모두 |
| GET-자산 실패 | GET /images/{id} 자산 상태가FAILED이고 image_objects가 없는 경우 | `ImageAssetEndpointTest` | 상태=FAILED인 시드 자산, `original=null`, `variants=[]`(또는 문서화된 4xx)를 사용하여 엔드포인트 호출 → 200 - NPE을 발생시키지 않아야 함 |
| 멱등성-null-원본 | NULLS NOT DISTINCT 제약 조건에 의해 중복 제거된 ORIGINAL 행 | `ImagePersistenceServiceImplTest` | 동일한 `assetId` 및 ORIGINAL 객체를 사용하여 `recordJobSuccess()`을 두 번 호출합니다. `image_objects`에서 `kind=ORIGINAL` 및 `variant_name IS NULL`를 사용하여 정확히 1개의 행을 검증문 |
| T3-위생처리 | error_message에는 스택 추적이 없습니다 | `ImagePersistenceServiceImplTest` | 예외 파생 메시지가 있는 `recordJobFailure()` 뒤: `job.errorMessage`에 개행 문자(`\n`), `"at io."` 또는 `"Exception"`가 ​​포함되어 있지 않습니다. |
| 이벤트 성공 경로 | 성공적인 업로드는 4단계 이벤트를 모두 순서대로 내보냅니다 | `ImageDerivativeWorkflowSagaTest` | 전체 사가 이후: `VALIDATION→VIPS_PROCESSING→S3_UPLOAD(×N)→JOB_COMPLETED`에 대한 `image_processing_events` 행, `created_at`순으로 정렬; 각각은 null이 아닌 `step`, `status=COMPLETED`를 가집니다. 총 행 수 = 3 + N(여기서 N = 변형 수 + 원본의 경우 1) 예: 변형 2개 → 이벤트 3+3=6개. |
| 이벤트 실패 경로 | 업로드 실패 시 `JOB_FAILED` 이벤트 발생 | `ImageDerivativeWorkflowSagaTest` | T3 뒤: `step=JOB_FAILED`, `status=FAILED`, null이 아닌 `message`가 ​​있는 `image_processing_events` 행 1개 |
| 이벤트-미니-tx-억제 | 이벤트 mini-tx 발생 → 주요 흐름은 영향을 받지 않음 | `ImageDerivativeWorkflowSagaTest` | `@SpykBean` `ImagePersistenceService` 스텁 `appendEvent` 던지기; full `processUpload()` 호출 → 예외가 전파되지 않음; 업로드 응답이 유효합니다. Micrometer 카운터 `image.processing.event.append.failed` 증가 |
| 동시 체크섬 경주 결정적 | DIVE 잡아서 다시 읽는 경로가 정확함 | `ImagePersistenceServiceImplTest` | `DataIntegrityViolationException`을 던지려면 먼저 INSERT를 모의하세요. verify `recordJobStart()`는 체크섬으로 다시 읽고 DIVE |
| 동시 체크섬 경주 확률 | 2개 동시 업로드 → 정상(확률적) | `ImagePersistenceServiceImplTest` | 동일한 체크섬으로 `recordJobStart()`를 호출하는 2개의 스레드가 있는 `MultithreadingTester`(bluetape4k-junit5)를 사용하세요. 예외가 발생하지 않는다고 검증문합니다. `SELECT COUNT(*) FROM image_assets WHERE checksum=X` = 1. **참고**: 확률적; 문서 작업장 규모의 신뢰만 |
| 동시 체크섬-DIVE-null-branch | 삽입 시 DIVE + findByChecksum가 null을 반환 → ImageAssetNotFoundException | `ImagePersistenceServiceImplTest` | MockK `spyk` `ImageAssetRepository`; 먼저 `insertAsset`이 `DataIntegrityViolationException`를 던졌습니다. `findByChecksum`은 null을 반환합니다. 체크섬 값으로 `assertFailsWith<ImageAssetNotFoundException>`을 검증문 |
| 계단식 삭제 | ON DELETE CASCADE는 image_assets에서 모든 하위 테이블로 전파됩니다. `ImagePersistenceServiceImplTest` | 전체 T1+T2 주기 후: `DELETE FROM image_assets WHERE id=?`; 검증문 `COUNT(*) FROM image_objects WHERE image_asset_id=?` = 0; `COUNT(*) FROM image_processing_jobs WHERE image_asset_id=?` = 0 |
| POST-imageId-같음-externalId | POST 응답 imageId은 지속되는 external_id와 같습니다 | `ImagePersistenceServiceImplTest` | `recordJobStart()` 다음에 `result.externalId == SELECT external_id FROM image_assets WHERE checksum=?`를 검증문하세요. 전체 엔드포인트 주기 후 HTTP 응답 `imageId` 필드는 동일 `external_id` |
| UserContext-감사 | created_by/updated_by는 UserContext에서 채워짐 | `ImagePersistenceServiceImplTest` | `recordJobStart()`을 `UserContext.withUser("test-user") { }`로 묶음 → `image_assets` 및 `image_processing_jobs` 행 모두에서 `created_by="test-user"`을 검증문 |
| S3-업로드-FAILED-종점 | S3 업로드 실패 시 Saga가 즉시 종료됩니다 | `ImageDerivativeWorkflowSagaTest` | 첫 번째 호출에서 실패하도록 S3 `putObject`을 모의합니다. 즉시 호출되는 T3을 검증문하십시오. 남은 변형 업로드 NOT 시도; 작업 상태 확인=FAILED; S3_UPLOAD 상태=FAILED인 이벤트가 존재합니다 |
| 오류는 오류 하위 클래스 | T3 catch 블록이 오류를 포착합니다(예외뿐만 아니라) | `ImageDerivativeWorkflowSagaTest` | VIPS 단계에서 `OutOfMemoryError`을 던지십시오. T3 `catch(Throwable)`가 그것을 잡는다고 검증문하세요; FAILED로 기록된 작업; 원본 `OutOfMemoryError`이 사가에서 다시 발생합니다 |
| ConcurrentProcessing-경로 | 기존 PROCESSING 자산 → 두 번째 업로드로 동일한 자산에 새 작업 생성 | `ImagePersistenceServiceImplTest` | `withTestUser { }`를 통해 `status=PROCESSING`이 있는 시드 자산; 동일한 체크섬으로 `recordJobStart()`을 호출합니다. `assertIs<JobStartResult.ConcurrentProcessing>`; 새로운 `RUNNING` 작업이 동일한 `assetId`에 존재한다고 검증문합니다. 원본 PROCESSING 자산은 변경되지 않음 |

---

## 11. 합격기준(DoD)

- [ ] `POST /api/images/derivatives` 응답에는 `image_assets`에 유지되는 `imageId`이 포함됩니다.
- [ ] `GET /api/images/{imageId}`은 PostgreSQL에서 다시 로드된 자산 메타데이터 및 파생 URL을 반환합니다.
- [ ] `GET /api/images/{imageId}/history`은 이벤트와 함께 완료된 작업을 하나 이상 반환합니다.
- [ ] 처리가 실패하면 `status=FAILED` + `error_code`/`error_message`을 사용하여 쿼리 가능한 `image_processing_jobs` 행이 생성됩니다.
- [ ] 재시도(동일한 체크섬)는 멱등적입니다. 중복된 `image_objects` 행이 없습니다.
- [ ] FAILED 자산 + 동일한 체크섬 재시도 성공(`DataIntegrityViolationException` 없음)
- [ ] 동시 동일 체크섬 업로드가 정상적으로 처리됨(처리되지 않은 DB 예외 없음)
- [ ] 모든 기존 테스트(`ImageDerivativeWorkflowServiceTest`, 단위 테스트)는 PostgreSQL 없이 통과됩니다.
- [ ] 통합 테스트는 §10의 28개 시나리오를 모두 다룹니다(이벤트 수명 주기, 경합, ConcurrentProcessing 경로, 삭제, DIVE null 분기, 계단식 삭제, UserContext 감사 및 GET 실패 시나리오 포함).
- [ ] `POST /api/images/derivatives` 응답 `imageId`은 `image_assets`에 지속된 `external_id`와 같습니다(새로 생성된 UUID 아님).
- [ ] `README.md` 및 `README.ko.md`에는 ERD + 업데이트된 지속성 시퀀스 다이어그램이 포함됩니다.
- [ ] `README.md`에는 "사용된 Bluetape4k 기능" 테이블이 포함되어 있습니다.
- [ ] `README.md`에는 작업 섹션에 오래된 작업 모니터링 쿼리가 포함되어 있습니다.
- [ ] 모든 공개 API에는 영어 KDoc이 있습니다.
- [ ] DB의 `error_message`에는 스택 추적이나 내부 경로가 없습니다.

---

## 12. 제약

- Exposed 1.2+ 연산자: 최상위 `eq`, `and`, `inList` — NOT `SqlExpressionBuilder.eq`
- `!!` 연산자가 없습니다. `val`을 선호합니다; 모두 `data class` 구현 `Serializable` + `serialVersionUID`
- 일시 중지 컨텍스트에서 모든 차단 Exposed JDBC 호출에 대한 `withContext(Dispatchers.IO)`
- 모든 Saga 트랜잭션은 `REQUIRES_NEW`(`REQUIRED` 아님)을 사용합니다.
- `CancellationException`은 광범위한 `catch(Exception)` 앞에 다시 던져야 합니다. `runCatching {}` 통화 중단 시 금지
- `NoopImagePersistenceService` `src/test/kotlin/`에만 — 프로덕션 소스에는 없음
- `SchemaUtils.create()` 실패가 전파되어 애플리케이션 시작을 방해해야 합니다.
- `created_by`/`updated_by` `"image-processing-service"`(고정 ID)로 채워진 감사 열
- PostgreSQL `image_objects` 고유 제약 조건의 `NULLS NOT DISTINCT`에는 15개 이상이 필요합니다. `postgres:18-alpine`를 사용하여 `PostgreSQLServer.Launcher`를 통해 확인됨
- ktlint 후크 없음
- 아니요 Flyway/Liquibase — `SchemaUtils.create()` (워크샵 컨벤션)
- `forkEvery=1`은 VIPS FFM 격리를 위해 빌드 구성에 남아 있습니다.

---

## 부록: 반복 로그 검토

| 라운드 | 건축가 P0/P1 | 조용한 실패 P0/P1 | 테스트 P0/P1 | 활자 디자인 P0/P1 | 코덱스 P0/P2 | 6계층 어드바이저 P0/P1 | P0/P1 적용됨 | 커밋 |
|-------|----------------|---------------------|-----------|-------------------|-------------|----------------------|---------------|--------|
| 1 | 3 HIGH / 4 MED | 4 HIGH / 4 MED | 8 HIGH / 6 MED | — | — | — | spec rev | 헌신 |
| 2 (어드바이저) | — | — | — | — | — | P0=0, P1=5(Tier3+5+6) | §3.7 이벤트 수명주기, 인터페이스 서명, 풀 분석, 4개의 새로운 §10 시나리오 | 헌신 |
| 2(코덱스) | — | — | — | — | P0=0, P2=2 | — | VALIDATION T1(jobId 사용 가능) 이후 STARTED 열거형 제거, 성공 경로 어설션 수정 | 헌신 |
| 2 (2라운드) | 0/3 | 0/2(널 분기 고정) | 0/6 | **P0=1**/3 | — | — | AssetMetadataInput, JobIdentity, JobFailureReason, 봉인됨 JobStartResult, ImageDimensions, AuditorAware config, T3 Throwable, S3_UPLOAD FAILED 터미널, 4개의 새로운 시나리오, 2개의 테스트 클래스 재배치, 중복 제거 YAML | 헌신 |
| 3(Round-3 이전 수정) | — | — | — | — | — | — | T8 상태→enumerationByName, §10 +7 시나리오(총 27개), §11 DoD POST imageId 기준, 계획 T25 종속성+T21 확인, T28→T2 dep 확인 | 7b92c310 |
| 3(3라운드 전체) | 0/2 | **P0=1**/4 | **P0=1**/4 | 0/3 | **P0=0**/P1=0 | 0/4 | T3 발생 보장 패턴(P0), T29-7 델타 어설션(P0), T10/T11 enumerationByName, MessageDigest 스레드 안전성, txTemplate Null 처리, CancellationException 이벤트 가드, AlreadyReady Exhaustive-When+field-map, 유효성 검사 순서 지정, ConcurrentProcessing 시나리오(T29-17), T30-5 S3 확인, 테스트 클래스 불일치 수정(§10 테이블), T30 T31 dep 제거, §10 28 시나리오, T30 MockK 전략 | HEAD |
