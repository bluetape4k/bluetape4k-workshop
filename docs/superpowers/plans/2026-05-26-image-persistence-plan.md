# 이미지 처리 지속성 계층 - 구현 계획

**날짜**: 2026-05-26
**사양**: `docs/superpowers/specs/2026-05-26-image-persistence-design.md`
**문제**: #94
**모듈**: `image-processing/advanced-workflow`

---

## 1단계 — 기초(14개 작업)

### T1 — build.gradle.kts에 Exposed + PostgreSQL + Testcontainers 종속성을 추가합니다.
- **파일**: `image-processing/advanced-workflow/build.gradle.kts`
- **복잡성**: 낮음
- **종속성**: 없음
- **세부**:
  - `implementation(libs.exposed.core)`, `implementation(libs.exposed.jdbc)` 추가,
    `implementation(libs.exposed.jackson3)`, `implementation(libs.jetbrains.exposed.java.time)`,
    `implementation(libs.jetbrains.exposed.spring.boot4.starter)`,
    `implementation(libs.jetbrains.exposed.spring7.transaction)`,
    `implementation(libs.hikaricp)`, `runtimeOnly(libs.postgresql.driver)`
  - 테스트 추가: `testImplementation(libs.bluetape4k.testcontainers)`,
    `testImplementation(libs.testcontainers.postgresql)`
  - NOT `forkEvery`을 터치하세요. 이미 `1`로 설정되어 있습니다.
  - NOT`testImplementation.extendsFrom`을 터치하세요 — 이미 구성되어 있습니다
- **수락 기준**: `./gradlew :image-processing-advanced-workflow:dependencies`은 충돌 없이 모든 새 아티팩트를 해결합니다.

### T2 — spring.datasource + HikariCP 구성을 application.yml에 추가합니다.
- **파일**: `image-processing/advanced-workflow/src/main/resources/application.yml`
- **복잡성**: 낮음
- **종속성**: 없음
- **세부**:
  - `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password`를 추가합니다.
    `spring.datasource.driver-class-name: org.postgresql.Driver`
  - `spring.datasource.hikari.connection-init-sql: "SET statement_timeout='10000'"`을 추가합니다.
    `maximum-pool-size: 10`, `connection-timeout: 30000`, `leak-detection-threshold: 30000`
- **승인 기준**: YAML은 오류 없이 구문 분석됩니다. Spring Boot은 시작 시 `DataSourceProperties`을 바인딩합니다.

### T3 — ImageAssetStatus 열거형 생성
- **파일**: `src/main/kotlin/.../advanced/persistence/schema/ImageAssetStatus.kt`
- **복잡성**: 낮음
- **종속성**: 없음
- **세부정보**: `enum class ImageAssetStatus { PROCESSING, READY, FAILED }`
- **승인 기준**: 컴파일; `ImageAssetTable.status` 열에서 사용됨

### T4 — ImageJobStatus 열거형 생성
- **파일**: `src/main/kotlin/.../advanced/persistence/schema/ImageJobStatus.kt`
- **복잡성**: 낮음
- **종속성**: 없음
- **세부정보**: `enum class ImageJobStatus { RUNNING, SUCCEEDED, FAILED }`
- **승인 기준**: 컴파일; `ImageProcessingJobTable.status` 열에서 사용됨

### T5 — ImageProcessingStep 열거형 생성
- **파일**: `src/main/kotlin/.../advanced/persistence/schema/ImageProcessingStep.kt`
- **복잡성**: 낮음
- **종속성**: 없음
- **세부정보**: `enum class ImageProcessingStep { VALIDATION, VIPS_PROCESSING, S3_UPLOAD, JOB_COMPLETED, JOB_FAILED }`
- **승인 기준**: 컴파일; 사양 §3.7 단계 테이블과 일치합니다. 아니요 `JOB_STARTED`

### T6 — ImageProcessingEventStatus 열거형 생성
- **파일**: `src/main/kotlin/.../advanced/persistence/schema/ImageProcessingEventStatus.kt`
- **복잡성**: 낮음
- **종속성**: 없음
- **세부정보**: `enum class ImageProcessingEventStatus { COMPLETED, FAILED, SKIPPED }` — 아니요 `STARTED`
- **승인 기준**: 컴파일; `ImageAssetStatus` 및 `ImageJobStatus`과 구별됨

### T7 — ImageObjectKind 열거형 생성
- **파일**: `src/main/kotlin/.../advanced/model/ImageObjectKind.kt`
- **복잡성**: 낮음
- **종속성**: 없음
- **세부정보**: `enum class ImageObjectKind { ORIGINAL, VARIANT }`
- **참고**: 사양에서는 이것이 이슈 #93에서 존재하지만 코드베이스(grep-verified)에는 ​​NOT가 존재한다고 검증합니다. 생성되어야 합니다.
- **승인 기준**: 컴파일; `ImageObjectTable.kind` 및 `ImageObjectInput.kind`에서 참조됨

### T8 — ImageAssetTable 생성(Exposed AuditableLongIdTable)
- **파일**: `src/main/kotlin/.../advanced/persistence/schema/ImageAssetTable.kt`
- **복잡성**: 낮음
- **종속성**: T3
- **세부**:
  - `AuditableLongIdTable("image_assets")` 확장
  - 열: `externalId`(varchar 36, uniqueIndex), `originalFilename`(varchar 255, null 허용),
    `contentType`(varchar 100, null 허용), `byteSize`(long, null 허용),
    `width`(정수, null 허용), `height`(정수, null 허용),
    `checksum`(varchar 64, uniqueIndex),
    `status`(`enumerationByName<ImageAssetStatus>("status", 20)`, 기본값 `ImageAssetStatus.PROCESSING`)
  - `AuditableIdTable`에서 `createdBy`, `createdAt`, `updatedBy`, `updatedAt`을 상속합니다.
  - 최상위 Exposed 1.2+ 연산자만 사용 — NEVER `SqlExpressionBuilder.eq`
- **허용 기준**: 개체 컴파일; 열 유형이 사양과 일치합니다. §4 DDL; `status`은 유형이 안전한 열거형 열입니다(원시 varchar 아님).

### T9 — ImageObjectTable 생성(Exposed AuditableLongIdTable)
- **파일**: `src/main/kotlin/.../advanced/persistence/schema/ImageObjectTable.kt`
- **복잡성**: 중간
- **종속성**: T7, T8
- **세부**:
  - `AuditableLongIdTable("image_objects")` 확장
  - 열: `imageAssetId`(`ImageAssetTable`, `ON DELETE CASCADE` 참조),
    `kind`(`enumerationByName<ImageObjectKind>("kind", 20, ImageObjectKind::class)`),
    `variantName`(varchar 100, null 허용),
    `s3Key`(varchar 512), `publicUrl`(텍스트), `width`(정수, null 허용),
    `height`(정수, null 허용), `byteSize`(long, null 허용), `format`(varchar 20, null 허용)
  - `(imageAssetId, kind, variantName)`의 `UNIQUE NULLS NOT DISTINCT` 복합 인덱스 — DatabaseInitializer(T14)의 원시 SQL을 통해 Exposed DSL이 기본적으로 `NULLS NOT DISTINCT`를 지원하지 않기 때문
  - **NOT `(imageAssetId, kind, variantName)`에 Exposed `uniqueIndex()` 또는 `index()`을 추가하세요** — T14는 원시 SQL를 통해서만 이를 처리합니다. FK 조회를 위해 `imageAssetId`에 `index()`만 추가합니다.
- **허용 기준**: 개체 컴파일; FK 캐스케이드가 선언되었습니다. NULLS NOT DISTINCT는 초기화 프로그램을 통해 처리됩니다. 3열 조합에는 Exposed uniqueIndex가 없습니다.

### T10 — ImageProcessingJobTable 생성(Exposed LongIdTable)
- **파일**: `src/main/kotlin/.../advanced/persistence/schema/ImageProcessingJobTable.kt`
- **복잡성**: 낮음
- **종속성**: T4, T8
- **세부**:
  - `LongIdTable("image_processing_jobs")` 확장 — NOT 감사 가능(사양별 명시적 타임스탬프)
  - 열: `imageAssetId`(참조, `ON DELETE CASCADE`),
    `status`(`enumerationByName<ImageJobStatus>("status", 20)`, 기본값 `ImageJobStatus.RUNNING`),
    `requestedVariants`(`jacksonb<List<String>>()`),
    `startedAt`(타임스탬프, defaultExpression CurrentTimestamp),
    `finishedAt`(타임스탬프, Null 허용), `durationMs`(긴, Null 허용),
    `errorCode`(varchar 100, null 허용), `errorMessage`(텍스트, null 허용)
  - `imageAssetId`의 인덱스
- **허용 기준**: 개체 컴파일; bluetape4k-exposed-jackson3의 `jacksonb<List<String>>()`을 사용합니다. `status`은 유형이 안전한 열거형 열입니다(원시 varchar 아님).

### T11 — ImageProcessingEventTable 생성(Exposed LongIdTable)
- **파일**: `src/main/kotlin/.../advanced/persistence/schema/ImageProcessingEventTable.kt`
- **복잡성**: 낮음
- **종속성**: T5, T6, T10
- **세부**:
  - `LongIdTable("image_processing_events")` 확장
  - 열: `jobId`(`ImageProcessingJobTable`, `ON DELETE CASCADE` 참조),
    `step`(`enumerationByName<ImageProcessingStep>("step", 100)`),
    `status`(`enumerationByName<ImageProcessingEventStatus>("status", 20)`),
    `message`(텍스트, null 가능),
    `payloadJson`(`jacksonb<Map<String, Any?>>()`, null 가능),
    `createdAt`(타임스탬프, defaultExpression CurrentTimestamp)
  - `jobId`의 인덱스
- **허용 기준**: 개체 컴파일; `payloadJson`은 `jacksonb` 열 유형을 사용합니다. `step` 및 `status`은 유형이 안전한 열거형 열입니다(원시 varchar 아님).

### T12 — ImagePersistenceModels.kt 생성(DTO + 봉인된 JobStartResult)
- **파일**: `src/main/kotlin/.../advanced/model/ImagePersistenceModels.kt`
- **복잡성**: 낮음
- **종속성**: T7, T3, T4, T5, T6
- **세부**:
  - 데이터 클래스: `AssetMetadataInput`, `ImageDimensions`, `JobIdentity`, `JobFailureReason`,
    `ImageObjectInput` — 모두 `Serializable` + `serialVersionUID` 포함
  - `JobFailureReason.init` MUST 유효성 검사: `require(errorCode.isNotBlank())` 및 `require(errorCode.length <= 100)`(`VARCHAR(100)` 열에 매핑, 너무 큰 값은 NonCancellable T3 블록 내부에 발생)
  - 4개의 하위 유형이 있는 `sealed interface JobStartResult`: `NewAsset`, `AlreadyReady`,
    `ConcurrentProcessing`, `RecoveredFromFailed` — 모두 `assetId: Long`, `jobId: Long`, `externalId: String`를 전달합니다.
    (externalId 사양 차이에 따라 추가됨 R10 — POST 응답 `imageId`은 지속되는 `external_id`과 일치해야 함)
  - `AlreadyReady.jobId`의 기본값은 `-1L`입니다(단락 경로에 새 작업이 생성되지 않음).
  - 응답 DTO: `ImageAssetDTO`, `ImageObjectDTO`, `ImageProcessingJobDTO`,
    `ImageProcessingEventDTO`, `ImageAssetDetailResponse`, `ImageAssetHistoryResponse`,
    `ImageJobWithEventsDTO`
  - `ImageAssetNotFoundException(val checksum: String)` 사용자 정의 예외
- **수용 기준**: 모든 데이터 클래스가 컴파일됩니다. `JobStartResult` 하위 유형은 `when`에서 완전합니다. `externalId`는 4가지 하위 유형 모두에 존재합니다. `JobFailureReason` 초기화 블록 유효성 검사 길이 ≤ 100

### T13 — ImagePersistenceMappers.kt(ResultRow 확장 기능) 생성
- **파일**: `src/main/kotlin/.../advanced/persistence/mapper/ImagePersistenceMappers.kt`
- **복잡성**: 낮음
- **종속성**: T8, T9, T10, T11, T12
- **세부**:
  - `ResultRow.toImageAssetDTO()`: `ImageAssetTable` 행 → `ImageAssetDTO` 매핑
  - `ResultRow.toImageObjectDTO()`: `ImageObjectTable` 행 → `ImageObjectDTO` 매핑
  - `ResultRow.toImageProcessingJobDTO()`: `ImageProcessingJobTable` 행 → `ImageProcessingJobDTO` 매핑
  - `ResultRow.toImageProcessingEventDTO()`: `ImageProcessingEventTable` 행 → `ImageProcessingEventDTO` 매핑
  - 최상위 Exposed 1.2+ 연산자를 사용하세요. 절대로 `SqlExpressionBuilder.eq`
- **허용 기준**: 각 매퍼 함수가 컴파일됩니다. 널 입력 가능 열을 포함한 모든 열을 매핑합니다.

### T14 — ImagePersistenceDatabaseInitializer 생성
- **파일**: `src/main/kotlin/.../advanced/persistence/config/ImagePersistenceDatabaseInitializer.kt`
- **복잡성**: 중간
- **종속성**: T8, T9, T10, T11
- **세부**:
  - `ApplicationRunner` 구현; `@Component`
  - `run()` 메소드의 `@Transactional`
  - `SchemaUtils.create(ImageAssetTable, ImageObjectTable, ImageProcessingJobTable, ImageProcessingEventTable)`에 전화 걸기
  - `SchemaUtils.create` 다음에 원시 SQL을 실행합니다.
    ```sql
    CREATE UNIQUE INDEX IF NOT EXISTS uq_image_objects_asset_kind_variant
    ON image_objects (image_asset_id, kind, variant_name) NULLS NOT DISTINCT
    ```
  - try/catch에서 NOT `SchemaUtils.create()`을 래핑해야 함 — 시작 DDL 실패는 전파되어야 함
  - **UserContext 참고**: 감사 가능한 모든 테이블 쓰기는 `UserContext.withUser("image-processing-service") { ... }` — NOT Spring `AuditorAware` Bean을 사용해야 합니다(bluetape4k 노출은 `AuditableIdTable`의 `clientDefault`을 통해 `UserContext.getCurrentUser()`를 사용합니다)
- **수락 기준**: 앱 시작 시 생성된 테이블 4개 모두; `NULLS NOT DISTINCT` 인덱스가 존재합니다. DDL 주변에는 try/catch이 없습니다. Spring Boot 4.x 예외 전파: `ApplicationRunner.run()` throw로 인해 `SpringApplication.run()`이 throw되고 → 애플리케이션이 시작되지 않습니다. 참고: DDL 초기화가 트랜잭션 경계 외부에서 `DataIntegrityViolationException`를 포착할 필요가 없기 때문에 여기서는 `run()`의 `@Transactional`가 허용됩니다(saga 서비스와는 다름).

---

## 2단계 — 리포지토리 계층(4개 작업)

### T15 — ImageAssetRepository 생성
- **파일**: `src/main/kotlin/.../advanced/persistence/repository/ImageAssetRepository.kt`
- **복잡성**: 중간
- **종속성**: T8, T12, T13
- **세부**:
  - `@Repository` 클래스 (NOT `@Component`) — `@Repository`는 Spring의 `PersistenceExceptionTranslationPostProcessor`가 JDBC 예외를 `DataIntegrityViolationException`를 포함하는 Spring DAO 예외로 래핑할 수 있도록 합니다. 이 주석이 없으면 DIVE은 올바르게 전파되지 않습니다.
  - `LongAuditableJdbcRepository<ImageAssetDTO, ImageAssetTable>` 구현
  - `override val table = ImageAssetTable`
  - `override fun ResultRow.toEntity()`이(가) `toImageAssetDTO()`에 위임됩니다.
  - 맞춤 방법:
    - `findByChecksum(checksum: String): ImageAssetDTO?`
    - `findByExternalId(externalId: String): ImageAssetDTO?`
    - `updateStatus(id: Long, status: ImageAssetStatus)` — `auditedUpdateById` 사용
  - 최상위 `eq` 연산자만 사용
- **승인 기준**: 컴파일; `findByChecksum`은 최상위 `eq`을 사용합니다. `auditedUpdateById` 세트 `updatedBy`

### T16 — ImageObjectRepository 생성
- **파일**: `src/main/kotlin/.../advanced/persistence/repository/ImageObjectRepository.kt`
- **복잡성**: 중간
- **종속성**: T9, T12, T13
- **세부**:
  - `@Repository` 클래스 (NOT `@Component`) — `DataIntegrityViolationException` Spring 예외 변환에 필요
  - `LongAuditableJdbcRepository<ImageObjectDTO, ImageObjectTable>` 구현
  - 맞춤 방법:
    - `batchUpsertObjects(assetId: Long, objects: List<ImageObjectInput>)` — 사용
      `table.batchUpsert(objects, keys = listOf(ImageObjectTable.imageAssetId, ImageObjectTable.kind, ImageObjectTable.variantName)) { row, obj -> ... }`
      `NULLS NOT DISTINCT` 고유 인덱스를 적중하려면
    - `findByAssetId(assetId: Long): List<ImageObjectDTO>`
- **허용 기준**: `batchUpsertObjects`은 올바른 키 열을 업데이트합니다. 재시도 시 멱등성

### T17 — ImageProcessingJobRepository 생성
- **파일**: `src/main/kotlin/.../advanced/persistence/repository/ImageProcessingJobRepository.kt`
- **복잡성**: 중간
- **종속성**: T10, T12, T13
- **세부**:
  - `@Repository` 클래스 (NOT `@Component`) — Spring 예외 변환에 필요
  - `LongJdbcRepository<ImageProcessingJobDTO>` 구현
  - 맞춤 방법:
    - `insertJob(assetId: Long, requestedVariants: List<String>): Long` — 생성된 작업 반환 ID
    - `markSucceeded(jobId: Long, durationMs: Long)` — 상태 업데이트, finishedAt, durationMs
    - `markFailed(jobId: Long, errorCode: String, errorMessage: String, durationMs: Long)`
    - `findByAssetId(assetId: Long): List<ImageProcessingJobDTO>` — 주문자: startedAt DESC
- **승인 기준**: `insertJob`은 유효한 ID을 반환합니다. `markFailed`은 모든 오류 필드를 채웁니다.

### T18 — ImageProcessingEventRepository 생성
- **파일**: `src/main/kotlin/.../advanced/persistence/repository/ImageProcessingEventRepository.kt`
- **복잡성**: 중간
- **종속성**: T11, T12, T13
- **세부**:
  - `@Repository` 클래스 (NOT `@Component`) — Spring 예외 변환에 필요
  - `LongJdbcRepository<ImageProcessingEventDTO>` 구현
  - 맞춤 방법:
    - `appendEvent(jobId: Long, step: ImageProcessingStep, status: ImageProcessingEventStatus, message: String, payload: Map<String, Any?> = emptyMap())` — 단순 삽입; 호출자에게 예외를 전파합니다.
    - `findByJobId(jobId: Long): List<ImageProcessingEventDTO>` — 주문자: createdAt ASC
- **승인 기준**: `appendEvent`은 하나의 행을 삽입합니다. `payloadJson`은 `jacksonb`를 통해 직렬화됩니다.

---

## 3단계 — 서비스 계층(3개 작업)

### T19 — ImagePersistenceService 인터페이스 생성
- **파일**: `src/main/kotlin/.../advanced/persistence/ImagePersistenceService.kt`
- **복잡성**: 낮음
- **종속성**: T12
- **세부**:
  - 사양 §5의 6가지 메서드와 인터페이스:
    `recordJobStart`, `recordJobSuccess`, `recordJobFailure`, `appendEvent`,
    `findAssetByExternalId`, `findAssetHistory`
  - NO `@Transactional` 인터페이스 수준 — 구현에서는 프로그래밍 방식을 사용합니다. `TransactionTemplate`
  - 거래 전파 계약을 문서화하는 각 방법에 대한 영문 KDoc
  - `appendEvent` 계약: 호출자에게 예외를 전파합니다(여기서는 억제하지 않음).
- **승인 기준**: 인터페이스 컴파일; 인터페이스에 `@Transactional` 주석이 없습니다.

### T20 — 생성 ImagePersistenceServiceImpl — 경로 쓰기(T1/T2/T3/event)
- **파일**: `src/main/kotlin/.../advanced/persistence/ImagePersistenceServiceImpl.kt`
- **복잡성**: 높음
- **종속성**: T15, T16, T17, T18, T19
- **세부**:
  - `ImagePersistenceService`을 구현하는 `@Service` 클래스
  - 클래스 또는 메소드에 `@Transactional` 없음 — 프로그래밍 방식으로 `TransactionTemplate(REQUIRES_NEW)` 사용
  - **생성자 주입**: `transactionManager: PlatformTransactionManager`을 생성자 매개변수로 주입합니다.
    클래스 수준 필드를 선언합니다.
    ```kotlin
    private val txTemplate = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }
    ```
  - **UserContext 범위**: `UserContext.withUser("image-processing-service") { ... }` MUST ALL `txTemplate.execute { }` 호출 AND을 포함하여 각 쓰기 메서드의 ENTIRE 메서드 본문을 래핑합니다(DIVE catch, 다시 읽기, 상태 업데이트). 내부 `execute` 블록만 래핑하면 감사 사용자 컨텍스트 없이 복구 쓰기가 남습니다.
  - **정리 오류**: 발신자는 사전에 정리된 `JobFailureReason`를 제공합니다. `recordJobFailure`은 값을 그대로 저장합니다. 원시 스택 추적은 메서드에 도달하지 않습니다. T25은 이 메서드를 호출하는 `Throwable → safe (errorCode, errorMessage)` BEFORE를 매핑해야 합니다.
  - **TransactionTemplate null 허용 반환 처리**: `txTemplate.execute { lambda }`은 Kotlin에서 `T?`을 반환합니다.
    모든 호출자는 `requireNotNull(txTemplate.execute { ... }) { "T1 transaction returned null — unexpected rollback (REQUIRES_NEW should not silently null-return)" }`를 사용해야 합니다.
    `!!` 사용은 금지되어 있습니다. 잘못된 기본값을 자동으로 사용하는 것(예: `NewAsset(0,0,"")` 반환)도 금지됩니다.
  - **`recordJobStart()` — T1** (가장 복잡함):
    - 내부적으로 `txTemplate.execute { }` 사용 (NOT `@Transactional`)
    - 체크섬으로 자산을 쿼리합니다. 상태 분기:
      - `READY` → `JobStartResult.AlreadyReady(assetId, jobId=-1L, externalId=existing.externalId)` 반환
      - `PROCESSING` → 로그 + 동일한 자산에 새 작업 생성 → `ConcurrentProcessing` 반환
      - `FAILED` → 상태 업데이트→PROCESSING, 새 작업 생성 → `RecoveredFromFailed` 반환
      - 찾을 수 없음 → `externalId`에 대해 `UUID.randomUUID().toString()` 생성, 자산 + 작업 삽입 → `NewAsset` 반환
    - `DataIntegrityViolationException` 잡았습니다 OUTSIDE 템플릿 실행 블록
      (DIVE이 전파될 때 트랜잭션이 이미 롤백되었습니다):
      체크섬으로 다시 읽고 상태에 따라 분기합니다.
      다시 읽으면 null이 반환되고 → `ImageAssetNotFoundException(checksum)`이 발생합니다.
  - **`recordJobSuccess()` — T2**: `TransactionTemplate(REQUIRES_NEW)`:
    `batchUpsertObjects`을 통해 image_objects 업데이트, 자산 상태 업데이트→READY, 작업 표시 SUCCEEDED
  - **`recordJobFailure()` — T3**: `TransactionTemplate(REQUIRES_NEW)`:
    FAILED 작업을 정리된 `errorCode`/`errorMessage`로 표시하고 자산 상태를 업데이트합니다→FAILED.
    `withContext(NonCancellable + Dispatchers.IO)` 포장은 CALLER의 책임입니다.
  - **`appendEvent()`**: `TransactionTemplate(REQUIRES_NEW)`: 단일 이벤트 행 삽입.
    호출자에게 예외를 전파합니다. 구현 내부에서는 억제하지 않습니다.
- **승인 기준**:
  - `recordJobStart` 4개 분기 모두 처리 + DIVE catch + null 다시 읽기
  - 클래스에 `@Transactional` 주석이 없습니다.
  - 모든 DB 쓰기 전에 UserContext 설정
  - 오류 메시지가 삭제되었습니다(스택 추적 없음, VIPS 경로 없음, JDBC URL 없음).

### T21 — 읽기 경로 구현(findAssetByExternalId, findAssetHistory)
- **파일**: `src/main/kotlin/.../advanced/persistence/ImagePersistenceServiceImpl.kt` (T20과 동일한 파일)
- **복잡성**: 중간
- **종속성**: T20
- **세부**:
  - **`findAssetByExternalId()`**: 트랜잭션 읽기:
    externalId으로 자산을 쿼리하고, assetId로 image_objects를 쿼리합니다.
    `ImageAssetDetailResponse(asset, original, variants)`를 어셈블하세요.
    자산을 찾을 수 없으면 null을 반환합니다(호출자는 404에 매핑됨).
    객체 없이 FAILED 자산을 처리합니다. `original=null, variants=emptyList()`을 반환합니다(NPE 아님).
  - **`findAssetHistory()`**: 트랜잭션 읽기:
    각 작업 쿼리 이벤트에 대해 externalId별로 자산 쿼리, assetId별로 작업 쿼리.
    `ImageAssetHistoryResponse(asset, jobs+events)`를 어셈블하세요.
    자산을 찾을 수 없으면 null을 반환합니다.
- **수용 기준**: 읽기 방법은 올바른 DTO 구조를 반환합니다. FAILED 자산에 대해 null 안전

---

## 4단계 - API 레이어(3개 작업)

### T22 — GET /api/images/{externalId} 엔드포인트 추가
- **파일**: `src/main/kotlin/.../advanced/web/ImageDerivativesController.kt`
- **복잡성**: 중간
- **종속성**: T19, T21
- **세부**:
  - 컨트롤러에 `ImagePersistenceService` 삽입
  - `@GetMapping("/{externalId}")` 일시 중지 기능:
    `withContext(Dispatchers.IO) { persistence.findAssetByExternalId(externalId) }`
    `?: throw NoSuchElementException("Image asset not found: $externalId")`
  - 광범위한 캐치 전에 `CancellationException` 다시 던지기
- **허용 기준**: 200 JSON 본문을 반환합니다. 알 수 없는 ID에 대한 예외 처리기를 통한 404

### T23 — GET /api/images/{externalId}/history 엔드포인트 추가
- **파일**: `src/main/kotlin/.../advanced/web/ImageDerivativesController.kt`
- **복잡성**: 중간
- **종속성**: T19, T21
- **세부**:
  - `@GetMapping("/{externalId}/history")` 정지 기능
  - 응답 유형: `ImageAssetHistoryResponse`
  - T22과 동일한 null → 404 패턴
- **수락 기준**: 작업 + 이벤트가 있는 경우 200을 반환합니다. 알 수 없는 ID의 경우 404

### T24 — ImageProcessingExceptionHandler에 NoSuchElementException 핸들러 추가
- **파일**: `src/main/kotlin/.../advanced/web/ImageProcessingExceptionHandler.kt`
- **복잡성**: 낮음
- **종속성**: 없음
- **세부**:
  - 반환하는 `@ExceptionHandler(NoSuchElementException::class)` 메서드 추가
    `ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.message ?: "Resource not found")`
  - 콘텐츠 유형: `application/problem+json`
- **허용 기준**: 알 수 없는 externalId이(가) 404를 반환하는 GET `application/problem+json`

---

## 5단계 — 통합 및 워크플로(3개 작업)

### T25 — ImageDerivativeWorkflowService을 연결하여 ImagePersistenceService 사용
- **파일**: `src/main/kotlin/.../advanced/service/ImageDerivativeWorkflowService.kt`
- **복잡성**: 높음
- **종속성**: T19, T20, T21
- **세부**:
  - `ImagePersistenceService`을 필수 생성자 매개변수로 추가합니다(null 허용 불가 - T27를 통해 기존 테스트에서 `NoopImagePersistenceService` 사용).
  - `processUploadInternal()`에서:
    1. 원시 바이트 배열에서 `MessageDigest.getInstance("SHA-256")`을 사용하여 `bytes`의 SHA-256 체크섬을 계산합니다. 소문자 16진수 문자열(64자)로 인코딩합니다. 결과는 `image_assets.checksum VARCHAR(64)`에 매핑됩니다.
       **스레드 안전성**: 호출마다 새 `MessageDigest` 인스턴스 생성 — NEVER 캐시를 클래스 수준 필드 또는 정적 변수로 사용합니다(`MessageDigest`은 NOT 스레드로부터 안전합니다. 공유 인스턴스는 동시성에서 체크섬을 손상시킵니다).
    **주문 의무**: 기존 유효성 검사기(`validateDeclaredSize`, `validate`) MUST는 BEFORE 체크섬 계산(1단계) 및 T1(2단계)을 실행합니다. 유효성 검사 실패는 `IllegalArgumentException`로 전파됩니다. NOT T1 또는 T3를 호출합니다. 기존 `recordFailure("validation")` 메트릭 경로를 통해서만 기록됩니다. 체크섬이 계산됩니다. AFTER 유효성 검사가 성공합니다.
    2. `withContext(Dispatchers.IO) { persistence.recordJobStart(assetMetadata, requestedVariants) }`
       브로드캐치 전에 명시적으로 `CancellationException` 다시 던지기
    3. **`JobStartResult`의 전체 `when`** (봉인된 인터페이스 - `when`, NOT 부분 `if/is` 사용):
       ```kotlin
       when (result) {
           is JobStartResult.AlreadyReady -> {
               // EARLY RETURN — must execute BEFORE any event emission (step 7)
               // NO new events emitted in AlreadyReady path
               val detail = withContext(Dispatchers.IO) { persistence.findAssetByExternalId(result.externalId) }
               return@processUploadInternal ImageProcessingResponse(
                   imageId = result.externalId,
                   original = detail.original?.let { OriginalImageMetadata(...from it...) },
                   variants = detail.variants.map { ... },
                   thumbnailUrl = detail.variants.firstOrNull { it.kind == ImageObjectKind.THUMBNAIL }?.publicUrl,
                   durationMillis = 0L,  // no new processing; cached response
               )
           }
           is JobStartResult.NewAsset,
           is JobStartResult.ConcurrentProcessing,
           is JobStartResult.RecoveredFromFailed -> { /* proceed with normal saga flow */ }
       }
       ```
       **AlreadyReady 필드 매핑**(status=READY은 null이 아닌 원래 행을 보장합니다):
       - `original`: `detail.original` — READY 자산에 대해 null이 아니어야 합니다(문서 불변성: `AlreadyReady`는 `status=READY`인 경우에만 반환되며 이는 `image_objects`가 존재함을 의미함)
       - `variants`: `detail.variants`에서(`List<ImageObjectDTO>` 입력)
       - `thumbnailUrl`: `detail.variants.firstOrNull { it.kind == THUMBNAIL }?.publicUrl`에서
       - `durationMillis`: `0L` 사용(새 처리가 수행되지 않음)
       - `OriginalImageMetadata`에 대한 `contentType`: `detail.asset.contentType`에서(`image_objects`가 아닌 `image_assets`의 열)
    4. `JobStartResult`의 `imageId = result.externalId` 사용(`UUID.randomUUID()` 사용 안 함)
    5. catch 블록에서 T3을 호출하기 전에: 예외 클래스별로 `cause: Throwable` → 안전한 `(errorCode: String, sanitizedMessage: String)`을 매핑합니다(예: VipsException → `"VIPS_FAILED"/"VIPS processing failed"`, AmazonS3Exception → `"S3_UPLOAD_FAILED"/"S3 upload failed"`, 일반 → `"PROCESSING_FAILED"/"Processing failed"`). NEVER `exception.message` 또는 스택 추적을 `JobFailureReason`에 전달합니다.
    6. **ALL `ImagePersistenceService` MUST에 대한 호출은 `withContext(Dispatchers.IO)`으로 래핑됩니다**:
       - `recordJobStart` (2단계)
       - `findAssetByExternalId` (3단계, AlreadyReady 분기)
       - `appendEvent` (각 이벤트 단계)
       - `recordJobSuccess` (T2)
       - `recordJobFailure` (T3 in catch 블록 — `withContext(NonCancellable)`에 추가로 래핑됨)
       - `findAssetHistory` (모든 기록 쿼리)
    7. 단계별로 이벤트를 내보냅니다.
       - `VALIDATION` (T1 커밋 후, jobId 알려짐)
       - `VIPS_PROCESSING`(VIPS이 반환된 후)
       - `S3_UPLOAD` (각 putObject 다음: `COMPLETED` 또는 `FAILED`)
       - `S3_UPLOAD FAILED`이 있는 경우: `FAILED` 상태의 `S3_UPLOAD` 이벤트를 내보내면 즉시 T3을 호출합니다. 더 이상 변형 업로드가 없습니다 — 터미널.
       - `JOB_COMPLETED` (T2 커밋 후)
       - `JOB_FAILED` (catch 블록에서 T3 커밋 후)
    8. 명시적인 예외 유형 안전 try/catch으로 래핑된 각 `appendEvent` 호출은 다음과 같습니다.
       ```kotlin
       try {
           withContext(Dispatchers.IO) { persistence.appendEvent(...) }
       } catch (e: CancellationException) {
           throw e  // NEVER suppress cancellation
       } catch (e: Exception) {
           // Suppress non-cancellation exceptions only (NOT Error subclasses)
           log.warn(e) { "Event append failed: step=$step jobId=$jobId" }
           meterRegistry.counter(METRIC_EVENT_APPEND_FAILED).increment()
       }
       ```
       `Error` 하위 클래스(OutOfMemoryError 등)는 이벤트 발생 시 NOT 억제되어야 합니다.
    9. T3 in catch 블록: 중첩된 try-catch 가드와 함께 사양 §3.1의 정확한 패턴을 사용합니다.
       `throw originalException` MUST `addSuppressed`/log/count throw 시에도 무조건 실행됩니다.
       전체 방어 패턴에 대해서는 사양 §3.1 T3 예외 처리 계약을 참조하세요.
  - 측정항목 상수(기존 4와 함께 `ImageDerivativeWorkflowService` 동반 개체에 추가):
    `METRIC_EVENT_APPEND_FAILED = "image.processing.event.append.failed"`,
    `METRIC_FAILURE_RECORD_FAILED = "image.processing.failure.record.failed"`
- **승인 기준**:
  - 기존 4개의 `ImageDerivativeWorkflowServiceTest` 테스트가 `NoopImagePersistenceService`로 통과되었습니다.
  - Saga 흐름: T1 → 프로세스 → 이벤트 → 성공 시 T2; T3 실패 시
  - S3_UPLOAD FAILED은 최종입니다(추가 변형 업로드 없음).
  - CancellationException 항상 다시 발생합니다. 통화를 일시 중지할 경우에는 `runCatching`이 없습니다.
  - POST 응답 `imageId` = `result.externalId` (NOT `UUID.randomUUID()`)
  - AlreadyReady 경로: NO 이벤트 발생; `findAssetByExternalId`에서 캐시된 응답을 반환합니다.
  - 오류 정리: NO 스택 추적 / FFM 경로 / JDBC `JobFailureReason.errorMessage`의 URL

### T26 — NoopImagePersistenceService 생성(가짜 테스트)
- **파일**: `src/test/kotlin/.../advanced/persistence/NoopImagePersistenceService.kt`
- **복잡성**: 낮음
- **종속성**: T19
- **세부**:
  - `src/test/kotlin/` ONLY에 위치 - 프로덕션 소스에는 없음
  - `ImagePersistenceService` 구현
  - `recordJobStart()`은 `JobStartResult.NewAsset(assetId=0L, jobId=0L, externalId=UUID.randomUUID().toString())`를 반환합니다.
    (externalId은 호출당 NON-DETERMINISTIC입니다 — 매번 새로운 UUID입니다. 한 번 호출하는 기존 테스트에 허용됩니다. 특정 externalId를 검증하는 경우 T30 사가 테스트에 대해 이 제한 사항을 문서화하세요.
  - 기타 모든 방법: no-ops(빈 본문, 쿼리의 경우 null 반환)
- **승인 기준**: 가짜 컴파일을 테스트합니다. DB 상호작용 없음

### T27 — 기존 ImageDerivativeWorkflowServiceTest을 NoopImagePersistenceService로 업데이트
- **파일**: `src/test/kotlin/.../advanced/service/ImageDerivativeWorkflowServiceTest.kt`
- **복잡성**: 중간
- **종속성**: T25, T26
- **세부**:
  - `service()` 팩토리 메소드를 업데이트하여 `NoopImagePersistenceService()`을 필수 `persistence` 매개변수로 전달하세요.
  - PostgreSQL 없이 기존 테스트 4개를 모두 통과해야 합니다.
- **승인 기준**: 기존 테스트 4개 모두 녹색을 통과합니다. PostgreSQL 컨테이너가 필요하지 않습니다.

---

## 6단계 — 테스트(4개 작업)

### T28 — AbstractImagePersistenceTest 생성(테스트 기본 클래스)
- **파일**: `src/test/kotlin/.../advanced/persistence/AbstractImagePersistenceTest.kt`
- **복잡성**: 중간
- **종속성**: T1, T2, T14
- **세부**:
  - `@SpringBootTest` `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` 포함
  - `PostgreSQLServer.Launcher.postgres` 싱글톤(NEVER `GenericContainer` 직접)
  - `@DynamicPropertySource` 주입할 동반 메소드:
    `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password`
  - 빌드 구성의 `forkEvery=1`은 이미 JVM 격리를 처리합니다. 각 포크는 공유 컨테이너에 다시 연결됩니다.
  - **`withTestUser` 도우미**: 감사 가능한 테이블의 모든 직접 DB 시드 작업MUST 이 도우미를 사용합니다.
    ```kotlin
    fun <R> withTestUser(block: () -> R): R =
        UserContext.withUser("image-processing-service") { block() }
    ```
    이것이 없으면 `AuditableIdTable.createdBy`(`UserContext.getCurrentUser()` clientDefault을 통해 채워짐)은 null이고 NOT NULL 제약 조건에 실패합니다.
  - 도우미 방법: `ON DELETE CASCADE`을 통해 assetId에 의한 분해; `junit-platform.properties` 및 `logback-test.xml`가 `src/test/resources/`에 있는지 확인합니다.
- **수락 기준**: 기본 클래스는 PostgreSQL로 Spring 컨텍스트를 시작합니다. 4개의 테이블이 모두 존재합니다. `withTestUser` 모든 하위 클래스에서 사용할 수 있는 도우미; 테스트 로그(application.yml의 `leak-detection-threshold: 30000`)에 HikariCP 연결 누출 경고가 없습니다.

### T29 — ImagePersistenceServiceImplTest 생성(17개 시나리오)
- **파일**: `src/test/kotlin/.../advanced/persistence/ImagePersistenceServiceImplTest.kt`
- **복잡성**: 높음
- **종속성**: T28, T20, T21
- **세부정보**: 테스트는 `ImagePersistenceServiceImpl` 직접 실행됩니다(워크플로 서비스 없음). 감사 가능한 테이블에 대한 ALL 시드 작업은 `withTestUser { ... }`를 사용합니다.
  사양 §10 시나리오를 다룹니다.
  1. **T1-생성**: `recordJobStart` PROCESSING 자산 + RUNNING 작업을 생성합니다. DB 행 수 검증
  2. **T2-성공**: `recordJobSuccess` → READY + SUCCEEDED + 올바른 image_objects 수
  3. **T3-상태**: `recordJobFailure` → FAILED 작업 + 자산 상태
  4. **T3-필드**: errorCode + errorMessage non-null/non-blank 실패 후
  5. **T3-삭제**: errorMessage에는 스택 추적이 포함되어 있지 않습니다(`\n` 없음, `"at io."` 없음, `"Exception"` 없음).
  6. **멱등성-체크섬**: 동일한 체크섬이 두 번 → 중복된 image_object 없음
  7. **멱등성 단락**: 동일한 체크섬 + READY → `assertIs<JobStartResult.AlreadyReady>`; 그런 다음 **델타 어설션**을 통해 생성된 새 이벤트가 없는지 확인합니다. 스냅샷 `SELECT COUNT(*) FROM image_processing_events` BEFORE AlreadyReady `recordJobStart()` 호출, 어설션 개수는 UNCHANGED 이후 — `jobId=-1L`는 이 결과에 대한 이벤트가 FK 존재하지 않음을 의미합니다. 델타 기반 어설션은 다른 jobId에서 우발적인 방출을 포착합니다.
  8. **FAILED-재시도**: `withTestUser { }` → `recordJobStart` → `assertIs<RecoveredFromFailed>`를 통해 시드 FAILED 자산; 상태 검증=PROCESSING; 새로운 RUNNING 작업이 존재한다고 검증합니다. 그런 다음 `appendEvent(step=VALIDATION)`을 호출하고 이벤트가 존재하는지 확인합니다.
  9. **멱등성-널-원본**: `recordJobSuccess` 두 번, ORIGINAL → 정확히 1 ORIGINAL 행
  10. **DB-init**: Spring 컨텍스트 시작 시 `SELECT COUNT(*)`을 통해 쿼리 가능한 4개 테이블 모두
  11. **동시 체크섬 경주 결정적**: `ImageAssetRepository`의 MockK `spyk`; 첫 번째 `insertAsset` 호출은 `DataIntegrityViolationException`을 발생시킵니다. 두 번째 호출(`findByChecksum`)은 기존 자산을 반환합니다. 다시 읽은 경로를 확인합니다(올바른 `JobStartResult` 하위 유형을 반환함).
  12. **Concurrent-checksum-race-DIVE-null**: MockK 저장소를 감시합니다. 삽입 던지기 DIVE; `findByChecksum`은 null을 반환합니다. `assertFailsWith<ImageAssetNotFoundException>`을 체크섬 값으로 검증
  13. **Concurrent-checksum-race-probabilistic**: `MultithreadingTester` 2개 스레드, 동일한 체크섬; 예외가 없다고 검증하십시오. 자산 수=1
  14. **연속 삭제**: 전체 T1+T2 주기 후, `DELETE FROM image_assets WHERE id=?`; `COUNT(*) FROM image_objects WHERE image_asset_id=?` = 0, `COUNT(*) FROM image_processing_jobs WHERE image_asset_id=?` = 0 검증
  15. **POST-imageId-같음-externalId**: T1 뒤에 `JobStartResult.externalId == SELECT external_id FROM image_assets WHERE checksum=?`을 검증합니다. POST 엔드포인트를 통한 전체 주기 후에 HTTP 응답 `imageId` 필드가 동일한 external_id와 같다고 검증
  16. **T1-creates-with-UserContext**: `recordJobStart` with `UserContext.withUser("test-user")` → 자산 및 작업 행 모두에서 `created_by="test-user"` 검증
  17. **ConcurrentProcessing-path**: `withTestUser { }`을 통해 `status=PROCESSING`(진행 중인 실행 시뮬레이션)이 있는 시드 자산 → 동일한 체크섬으로 `recordJobStart()` 호출 → `assertIs<JobStartResult.ConcurrentProcessing>`; 동일한 `assetId`에 새로운 `RUNNING` 작업이 존재한다고 검증합니다. 원래 PROCESSING 자산 행이 변경되지 않았다고 검증
  - 모든 테스트에서는 역따옴표 이름을 설명하는 AAA 패턴을 사용합니다.
  - T29-13/17에 대한 `MultithreadingTester`(bluetape4k-junit5); 원시 Thread/Executors/coroutineScope 실행 금지
  - 시나리오 11-12는 실제 저장소 빈을 래핑하는 MockK `spyk`을 사용합니다.
- **수용 기준**: 17개 시나리오 모두 통과(사양 §10의 시나리오 1-16, 시나리오 17 = ConcurrentProcessing-사양 §10의 경로). DB 직접 쿼리를 통해 확인된 상태; `withTestUser` 모든 시드 작업에 사용됩니다. HikariCP 누출 경고 없음

### T30 — ImageDerivativeWorkflowSagaTest 생성(6개 시나리오)
- **파일**: `src/test/kotlin/.../advanced/service/ImageDerivativeWorkflowSagaTest.kt`
  (패키지: `service/` — `persistence/` 아님 — `ImageDerivativeWorkflowService` 오케스트레이션 동작 테스트)
- **복잡성**: 높음
- **종속성**: T25, T28
- **세부정보**: 모든 일시중단 통화에 `runTest { }`을 사용합니다. ALL 시드 작업은 `withTestUser { }`를 사용합니다.
  사양 §10 시나리오를 다룹니다.
  1. **T3-전파**: MockK `recordJobFailure()`을 감시하여 `RuntimeException("T3 failure")`을 던집니다. VIPS 모의 실패로 업로드 트리거 → `runTest { assertFailsWith<RuntimeException> { service.processUpload(file) } }`; `e.suppressed[0].message == "T3 failure"` 검증
  2. **Event-mini-tx-suppression**: MockK `appendEvent`을 감시하여 던집니다. `processUpload()` 성공(예외 없음); Micrometer 카운터 `image.processing.event.append.failed` 증가 ≥ 1
  3. **이벤트 성공 경로**: 전체 사가(실제 T1+T2+이벤트); VALIDATION → VIPS_PROCESSING → S3_UPLOAD(×N) → JOB_COMPLETED 이벤트 시퀀스를 DB에서 검증; 총계 = 3+N 이벤트(N = image_objects 수 = 변형 수 + ORIGINAL의 경우 1). 예: 변형 2개 → N=3 → 총 6개 이벤트
  4. **이벤트 실패 경로**: VIPS을 모의하여 던집니다. 사가는 T3을 실행합니다. DB에서 step=JOB_FAILED, status=FAILED로 하나의 이벤트를 검증합니다.
  5. **S3-업로드-FAILED-terminality**: S3을 모의하여 첫 번째 putObject에서 실패하도록 합니다. T3가 ​​즉시 호출되도록 검증합니다. 남은 변형 업로드 NOT 시도 — `verify(exactly = 1) { s3Client.putObject(any()) }`로 확인; 작업 상태 확인=FAILED; 상태가 FAILED인 S3_UPLOAD 이벤트가 존재하는지 확인
  6. **Error-is-Error-subclass**: VIPS 단계에서 `OutOfMemoryError`(오류 하위 클래스)을 발생시킵니다. T3 catch 블록은 이를 잡아야 합니다(사양에서는 `catch(Exception)`가 아닌 `catch(Throwable)`이 필요함). FAILED으로 기록된 Assert 작업; 원본 `OutOfMemoryError`이 다시 던져진다고 검증
  - `@SpringBootTest` 컨텍스트 확장 `AbstractImagePersistenceTest` (실제 PostgreSQL)
  - **MockK 시나리오별 전략**: T30-1 및 T30-2는 `@SpykBean ImagePersistenceService`을 사용하여 나머지 서비스를 실제 상태로 유지하면서 개별 메소드를 스텁합니다. T30-3 및 T30-4는 실제 서비스를 사용합니다(모의 없음 - 실제 PostgreSQL가 포함된 완전한 사가). T30-5 모의 `S3Client`(또는 이에 상응하는 업로드 구성요소). T30-6은 `OutOfMemoryError`을 발생시키기 위한 VIPS 처리 단계를 모의합니다.
  - `@SpykBean`은 수업 수준에 적용됩니다. `@BeforeEach`의 `clearAllMocks()`을 통한 테스트 방법별 격리
- **수용 기준**: 6개 시나리오가 모두 통과됩니다. T3 `suppressed` 검사로 실패 전파 확인; Micrometer 카운터로 이벤트 억제를 확인했습니다. DB 쿼리를 통해 확인된 이벤트 순서; S3_UPLOAD FAILED 최종성 확인; T3 블록에 잡힌 `Error` 하위 클래스(`Exception`뿐만 아니라)

### T31 — ImageAssetEndpointTest 생성(4개 시나리오)
- **파일**: `src/test/kotlin/.../advanced/web/ImageAssetEndpointTest.kt`
- **복잡성**: 중간
- **종속성**: T22, T23, T24, T28
- **세부정보**: 사양 §10 시나리오를 다룹니다. 감사 가능한 테이블의 ALL 시드 작업은 `withTestUser { }`을 사용합니다.
  1. **GET-asset**: `withTestUser { seedFullAsset(externalId, status=READY) }` → GET `/api/images/{externalId}` → 본문 확인, 상태 200; `response.body.imageId == externalId` 검증
  2. **GET-404**: GET 알 수 없음 ID → 404, 콘텐츠 유형 `application/problem+json`
  3. **GET-역사**: 시드 자산 + 작업 + `withTestUser { }` → GET `/api/images/{externalId}/history`을 통한 이벤트 → 비어 있지 않은 작업, 단계 이름이 있는 이벤트
  4. **GET-asset-failed**: `withTestUser { seedFailedAsset(externalId) }`(image_objects 행 없음) → `original=null`, `variants=[]` 포함 200
  - `WebTestClient` 또는 `MockMvc`을 사용하세요. `AbstractImagePersistenceTest` 확장
- **수용 기준**: 4가지 엔드포인트 시나리오가 모두 통과됩니다. 올바른 HTTP 상태 코드 및 콘텐츠 유형 `withTestUser` 모든 시드 작업에 사용됩니다. 응답 `imageId`이(가) 지속된 `external_id`과(와) 일치합니다.

---

## 7단계 — 문서화(2개 작업)

### T32 — README.md 업데이트(ERD + 시퀀스 다이어그램 + 엔드포인트 + 작업)
- **파일**: `image-processing/advanced-workflow/README.md`
- **복잡성**: 낮음
- **종속성**: T20, T22, T23
- **세부**:
  - ERD 다이어그램(`bluetape4k-diagram` 스킬을 통한 SVG+PNG) — FK 관계가 있는 4개 테이블 모두
  - 업데이트된 지속성 시퀀스 다이어그램 - saga 흐름(T1 → VIPS → T2/T3)
  - 새로운 엔드포인트 문서: GET /api/images/{externalId} 및 /history
  - 사양 §9의 "사용된 Bluetape4k 기능" 표
  - 사양 §3.6의 오래된 작업 모니터링 쿼리가 포함된 "작업" 섹션
  - `docs/images/readme-diagrams/` 아래에 다이어그램 자산을 저장합니다. README에 `.png`만 포함
- **수락 기준**: README에는 ERD, 시퀀스 다이어그램, 엔드포인트 문서, 기능 테이블, 모니터링 쿼리가 포함됩니다.

### T33 — README.ko.md를 일치하는 콘텐츠로 업데이트합니다.
- **파일**: `image-processing/advanced-workflow/README.ko.md`
- **복잡성**: 낮음
- **종속성**: T32
- **세부**:
  - 모든 새로운 README 섹션의 한국어 번역
  - 동일한 다이어그램(공유 PNG 자산)
  - README.md와 README.ko.md 간의 언어 전환 링크
- **승인 기준**: README.ko.md는 구조적으로 README.md와 일치합니다. 동일한 다이어그램이 참조됨

---

## 요약

| 단계 | 설명 | 작업 수 | ID |
|-------|-------------|-----------|-----|
| 1단계 | 재단 | 14 | T1–T14 |
| 2단계 | 리포지토리 계층 | 4 | T15–T18 |
| 3단계 | 서비스 계층 | 3 | T19–T21 |
| 4단계 | API 레이어 | 3 | T22-T24 |
| 5단계 | 통합 및 워크플로우 | 3 | T25–T27 |
| 6단계 | 테스트 | 4 | T28–T31 |
| 7단계 | 문서 | 2 | T32–T33 |
| **합계** | | **33** | |

> **테스트 시나리오 수 업데이트**: T29 14개 → 17개 시나리오 확장; T30 2개 → 6개 시나리오로 확장되었습니다. 총 새로운 테스트 시나리오: 3개 테스트 파일에 걸쳐 23개(T29) + 6(T30) + 4(T31) = 33개 시나리오.

---

## 중요 경로

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
T25 (workflow rewire) ←── T20, T21                                                        |
T26 (NoopPersistence) ←── T19                                                             |
T27 (existing test fix) ←── T25, T26                                                      |
T28 (test base) ←── T1, T2, T14                                                           |
T29 (persistence tests) ←── T28, T20, T21                                                 |
T30 (saga tests) ←── T25, T28, T31                                                        |
T31 (endpoint tests) ←── T22–T24, T28                                                     |
T32–T33 (docs) ←── T20, T22, T23                                                          |
```

**주요 경로**: T1 → T3–T7 → T8–T11 → T13 → T15–T18 → T20 → T25 → T29/T30

---

## 위험 및 순서 결정 문제

| ID | 위험 | 완화 |
|----|------|-----------|
| R1 | `ImageObjectKind`이(가)  #93에서 누락됨 | T7는 새 파일로 생성합니다. T9/T12/T16 의존 |
| R2 | `@Transactional` 자기 호출 | 계획은 프로그래밍 방식으로 `TransactionTemplate`을 사용합니다. impl 클래스에는 `@Transactional`가 없습니다 |
| R3 | 동시 FAILED-재시도 경주 | 워크숍에 참가할 수 있습니다. `MultithreadingTester` 문서 동작(T29-14) |
| R4 | 진부한 직업 고아(사신 없음) | README에서만 쿼리 모니터링(T32); 구현 없음 |
| R5 | `NULLS NOT DISTINCT` 필요 PG 15세 이상 | `PostgreSQLServer.Launcher`은 `postgres:18-alpine`를 사용합니다 — 확인됨 |
| R6 | `forkEvery=1`이(가) 이미 있음 | T1 반드시 NOT 중복되어야 합니다. 각 포크는 공유 Testcontainers PG |
| R7 | `UserContext` 대 `AuditorAware` | 계획은 모든 쓰기 경로에서 `UserContext.withUser("image-processing-service")`를 사용합니다(확인된 소스) |
| R8 | 결정적 DIVE 테스트 설계 | MockK `spyk` 저장소에서 첫 번째 삽입 호출 시 발생 |
| R9 | 기존 테스트의 역호환 | T26는 `NoopImagePersistenceService`을 생성합니다. T27를 테스트 공장에 연결 |
| R10 | `externalId`이(가) `JobStartResult`에서 누락됨 | T12는 4개의 하위 유형 모두에 `externalId: String`를 추가합니다 |

---

## 중요한 구현 규칙

1. **Exposed 1.2+**: ONLY 최상위 `eq`, `and`, `inList` — NEVER `SqlExpressionBuilder.eq` (ERROR 수준은 더 이상 사용되지 않음)
2. **모든 사가 트랜잭션**: `TransactionTemplate`을 통한 `REQUIRES_NEW` — 외부 컨텍스트와 무관
3. **T3 블록 잡기**: `withContext(NonCancellable + Dispatchers.IO)` + 잡기 `Throwable` (`Exception` 아님)
4. **CancellationException**: `catch(Exception)` 확장 전에 항상 다시 던집니다. `runCatching {}` 통화 중단 시 금지
5. **`!!` 연산자 없음**; `val`을 선호합니다; 모두 `data class` 구현 `Serializable` + `serialVersionUID`
6. **`SchemaUtils.create()`**: NOT가 try/catch에 래핑되어야 함 - 시작 DDL 실패가 전파되어야 함
7. **`NoopImagePersistenceService`**: `src/test/kotlin/` ONLY — 프로덕션 소스에는 없음
8. **`appendEvent`**: 호출자에게 예외를 전파합니다. `ImageDerivativeWorkflowService`은 `log.warn` + 미터법 카운터로 억제합니다.
9. **S3_UPLOAD FAILED은 터미널입니다**: 즉시 T3를 호출합니다. 더 이상 변형 업로드가 없습니다
10. **VALIDATION 이벤트**: 방출된 AFTER T1 커밋(jobId은 이제 알려져 있음)
11. **`ImageProcessingEventStatus`**: `COMPLETED | FAILED | SKIPPED` — 아니요 `STARTED`
12. **오류 메시지 삭제**: 스택 추적 없음, VIPS FFM 경로 없음, JDBC 연결 문자열 없음
13. **`PostgreSQLServer.Launcher.postgres`**: Testcontainers에 대한 싱글톤 - 절대 `GenericContainer` 직접적으로 사용하지 않음
14. **`MultithreadingTester`**: 동시 레이스 테스트를 위한 bluetape4k-junit5에서
15. **HikariCP `leak-detection-threshold: 30000`** application.yml에 있음
16. **`UserContext.withUser("image-processing-service")`**: 모든 DB 쓰기를 서비스 impl로 래핑합니다. 봄이 아니다 `AuditorAware`; 범위는 복구 분기를 포함한 전체 메서드 본문을 포함합니다.
17. **`@Repository` (`@Component` 아님)**: 모든 저장소 클래스(T15–T18)는 `DataIntegrityViolationException`를 번역하기 위해 Spring의 `PersistenceExceptionTranslationPostProcessor`에 대해 `@Repository`를 사용해야 합니다.
18. **`PlatformTransactionManager` 주입**: `ImagePersistenceServiceImpl`은 `PlatformTransactionManager`을 주입하고 `TransactionTemplate`을 클래스 수준 필드로 생성해야 합니다.
19. **`withTestUser { }`**: 감사 가능한 테이블에 대한 모든 테스트 시드 작업은 `AbstractImagePersistenceTest`의 이 도우미를 사용해야 합니다.
20. **ALL `ImagePersistenceService` 워크플로 호출**: `recordJobStart` 및 `recordJobFailure`뿐만 아니라 `withContext(Dispatchers.IO)`로 래핑
21. **SHA-256 체크섬**: 원시 바이트의 `MessageDigest.getInstance("SHA-256")`; 소문자 16진수(64자)로 인코딩합니다. `VARCHAR(64)` 열과 일치
22. **T3 catch `Throwable`**: 오류 하위 클래스(예: `OutOfMemoryError`)도 잡아야 합니다. T3에는 `catch(Exception)`를 사용하지 마세요.
23. **T3 전 삭제 오류**: `JobFailureReason`을 생성하는 예외 클래스 BEFORE에 의해 `Throwable → (errorCode, sanitizedMessage)` 매핑; `exception.message` 또는 스택 추적을 전달하지 마세요.
