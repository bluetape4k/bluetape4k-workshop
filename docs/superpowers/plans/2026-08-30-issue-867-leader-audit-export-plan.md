# Leader audit export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 `leader/job-safety-lab`의 Redis leader lifecycle을 2.0.0-SNAPSHOT public audit exporter와 연결하고, 기본 네트워크 없는 bounded MEMORY 예제와 명시적 trusted HTTPS 경계를 함께 검증한다.

**Architecture:** PostgreSQL `JobSafety*` 저장소는 그대로 authoritative source로 두고, `AdmissionOnlyLeaderHistorySink`를 `SafeLeaderHistoryRecorder`에 연결한다. upstream `ExportingLeaderHistorySink`가 만든 sanitized event를 `MicrometerLeaderAuditExporter`와 `HttpLeaderAuditExporter`에 전달하며, `RecordingLeaderAuditPayloadEncoder`가 두 transport 앞에서 serialized JSON bytes를 동일한 bounded store에 캡처한다. MEMORY transport는 payload를 저장하지 않는 loopback-free fake를 사용하고 HTTPS는 exact host allow-list와 allow-listed header를 통과한 경우에만 생성한다. operator 전용 report는 retained bytes를 요청 시 Jackson tree로 decode하고 exporter snapshot/fixed meters만 반환한다. listening elector와 `LeaderElectionEventExportSubscription`은 기존 `LeaderElector` 주입 계약을 유지하면서 실제 acquire/release lifecycle을 연결한다. `JobSafetyAuditShutdownCoordinator`는 context close의 유일한 destroy owner로서 하나의 monotonic deadline을 계산하고 모든 owned resource에 남은 시간을 순서대로 전달한다.

**Tech Stack:** Kotlin 2.4, Java 25 `HttpClient`, Spring Boot 4, Spring Security HTTP Basic, Spring Boot `@ConfigurationProperties`, bluetape4k 2.0.0-SNAPSHOT leader audit public API, `bluetape4k-jackson3`, Micrometer, JUnit 5, bluetape assertions, Testcontainers PostgreSQL/Redis.

---

## 파일 책임 지도

| 경로 | 책임 |
| --- | --- |
| `leader/job-safety-lab/build.gradle.kts` | Jackson 3 consumer alias 추가. 버전은 root dependencies BOM에서만 해결한다. |
| `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/config/JobSafetyAuditProperties.kt` | MEMORY/HTTPS transport, queue/retry/payload/history/aggregate limits, endpoint/host/header fail-closed 검증. |
| `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/AdmissionOnlyLeaderHistorySink.kt` | PostgreSQL 저장을 하지 않고 lifecycle key만 admission하는 non-authoritative sink. |
| `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/BoundedAuditPayloadStore.kt` | immutable serialized payload bytes를 count/byte budget으로 보관하고 방어적 snapshot을 제공한다. |
| `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/JobSafetyAuditPayloadEncoder.kt` | upstream `LeaderAuditPayloadEncoder` 구현과 token/identity/tenant/customer/raw exception 제외 DTO. |
| `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/RecordingLeaderAuditPayloadEncoder.kt` | MEMORY/HTTPS에 공통으로 적용되는 serialized payload capture decorator. |
| `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/InMemoryAuditHttpClient.kt` | `https://audit.invalid/in-memory`에 대한 DNS/socket 없는 Java `HttpClient` fake와 gated status script. payload는 저장하지 않는다. |
| `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/JobSafetyAuditReportService.kt` | report port, bounded JSON tree view, exporter snapshot, fixed meter names. |
| `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/config/JobSafetyAuditConfiguration.kt` | properties 기반 client/encoder/store/exporter/recorder/report bean, listening publisher/subscription과 명시적 close ownership. |
| `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/config/JobSafetyAuditScope.kt` | `SupervisorJob + Dispatchers.Default`를 소유하고 `close()`에서 collector를 취소하는 application scope. |
| `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/config/JobSafetyAuditShutdownCoordinator.kt` | context close 시작 시 하나의 monotonic deadline을 만들고 subscription/exporter/client/scheduler/executor/scope를 순서대로 bounded 종료하는 유일한 destroy owner. |
| `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/config/JobSafetyConfiguration.kt` | `LettuceLeaderElector`에 `SafeLeaderHistoryRecorder` 주입. 기존 observation/DB wiring 보존. |
| `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/web/JobSafetyApiModels.kt` | audit report response DTO. |
| `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/web/JobSafetyController.kt` | `GET /api/job-safety/audit` operator report endpoint. |
| `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/web/JobSafetySecurityConfiguration.kt` | audit GET을 `JOB_SAFETY_OPERATOR`로 제한. |
| `leader/job-safety-lab/src/main/resources/application.yml` | MEMORY 기본값, bounded defaults, actuator secret masking. |
| `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/config/JobSafetyAuditPropertiesTest.kt` | bind/default/bound/overflow/HTTPS trust tests. |
| `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/JobSafetyAuditPayloadEncoderTest.kt` | JSON field/redaction/UTF-8/size tests. |
| `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/AdmissionOnlyLeaderHistorySinkTest.kt` | key preservation 및 non-authoritative behavior. |
| `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/BoundedAuditPayloadStoreTest.kt` | count/byte eviction, defensive copy, oversized payload discard. |
| `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/JobSafetyAuditExporterTest.kt` | queue/drop/retry/terminal/close/cancel/concurrency와 fake HTTP classification. |
| `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/JobSafetyAuditReportServiceTest.kt` | transient decode, fixed metric names, secret/sentinel exclusion. |
| `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/web/JobSafetyControllerTest.kt` | report response contract를 기존 controller tests에 추가. |
| `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/web/JobSafetySecurityTest.kt` | viewer/operator/anonymous audit authorization. |
| `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/JobSafetyRuntimeContractTest.kt` | exporter/recorder/publisher/scope bean wiring과 close order. |
| `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/JobSafetyEndToEndIntegrationTest.kt` | 실제 Redis leader lifecycle의 acquired/completed/failed audit 제출과 PostgreSQL authority 보존. |
| `leader/job-safety-lab/README.md`, `README.ko.md` | 양국 설정, MEMORY/HTTPS 차이, report curl, redaction/unsupported guarantee. |
| `docs/coverage-matrix.md` | leader job-safety audit example coverage. |
| `docs/lessons/2026-08-30-issue-867-leader-audit-export.md` | 재발 방지 lesson. |
| `docs/review/issue-867-workflow-checklist.md` 및 `docs/review/2026-08-30-issue-867-*.md` | Type A gate evidence. |

### Bean 이름·소유권 계약

| Bean name | 구현 타입/역할 | 종료 책임 | 주입 qualifier |
| --- | --- | --- | --- |
| `jobSafetyAuditExecutor` | `ExecutorService`, audit delivery worker | coordinator가 `shutdownNow` + 남은 deadline await | `@Qualifier("jobSafetyAuditExecutor")` |
| `jobSafetyAuditScheduler` | `ScheduledThreadPoolExecutor`, `removeOnCancelPolicy=true` | coordinator가 `shutdownNow` + 남은 deadline await | `@Qualifier("jobSafetyAuditScheduler")` |
| `jobSafetyAuditHttpClient` | raw Java 25 `HttpClient` | implicit destroy 비활성화, coordinator가 lifecycle wrapper 호출 | `@Qualifier("jobSafetyAuditHttpClient")` |
| `jobSafetyAuditHttpClientLifecycle` | `JobSafetyAuditHttpClientLifecycle` | coordinator가 `shutdownNow` + 남은 deadline await | `@Qualifier("jobSafetyAuditHttpClientLifecycle")` |
| `jobSafetyAuditExportOptions` | `LeaderAuditExportOptions` | 소유하지 않음 | `@Qualifier("jobSafetyAuditExportOptions")` |
| `jobSafetyAuditScope` | `JobSafetyAuditScope` (`SupervisorJob + Dispatchers.Default`) | coordinator가 마지막에 `close()`하여 job cancel | `@Qualifier("jobSafetyAuditScope")` |
| `jobSafetyAuditSubscription` | `LeaderElectionEventExportSubscription` | coordinator가 exporter보다 먼저 `close()` | `@Qualifier("jobSafetyAuditSubscription")` |
| `jobSafetyAuditExporter` | `MicrometerLeaderAuditExporter` | coordinator가 delegate를 먼저 `close()` | `@Qualifier("jobSafetyAuditExporter")` |
| `jobSafetyAuditShutdownCoordinator` | `JobSafetyAuditShutdownCoordinator` | 유일한 Spring destroy owner, aggregate deadline 생성 | `@Qualifier("jobSafetyAuditShutdownCoordinator")` |

`JobSafetyRuntimeContractTest`는 이 표의 실제 bean 이름을 조회하고 recording
fake trace를 비교한다. `jobSafetyAuditShutdownCoordinator`만 Spring destroy method를
가지며 나머지 resource bean은 implicit destroy를 비활성화한다. coordinator는 context
close 시작 시 한 번만 `shutdownDeadline`을 계산하고 `jobSafetyAuditScope`를 마지막에
cancel한다. context close가 aggregate `shutdownTimeout` 안에 반환되며
queued/inFlight/scheduledRetries가 0이고 executor/scheduler/client가 terminated인지
확인한다.

## Task 1: versionless Jackson dependency와 audit properties를 고정한다

**Files:**
- Modify: `leader/job-safety-lab/build.gradle.kts`
- Create: `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/config/JobSafetyAuditProperties.kt`
- Create: `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/config/JobSafetyAuditPropertiesTest.kt`
- Modify: `leader/job-safety-lab/src/main/resources/application.yml`

- [ ] **Step 1: failing tests 작성**

`JobSafetyAuditPropertiesTest`에 defaults, bounds, aggregate overflow, HTTPS allow-list와 redacted header 계약을 먼저 추가한다.

```kotlin
@Test
fun `memory defaults are bounded and endpoint free`() {
    val properties = JobSafetyAuditProperties()
    properties.transport shouldBeEqualTo AuditTransport.MEMORY
    properties.queueCapacity shouldBeEqualTo 32
    properties.maxInFlight shouldBeEqualTo 4
    properties.maxPayloadBytes shouldBeEqualTo 64 * 1024
    properties.recentHistoryByteBudget shouldBeEqualTo 512 * 1024
    properties.maxBufferedBytes shouldBeEqualTo 72 * 1024 * 1024
    properties.endpoint.shouldBeNull()
}

@Test
fun `maximum combination exceeds budget before resources are built`() {
    assertFailsWith<IllegalArgumentException> {
        JobSafetyAuditProperties(
            queueCapacity = 65_536,
            maxInFlight = 65_536,
            maxPayloadBytes = 1024 * 1024,
            maxBufferedBytes = 128L * 1024 * 1024,
        )
    }
}

@Test
fun `checked reservation reports long overflow`() {
    assertFailsWith<IllegalArgumentException> {
        checkedAuditReservation(
            queueAndFlight = Long.MAX_VALUE,
            maxPayloadBytes = 1,
            recentHistoryByteBudget = 0,
            pendingMetadataBytes = 0,
        )
    }
}

@Test
fun `https rejects private host and host outside exact allow list`() {
    assertFailsWith<IllegalArgumentException> {
        JobSafetyAuditProperties(
            transport = AuditTransport.HTTPS,
            endpoint = "https://127.0.0.1/audit",
            allowedHosts = setOf("127.0.0.1"),
        )
    }
    assertFailsWith<IllegalArgumentException> {
        JobSafetyAuditProperties(
            transport = AuditTransport.HTTPS,
            endpoint = "https://audit.example.test/audit",
            allowedHosts = setOf("other.example.test"),
        )
    }
}

@Test
fun `authorization is bounded and absent from value rendering`() {
    val secret = "Bearer " + "x".repeat(32)
    val headers = AuditHeaders(authorization = secret)
    headers.toString().contains(secret).shouldBeFalse()
    headers shouldBeEqualTo AuditHeaders(authorization = "Bearer another-secret")
    assertFailsWith<IllegalArgumentException> { AuditHeaders(authorization = "x".repeat(8193)) }
}
```

- [ ] **Step 2: RED 실행**

실행: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyAuditPropertiesTest' --no-parallel --max-workers=1`

예상: properties/enum/header 타입 부재로 compile 또는 test가 실패한다. 실패가 dependency resolution이 아니라 새 계약 부재인지 확인한다.

- [ ] **Step 3: 최소 구현**

`build.gradle.kts`의 `dependencies`에 `implementation(libs.bluetape4k.jackson3)`를 추가한다. properties public shape은 다음과 같다.

```kotlin
enum class AuditTransport { MEMORY, HTTPS }

@ConfigurationProperties("workshop.job-safety.audit")
data class JobSafetyAuditProperties(
    val transport: AuditTransport = AuditTransport.MEMORY,
    val endpoint: String? = null,
    val allowedHosts: Set<String> = emptySet(),
    val headers: AuditHeaders = AuditHeaders(),
    val queueCapacity: Int = 32,
    val maxInFlight: Int = 4,
    val maxAttempts: Int = 3,
    val attemptTimeout: Duration = Duration.ofSeconds(2),
    val initialBackoff: Duration = Duration.ofMillis(100),
    val maxBackoff: Duration = Duration.ofSeconds(1),
    val maxPayloadBytes: Int = 64 * 1024,
    val recentHistoryLimit: Int = 32,
    val recentHistoryByteBudget: Long = 512L * 1024,
    val maxBufferedBytes: Long = 72L * 1024 * 1024,
    val shutdownTimeout: Duration = Duration.ofSeconds(2),
)

class AuditHeaders(private val authorization: String? = null) {
    init {
        require(authorization == null || authorization.toByteArray(Charsets.UTF_8).size <= 8 * 1024)
    }
    fun asMap(): Map<String, String> = authorization?.let { mapOf("Authorization" to it) }.orEmpty()
    override fun toString(): String = "AuditHeaders(authorization=<redacted>)"
    override fun equals(other: Any?): Boolean =
        other is AuditHeaders && (authorization != null) == (other.authorization != null)
    override fun hashCode(): Int = if (authorization == null) 0 else 1
}
```

`init`에서 upstream queue/in-flight/attempt/time과 1 MiB payload bound를 확인한다. aggregate reservation은 executor/client를 만들기 전에 다음처럼 checked `Long` 산술로 계산한다.

```kotlin
internal fun checkedAuditReservation(
    queueAndFlight: Long,
    maxPayloadBytes: Long,
    recentHistoryByteBudget: Long,
    pendingMetadataBytes: Long = 4_096L * 16L * 1024L,
): Long = try {
    val copiedPayload = Math.multiplyExact(Math.multiplyExact(queueAndFlight, maxPayloadBytes), 2L)
    Math.addExact(Math.addExact(copiedPayload, recentHistoryByteBudget), pendingMetadataBytes)
} catch (overflow: ArithmeticException) {
    throw IllegalArgumentException("audit retained-byte reservation overflows", overflow)
}
```

overflow 또는 `checkedAuditReservation(...) > maxBufferedBytes`이면 `IllegalArgumentException`을 던진다. `shutdownTimeout`은 1ns 이상 30s 이하로 확인한다. HTTPS endpoint는 absolute hierarchical `https` URI이며 user-info/query/fragment/control character, localhost, loopback/private/link-local/ULA/CGNAT, IPv4 대체 표기, IPv6 literal을 거부한다. endpoint와 allow-list 항목은 lower-case/trailing-dot 제거 후 exact 비교하고 allow-list가 비어 있으면 거부한다. MEMORY에서 endpoint/authorization을 지정하면 fail closed한다. upstream pending context의 raw `lockName`/`nodeId`/`slotId`는 4,096 entry/15분 TTL로만 bounded되며 aggregate byte budget에는 포함되지 않는다는 한계를 properties KDoc와 테스트에 기록한다.

`application.yml`에는 `MEMORY`, bounded defaults, `management.endpoint.configprops.show-values=never`, `management.endpoint.env.show-values=never`를 추가한다.

- [ ] **Step 4: GREEN 실행**

실행: 동일한 `JobSafetyAuditPropertiesTest` 명령.

예상: defaults, bounds, checked overflow, HTTPS trust, header redaction 테스트가 PASS한다.

- [ ] **Step 5: commit**

```bash
git add leader/job-safety-lab/build.gradle.kts leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/config/JobSafetyAuditProperties.kt leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/config/JobSafetyAuditPropertiesTest.kt leader/job-safety-lab/src/main/resources/application.yml
git commit -m "audit 외부 전송 경계를 bounded 설정으로 고정"
```

예상 Lore trailers: `Constraint`, `Rejected`, `Confidence`, `Scope-risk`, `Directive`, `Tested`, `Not-tested`가 한국어 intent와 함께 포함된다.

## Task 2: sanitized payload encoder와 bounded store를 만든다

**Files:**
- Create: `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/JobSafetyAuditPayloadEncoder.kt`
- Create: `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/RecordingLeaderAuditPayloadEncoder.kt`
- Create: `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/BoundedAuditPayloadStore.kt`
- Create: `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/JobSafetyAuditPayloadEncoderTest.kt`
- Create: `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/BoundedAuditPayloadStoreTest.kt`

- [ ] **Step 1: failing tests 작성**

History acquired/completed/failed와 lifecycle event를 upstream factory로 만들고 JSON tree를 검증한다.

```kotlin
@Test
fun `encoded payload excludes token raw identity tenant customer and exception`() {
    val event = LeaderAuditExportEvent.History.from(
        LeaderLockHistoryRecord(
            lockName = "customer-42-secret-job",
            token = "redis-token-secret",
            kind = LockIdentity.AnnotationKind.SINGLE,
            acquiredAt = Instant.parse("2026-08-30T00:00:00Z"),
            lockedUntil = Instant.parse("2026-08-30T00:01:00Z"),
            status = LeaderHistoryStatus.FAILED,
            errorType = "java.lang.IllegalStateException",
            errorMessage = "customer-42 raw stack detail",
            metadata = mapOf("tenantId" to "tenant-42", "safe" to "value"),
        ),
        LeaderAuditValueSanitizer.Default,
    )
    val body = JobSafetyAuditPayloadEncoder(64 * 1024).encode(event).body()
    val tree = Jackson.defaultJsonMapper.readTree(body)
    tree.toString().contains("redis-token-secret").shouldBeFalse()
    tree.toString().contains("customer-42").shouldBeFalse()
    tree.toString().contains("tenant-42").shouldBeFalse()
    tree.toString().contains("raw stack detail").shouldBeFalse()
    tree.path("kind").asText() shouldBeEqualTo "SINGLE"
}

@Test
fun `store retains only serialized bytes and evicts by exact byte budget`() {
    val store = BoundedAuditPayloadStore(maxEntries = 2, maxBytes = 8)
    store.add("1234".toByteArray())
    store.add("5678".toByteArray())
    store.add("90".toByteArray())
    store.snapshot().map { it.decodeToString() } shouldBeEqualTo listOf("5678", "90")
    store.retainedBytes shouldBeEqualTo 6
}
```

- [ ] **Step 2: RED 실행**

실행: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyAuditPayloadEncoderTest' --tests '*BoundedAuditPayloadStoreTest' --no-parallel --max-workers=1`

예상: encoder/store 타입 부재로 실패한다.

- [ ] **Step 3: 최소 구현**

encoder는 DTO에 `lockName`, `nodeId`, `slotId`, `token`, customer/tenant 원문, raw exception message를 두지 않는다. `Jackson.defaultJsonMapper.writeValueAsBytes`로 직렬화하고 `LeaderAuditHttpPayload.of("application/json", bytes)` 전에 configured max를 확인한다. history와 lifecycle 각각의 DTO 필드는 spec의 occurredAt/kind/status/duration/errorType 및 outcome/lease expiry/attributes만 사용한다.

```kotlin
class JobSafetyAuditPayloadEncoder(private val maxPayloadBytes: Int) : LeaderAuditPayloadEncoder {
    override fun encode(event: LeaderAuditExportEvent): LeaderAuditHttpPayload {
        val dto = when (event) {
            is LeaderAuditExportEvent.History -> HistoryWire.from(event)
            is LeaderAuditExportEvent.Lifecycle -> LifecycleWire.from(event)
        }
        val bytes = Jackson.defaultJsonMapper.writeValueAsBytes(dto)
        require(bytes.size <= maxPayloadBytes) { "audit payload exceeds configured byte bound" }
        return LeaderAuditHttpPayload.of("application/json", bytes)
    }
}
```

`HistoryWire`와 `LifecycleWire`는 각각 spec에 열거한 필드만 갖는 private
`data class`이며 `from` 변환에서 attributes를 정제한다. `RecordingLeaderAuditPayloadEncoder`
는 base encoder 결과의 `body()`를 store에 전달한 뒤 동일 payload를 반환한다. 이
capture 단계는 MEMORY fake와 HTTPS client에 공통으로 연결하고, fake client는
serialized bytes를 자체 보관하지 않는다.

`BoundedAuditPayloadStore`는 `ReentrantLock`으로 deque와 `retainedBytes`를 함께 변경한다. `add`는 입력을 즉시 `copyOf()`하고 하나의 payload가 budget보다 크면 저장하지 않는다. 오래된 bytes를 앞에서 제거해 entry와 exact byte sum을 만족하며 `snapshot()`은 각 byte array의 방어적 복사본을 반환한다. raw request/header/event view를 필드에 저장하지 않는다.

- [ ] **Step 4: GREEN 실행**

실행: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyAuditPayloadEncoderTest' --tests '*BoundedAuditPayloadStoreTest' --no-parallel --max-workers=1`

예상: redaction, configured/hard payload bound, byte eviction, defensive copy가 PASS한다.

- [ ] **Step 5: commit**

```bash
git add leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/JobSafetyAuditPayloadEncoderTest.kt leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/BoundedAuditPayloadStoreTest.kt
git commit -m "audit payload를 token-free bounded bytes로 직렬화"
```

## Task 3: admission-only sink과 memory/HTTPS exporter test seam을 만든다

**Files:**
- Create: `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/AdmissionOnlyLeaderHistorySink.kt`
- Create: `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/InMemoryAuditHttpClient.kt`
- Create: `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/AdmissionOnlyLeaderHistorySinkTest.kt`
- Create: `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/JobSafetyAuditExporterTest.kt`

- [ ] **Step 1: failing tests 작성**

```kotlin
@Test
fun `admission sink returns lifecycle key without persistence`() {
    val sink = AdmissionOnlyLeaderHistorySink()
    val key = sink.recordAcquired(record())
    key.shouldNotBeNull()
    key.lockName shouldBeEqualTo "job-safety:sample"
    sink.deleteOlderThan(Instant.now(), 10) shouldBeEqualTo 0
}

@Test
fun `memory exporter classifies queue drop retry terminal and close`() {
    val firstDelivery = CompletableFuture<HttpResponse<ByteArray>>()
    val fake = InMemoryAuditHttpClient(
        responses = ConcurrentLinkedQueue(listOf(firstDelivery, completedResponse(204))),
    )
    val fixture = exporter(fake, queueCapacity = 1, maxAttempts = 2)
    fixture.exporter.submit(event()) shouldBeEqualTo LeaderAuditSubmitResult.ACCEPTED
    fixture.exporter.submit(event()) shouldBeEqualTo LeaderAuditSubmitResult.DROPPED_QUEUE_FULL
    firstDelivery.complete(completedResponse(429))
    fake.awaitRequestCount(2, timeout = 5.seconds)
    await().atMost(5.seconds).untilAsserted { fixture.exporter.snapshot().accepted shouldBeEqualTo 1 }
    fixture.exporter.close()
    fixture.exporter.submit(event()) shouldBeEqualTo LeaderAuditSubmitResult.DROPPED_CLOSED
}
```

- [ ] **Step 2: RED 실행**

실행: `./gradlew :leader-job-safety-lab:test --tests '*AdmissionOnlyLeaderHistorySinkTest' --tests '*JobSafetyAuditExporterTest' --no-parallel --max-workers=1`

예상: sink/fake fixture 부재로 compile 실패한다.

- [ ] **Step 3: 최소 구현**

`AdmissionOnlyLeaderHistorySink.recordAcquired`는 `LeaderHistoryKey(historyId = UUID.randomUUID().toString(), lockName = record.lockName, token = record.token, slotId = record.slotId)`를 반환하고 나머지 persistence 동작은 `Unit`/`0`으로 끝낸다. 이 sink는 record/key를 저장하는 collection을 갖지 않는다.

`InMemoryAuditHttpClient`는 Java 25 `HttpClient` abstract methods를 구현한다. `sendAsync`에서 `BodyPublisher.contentLength()`를 읽고 status/cancellation만 검증한다. request/header/raw event/payload bytes를 저장하지 않으며, authorization header가 있으면 `IllegalArgumentException`을 던진다. 기본 response는 sentinel endpoint의 204이고 실제 DNS/socket을 열지 않는다. 테스트용 thread-safe response queue와 gated `CompletableFuture<HttpResponse<T>>`는 deterministic close/cancellation을 위해 제공하되 raw request/header를 캡처하지 않는다. `shutdown`, `shutdownNow`, `awaitTermination(Duration)`, `isTerminated`, `close`를 명시적으로 구현하고, `shutdownNow`는 모든 in-flight future를 취소하며 `awaitTermination`은 주어진 timeout 안에 반환한다. `close`는 idempotent이며 fake가 생성한 작업을 남기지 않는다. `awaitRequestCount(expected, timeout)` 자체도 `await().atMost(timeout)`를 사용해 bounded assertion으로 구현한다. queue-full 검증은 첫 delivery를 barrier로 멈춘 상태에서 두 번째 submit을 수행한 뒤 barrier를 해제한다.

실제 HTTPS factory는 다음 계약을 따른다. `encoder` 인자는 base encoder가 아니라
`RecordingLeaderAuditPayloadEncoder(JobSafetyAuditPayloadEncoder(...), store)`이며,
MEMORY와 HTTPS fixture 모두 같은 decorator를 사용한다.

```kotlin
val client = HttpClient.newBuilder()
    .executor(httpExecutor)
    .followRedirects(HttpClient.Redirect.NEVER)
    .build()
val endpoint = LeaderAuditTrustedHttpsEndpoint.trusted(URI(properties.endpoint))
val exporter = HttpLeaderAuditExporter(
    client,
    endpoint,
    properties.headers.asMap(),
    encoder,
    exportOptions,
    LeaderAuditHttpOptions(properties.maxPayloadBytes),
)
```

`LeaderAuditExportOptions`는 properties의 queue/in-flight/retry/time과 caller-owned virtual-thread executor/scheduler를 사용한다. retry status는 upstream의 408/429/5xx/I/O 분류에 위임하고 response body는 폐기한다.

- [ ] **Step 4: GREEN 실행**

실행: 위 exporter 테스트 명령.

예상: queue full drop, 429 retry 후 204 accepted, terminal status, close 후 dropped closed, gated future cancellation이 PASS한다.

- [ ] **Step 5: commit**

```bash
git add leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/AdmissionOnlyLeaderHistorySink.kt leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/InMemoryAuditHttpClient.kt leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/AdmissionOnlyLeaderHistorySinkTest.kt leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/JobSafetyAuditExporterTest.kt
git commit -m "leader history admission과 deterministic audit transport를 연결"
```

## Task 4: report service와 controller/security contract를 추가한다

**Files:**
- Create: `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/JobSafetyAuditReportService.kt`
- Modify: `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/web/JobSafetyApiModels.kt`
- Modify: `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/web/JobSafetyController.kt`
- Modify: `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/web/JobSafetySecurityConfiguration.kt`
- Create: `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/JobSafetyAuditReportServiceTest.kt`
- Modify: `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/web/JobSafetyControllerTest.kt`
- Modify: `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/web/JobSafetySecurityTest.kt`

- [ ] **Step 1: failing tests 작성**

```kotlin
@Test
fun `report exposes bounded events snapshot and fixed meters only`() {
    val report = reportService.report()
    report.transport shouldBeEqualTo "MEMORY"
    report.recentEvents.size shouldBeEqualTo 1
    report.toString().contains("audit.invalid").shouldBeFalse()
    report.toString().contains("Authorization").shouldBeFalse()
    report.meters shouldBeEqualTo FIXED_AUDIT_METERS
}

@ParameterizedTest
@EnumSource(AuditTransport::class)
fun `memory and https retain the same sanitized payload contract`(transport: AuditTransport) {
    val event = fixedAuditEvent()
    val expected = fixture(AuditTransport.MEMORY, event).report()
    val fixture = fixture(transport, event)
    val report = fixture.report()
    report.transport shouldBeEqualTo transport.name
    if (transport == AuditTransport.HTTPS) {
        fixture.fakeClient.awaitRequestCount(1, timeout = 5.seconds)
    }
    report.recentEvents shouldBeEqualTo expected.recentEvents
    report.recentEvents.sumOf { it.toString().toByteArray().size } shouldBeLessThanOrEqualTo 512 * 1024
}

@Test
@WithMockUser(roles = ["JOB_SAFETY_VIEWER"])
fun `viewer cannot read audit report`() {
    mockMvc.get("/api/job-safety/audit").andExpect { status { isForbidden() } }
}

@Test
@WithMockUser(roles = ["JOB_SAFETY_OPERATOR"])
fun `operator reads audit report`() {
    mockMvc.get("/api/job-safety/audit").andExpect { status { isOk() } }
}
```

- [ ] **Step 2: RED 실행**

실행: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyAuditReportServiceTest' --tests '*JobSafetyControllerTest' --tests '*JobSafetySecurityTest' --no-parallel --max-workers=1`

예상: report port/endpoint와 authorization rule 부재로 실패한다.

- [ ] **Step 3: 최소 구현**

report port를 controller가 직접 exporter에 의존하지 않도록 분리한다.

```kotlin
interface JobSafetyAuditReportPort {
    fun report(): JobSafetyAuditReport
}

data class JobSafetyAuditReport(
    val transport: String,
    val enabled: Boolean,
    val recentEvents: List<JsonNode>,
    val snapshot: JobSafetyAuditSnapshot,
    val meters: List<String>,
)
```

service는 store snapshot bytes를 transient `Jackson.defaultJsonMapper.readTree`로 decode하고, snapshot 값은 upstream `LeaderAuditExportSnapshot`의 numeric/boolean fields를 복사한다. endpoint URI, headers, sentinel, token, raw lock/node/slot/customer/tenant/error message는 DTO 또는 metric tag에 넣지 않는다. upstream `MicrometerNames`는 `internal`이므로 import하지 않고, public upstream meter contract의 12개 unique audit meter name과 `outcome` 변형을 `JobSafetyAuditMeterCatalog` private 상수로 복사해 정렬된 list로 고정한다. catalog에는 `leader.audit.export.dropped`의 `queue_full`/`closed` 변형이 하나의 name으로 포함된다는 사실을 test로 고정한다. MEMORY와 HTTPS fixture는 동일 event를 capture decorator에 통과시키고, report의 retained JSON과 byte 합계를 서로 비교한다.

controller에는 다음 method를 추가한다.

```kotlin
@GetMapping("/audit")
fun audit(): JobSafetyAuditReport = auditReport.report()
```

생성자에는 `JobSafetyAuditReportPort`만 주입한다. security chain에는 기존 denyAll보다 앞서 다음 rule을 추가한다.

```kotlin
.requestMatchers(HttpMethod.GET, "/api/job-safety/audit").hasRole(OPERATOR_ROLE)
```

- [ ] **Step 4: GREEN 실행**

실행: 동일 report/controller/security 테스트 명령.

예상: transient decode, fixed meter, sentinel/header/redaction, operator-only access 테스트가 PASS한다.

- [ ] **Step 5: commit**

```bash
git add leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/JobSafetyAuditReportService.kt leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/web leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/audit/JobSafetyAuditReportServiceTest.kt leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/web/JobSafetyControllerTest.kt leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/web/JobSafetySecurityTest.kt
git commit -m "operator 전용 leader audit report 경계를 추가"
```

## Task 5: Spring bean graph와 close ownership을 연결한다

**Files:**
- Create: `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/config/JobSafetyAuditConfiguration.kt`
- Create: `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/config/JobSafetyAuditHttpClientLifecycle.kt`
- Create: `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/config/JobSafetyAuditScope.kt`
- Create: `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/config/JobSafetyAuditShutdownCoordinator.kt`
- Modify: `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/config/JobSafetyConfiguration.kt`
- Modify: `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/JobSafetyRuntimeContractTest.kt`

- [ ] **Step 1: failing tests 작성**

`ApplicationContextRunner` 또는 existing runtime contract fixture로 `MEMORY` default context의 bean graph와 shutdown 상태를 먼저 명시한다.

```kotlin
@Test
fun `job safety leader receives safe history recorder`() {
    contextRunner.run { context ->
        context.getBean(LeaderElector::class.java).shouldNotBeNull()
        context.getBean(SafeLeaderHistoryRecorder::class.java).shouldNotBeNull()
        context.getBean(JobSafetyAuditReportPort::class.java).shouldNotBeNull()
        context.getBean(LeaderAuditExporter::class.java).snapshot().closed.shouldBeFalse()
        context.getBean<JobSafetyAuditScope>("jobSafetyAuditScope").coroutineContext[Job]?.isActive.shouldBeTrue()
    }
}

@Test
fun `context close is bounded and follows owned resource trace`() {
    contextRunner.withPropertyValues("workshop.job-safety.audit.shutdown-timeout=500ms").run { context ->
        val trace = context.getBean(AuditLifecycleTrace::class.java)
        val scope = context.getBean<JobSafetyAuditScope>("jobSafetyAuditScope")
        val executor = context.getBean<ExecutorService>("jobSafetyAuditExecutor")
        val scheduler = context.getBean<ScheduledThreadPoolExecutor>("jobSafetyAuditScheduler")
        val client = context.getBean<HttpClient>("jobSafetyAuditHttpClient")
        scheduler.removeOnCancelPolicy.shouldBeTrue()
        val cancelledRetry = scheduler.schedule({}, 1, TimeUnit.MINUTES)
        cancelledRetry.cancel(false)
        await().atMost(1.seconds).untilAsserted { scheduler.queue.shouldBeEmpty() }
        assertTimeoutPreemptively(Duration.ofSeconds(3)) { context.close() }
        trace.events shouldBeEqualTo listOf(
            "subscription.close", "exporter.close", "client.shutdownNow", "client.awaitTermination",
            "scheduler.shutdownNow", "scheduler.awaitTermination", "executor.shutdownNow",
            "executor.awaitTermination", "scope.close",
        )
        executor.isTerminated.shouldBeTrue()
        scheduler.isTerminated.shouldBeTrue()
        scheduler.queue.shouldBeEmpty()
        client.isTerminated.shouldBeTrue()
        scope.coroutineContext[Job]?.isActive.shouldBeFalse()
    }
}
```

- [ ] **Step 2: RED 실행**

실행: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyRuntimeContractTest' --no-parallel --max-workers=1`

예상: audit configuration bean과 recorder injection이 없어 실패한다.

- [ ] **Step 3: 최소 구현**

`JobSafetyConfiguration`에 `@EnableConfigurationProperties(JobSafetyProperties::class, JobSafetyAuditProperties::class)`를 적용하고 elector bean signature를 다음처럼 확장한다.

```kotlin
fun jobSafetyLeaderElector(
    @Qualifier("jobSafetyRedisConnection") connection: StatefulRedisConnection<String, String>,
    properties: JobSafetyProperties,
    @Qualifier("jobSafetyHistoryRecorder") historyRecorder: SafeLeaderHistoryRecorder,
): ListeningLeaderElector =
    LettuceLeaderElector(
        connection = connection,
        options = LeaderElectionOptions(
            waitTime = properties.defaultTimeout.toKotlinDuration(),
            leaseTime = properties.fencing.leaseTtl.toKotlinDuration(),
            autoExtend = true,
        ),
        historyRecorder = historyRecorder,
    ).withListeners()
```

`JobSafetyAuditConfiguration` bean 순서는 다음 ownership을 명시한다.

1. `BoundedAuditPayloadStore`, base encoder와 `RecordingLeaderAuditPayloadEncoder`를 만든다.
2. 이름을 고정한 `jobSafetyAuditExecutor`와 `jobSafetyAuditScheduler`를 caller-owned virtual-thread/single scheduler로 만들고 scheduler의 `removeOnCancelPolicy=true`를 설정한다.
3. MEMORY이면 `InMemoryAuditHttpClient`와 sentinel trusted endpoint를 사용하고, HTTPS이면 `HttpClient.newBuilder().executor(...).followRedirects(NEVER)`와 properties-validated endpoint를 사용한다. raw JDK client는 `jobSafetyAuditHttpClient` 이름으로 만들고 Spring의 implicit `close` destroy method는 비활성화한다.
4. `HttpLeaderAuditExporter`를 만들고 `MicrometerLeaderAuditExporter`가 유일하게 delegate를 소유하도록 한다. exporter, `jobSafetyAuditSubscription`, `jobSafetyAuditHttpClientLifecycle`, raw client, scheduler, executor, scope bean의 implicit destroy method는 모두 비활성화하고, 아래 coordinator만 `@Bean(name = "jobSafetyAuditShutdownCoordinator", destroyMethod = "close")`로 등록한다.
5. `ExportingLeaderHistorySink(AdmissionOnlyLeaderHistorySink(), micrometerExporter, LeaderAuditValueSanitizer.Default)`와 `SafeLeaderHistoryRecorder`를 만든다.
6. `JobSafetyAuditScope`(`SupervisorJob() + Dispatchers.Default`, `close()`에서 job 취소)를 `jobSafetyAuditScope` bean으로 만들고, `LeaderElectionEventExportSubscription`과 application-owned scope로 listening elector publisher를 구독한다. scope와 subscription에는 `@Qualifier("jobSafetyAuditScope")`를 사용하며 subscription은 `jobSafetyAuditExporter`보다 먼저, scope보다 먼저 닫히도록 dependency를 명시한다.
7. report port는 exporter/store/properties를 읽지만 secret/endpoint를 반환하지 않는다.
8. `JobSafetyAuditShutdownCoordinator`는 subscription, exporter, client lifecycle, scheduler,
   executor, scope를 생성자 qualifier로 받아 context close 시작 시 `System.nanoTime()` 기반
   단일 `shutdownDeadline`을 계산한다. `remaining(deadline)`을 각 bounded await에 전달하고
   `subscription.close → exporter.close → clientLifecycle.shutdownNow/await →
   scheduler.shutdownNow/await → executor.shutdownNow/await → scope.close`를 한 번만
   실행한다. 각 resource의 Spring destroy callback은 빈 문자열로 설정해 coordinator
   외부에서 중복 종료가 발생하지 않게 한다. coordinator는 idempotent이고 timeout/interrupt
   뒤에도 다음 resource를 정리하며 원래 interrupt 상태를 보존한다.

Spring destroy ownership는 coordinator bean 하나에 집중한다. coordinator가 context close
시작 시 하나의 monotonic deadline을 만들고 `remaining(deadline)`을 각 단계에 전달하므로
resource별 timeout 합산으로 aggregate deadline을 초과하지 않는다. Java 25 `HttpClient.close()`를
직접 호출하지 않아 긴 await가 발생하지 않도록 lifecycle wrapper가 bounded contract를
소유한다. `jobSafetyAuditExportOptions`, `jobSafetyAuditHttpClient`,
`jobSafetyAuditHttpClientLifecycle`, `jobSafetyAuditExecutor`, `jobSafetyAuditScheduler`,
`jobSafetyAuditScope`, `jobSafetyAuditSubscription`, `jobSafetyAuditExporter`,
`jobSafetyAuditShutdownCoordinator` qualifier를 모든 injection 지점에 명시하고
`javap java.net.http.HttpClient` 결과를 review evidence로 남긴다. `AuditLifecycleTrace`
recording fake는 각 close 단계에서 event를 남긴다. test는 delayed await fake와
`assertTimeoutPreemptively`로 context close가 aggregate timeout 안에 반환하는지,
trace 순서, `isTerminated`, scheduler queue 및 exporter의 queue/inFlight/scheduledRetries
0, scope `coroutineContext[Job]?.isActive == false`를 함께 확인한다.

- [ ] **Step 4: GREEN 실행**

실행: runtime contract test 명령.

예상: context startup, recorder injection, exporter decorator ownership, shutdown lifecycle가 PASS한다.

- [ ] **Step 5: commit**

```bash
git add leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/config/JobSafetyAuditConfiguration.kt leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/config/JobSafetyAuditHttpClientLifecycle.kt leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/config/JobSafetyAuditScope.kt leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/config/JobSafetyAuditShutdownCoordinator.kt leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/config/JobSafetyConfiguration.kt leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/JobSafetyRuntimeContractTest.kt
git commit -m "job safety leader에 audit exporter lifecycle을 주입"
```

## Task 6: 실제 leader lifecycle 회귀와 concurrency/cancellation proof를 고정한다

**Files:**
- Modify: `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/JobSafetyEndToEndIntegrationTest.kt`
- Modify: `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/JobSafetyContextRestartIntegrationTest.kt`
- Modify: `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/JobSafetyReadmeContractTest.kt`
- Modify: `docs/review/issue-867-workflow-checklist.md`

- [ ] **Step 1: failing integration/latency tests 작성**

실제 Redis/PostgreSQL fixture에서 `LeaderElectionPort.tryAcquire(JobName("monthly-summary"))`로 lease를 얻고 `release()`하는 실제 경로를 호출한 뒤 report snapshot과 retained payload를 bounded await로 확인한다. 이 경로는 `ListeningLeaderElector`의 `Elected`/`Revoked` lifecycle과 `ExportingLeaderHistorySink`의 acquired/completed history를 함께 만든다. 별도 concurrent test는 gated fake를 사용해 32개 submit admission이 caller를 막지 않고 queue full을 반환하는 upper bound를 확인한다. 긴 lock/node/slot 입력은 report/payload/metric/workshop local log에 나오지 않는지 확인하며 upstream `SafeLeaderHistoryRecorder` failure warning은 별도 core logging contract로 제외하고, pending raw identity의 heap byte bound를 주장하지 않는다.

```kotlin
@Test
@Tag("integration")
fun `leader lifecycle exports audit without changing postgres authority`() {
    seedAuthorityAndResource()
    val lease = leaderElection.tryAcquire(JobName("monthly-summary"))
    lease.shouldNotBeNull()
    lease.release()
    await().atMost(5.seconds).untilAsserted {
        auditReport.report().recentEvents.size shouldBeGreaterThanOrEqualTo 2
    }
    repositories.resource.find(CONFLICT_KEY)?.summaryValue shouldBeEqualTo 0L
}
```

- [ ] **Step 2: RED 실행**

실행: `./gradlew :leader-job-safety-lab:integrationTest --tests '*JobSafetyEndToEndIntegrationTest*' --no-parallel --max-workers=1`

예상: wiring 이전에는 report bean/lifecycle event가 없어 실패한다. container startup failure와 assertion failure를 구분해 기록한다.

- [ ] **Step 3: 최소 구현/수정**

기존 scenario와 repository assertion을 바꾸지 않고 audit assertion만 추가한다. fixture에는 실제 `LeaderElectionPort`와 `JobSafetyAuditReportPort`를 주입하고, `await().untilAsserted`로 비동기 encoder/store 반영을 기다린다. failure path는 sink 단위 테스트의 `recordFailed`에서 error type만 bounded field로 남기고 raw message는 payload/store/workshop local log에 남기지 않는다. upstream `SafeLeaderHistoryRecorder`가 남길 수 있는 raw failure warning은 이 예제의 logging 소유 범위 밖이다. cancellation test는 `CompletableFuture` cancellation이 underlying fake future에 전파되고 close 후 scheduled retry가 0인지 검증한다.

- [ ] **Step 4: GREEN 실행**

실행 순서:

```bash
./gradlew :leader-job-safety-lab:integrationTest --tests '*JobSafetyEndToEndIntegrationTest*' --no-parallel --max-workers=1
./gradlew :leader-job-safety-lab:test --tests '*JobSafetyAuditExporterTest' --no-parallel --max-workers=1
```

예상: 기존 PostgreSQL authority와 audit lifecycle 모두 PASS하며 close 후 queue/inFlight/scheduledRetries가 0이다.

- [ ] **Step 5: commit**

```bash
git add leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/JobSafetyEndToEndIntegrationTest.kt leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/JobSafetyContextRestartIntegrationTest.kt leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/JobSafetyReadmeContractTest.kt docs/review/issue-867-workflow-checklist.md
git commit -m "leader audit lifecycle과 기존 authority 회귀를 증명"
```

## Task 7: 양국 README와 coverage/workflow/stale 검증을 갱신한다

**Files:**
- Modify: `leader/job-safety-lab/README.md`
- Modify: `leader/job-safety-lab/README.ko.md`
- Modify: `docs/coverage-matrix.md`
- Inspect/modify only if registration is missing: `.github/workflows/Examples.yml`, `scripts/smoke-validate.sh`, stale-check helper paths.
- Create: `docs/lessons/2026-08-30-issue-867-leader-audit-export.md`

- [ ] **Step 1: README contract tests 작성/RED**

기존 `JobSafetyReadmeContractTest`에 EN/KO 양쪽이 다음 exact tokens를 포함하는지 추가한다.

```kotlin
listOf("workshop.job-safety.audit.transport", "MEMORY", "HTTPS", "/api/job-safety/audit", "max-payload-bytes")
    .forEach { token -> english shouldContain token; korean shouldContain token }
```

실행: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyReadmeContractTest' --no-parallel --max-workers=1`

예상: README에 audit section이 없어 실패한다.

- [ ] **Step 2: README 최소 갱신/GREEN**

양국 README에 다음 내용을 같은 순서와 의미로 추가한다.

1. `MEMORY`가 endpoint/DNS/socket/credential 없이 sentinel fake에서 동작한다는 설명.
2. `HTTPS` 설정 예시(`transport`, `endpoint`, `allowed-hosts`, `headers.authorization`)와 exact host/redirect/header/redaction 경계.
3. `GET /api/job-safety/audit`가 `JOB_SAFETY_OPERATOR` 전용이며 recent bounded payload, snapshot, fixed meter names만 반환한다는 curl.
4. queue full/drop/retry/close/cancel이 best-effort audit 관찰이고 PostgreSQL history/authority/exactly-once 외부 delivery를 대체하지 않는다는 제한.
5. Authorization/Actuator 값을 환경변수로 주입하고 운영 wire debug logging을 켜지 않는다는 주의.

`docs/coverage-matrix.md` leader row에는 기존 example의 audit exporter, MEMORY/HTTPS, operator report와 해당 test classes를 기록한다. 새 module은 만들지 않으므로 settings registration 변경은 하지 않는다. `Examples.yml`, smoke, stale helper가 이미 `leader/job-safety-lab`를 포함하는지 read-only로 확인하고, 누락될 때만 같은 변경에 registry를 추가한다.

lesson에는 context, public upstream API 선택, 실패했던 spec review finding과 repair, RED/GREEN/test evidence, 다음 modifiers를 위한 aggregate checked arithmetic와 raw identity boundary guard를 한국어로 기록한다.

- [ ] **Step 3: GREEN/locale parity 검증**

실행:

```bash
./gradlew :leader-job-safety-lab:test --tests '*JobSafetyReadmeContractTest' --no-parallel --max-workers=1
git diff --check
```

예상: README contract와 Markdown whitespace가 PASS한다. EN/KO 섹션의 config key, command, endpoint, security caveat가 의미상 동등한지 수동 read-back한다.

- [ ] **Step 4: commit**

```bash
git add leader/job-safety-lab/README.md leader/job-safety-lab/README.ko.md docs/coverage-matrix.md docs/lessons/2026-08-30-issue-867-leader-audit-export.md .github/workflows/Examples.yml scripts/smoke-validate.sh
git commit -m "leader audit 예제와 운영 학습 문서를 양국 README에 반영"
```

## Task 8: Type A verifier, cleanup, performance/stability scan을 실행한다

**Files:**
- Create: `docs/review/2026-08-30-issue-867-verifier.md`
- Modify: `docs/review/issue-867-workflow-checklist.md`
- Optional cleanup only after regression tests: Kotlin audit source files.

- [ ] **Step 1: targeted verification**

실행 순서:

```bash
./gradlew :leader-job-safety-lab:test --no-parallel --max-workers=1
./gradlew detekt --no-parallel --max-workers=1
./gradlew :leader-job-safety-lab:integrationTest --no-parallel --max-workers=1
./gradlew projects --no-parallel --max-workers=1
git diff --check
```

예상: fast test, detekt, sequential integration, project graph, diff check가 모두 PASS한다. integration/container 실패는 skip으로 분류하지 말고 원인과 재실행 결과를 기록한다.

- [ ] **Step 2: verifier artifact 작성**

`docs/review/2026-08-30-issue-867-verifier.md`에 Issue criterion별 source/test/command evidence, BOM/catalog proof, resource ownership, security grep, README parity, workflow/stale result, known gap(upstream pending raw identity heap byte bound)을 기록한다. `Required checks: X/Y; N/A: N; Blocked: 0`을 실제 count로 계산한다.

- [ ] **Step 3: cleanup/performance scan**

`ai-slop-cleaner`는 중복 wrapper/불필요한 DTO/긴 함수가 실제로 발견될 때만 사용한다. 먼저 regression tests를 green으로 잠근 뒤 한 smell-focused pass를 적용하고 detekt/test를 다시 실행한다. performance/stability scan은 queue admission latency, exact retained bytes, cancellation, executor/scheduler termination, Testcontainers serialization을 측정하며 production throughput이나 exactly-once를 주장하지 않는다.

- [ ] **Step 4: commit**

```bash
git add docs/review/2026-08-30-issue-867-verifier.md docs/review/issue-867-workflow-checklist.md
git commit -m "issue 867 수용 기준과 저장소 hazard 검증을 기록"
```

## Task 9: final six-lane code review와 lesson/PR 전 검증을 수렴한다

**Files:**
- Create: `docs/review/2026-08-30-issue-867-pre-pr.md`
- Modify: `docs/review/issue-867-workflow-checklist.md`
- Modify if review requires: implementation/docs/tests from Tasks 1–8.

- [ ] **Step 1: six perspectives 실행**

현재 final diff와 verifier artifact를 기준으로 performance, stability, security, operator/Ops, developer/API, user/caller 여섯 lane을 read-only로 실행한다. 각 lane은 P0/P1/P2/P3, exact file/line evidence, required edit, rerun lane만 반환한다. child slot이 부족하면 bounded wait 후 main fallback을 사용하고 그 사실을 artifact에 기록한다.

- [ ] **Step 2: main integration 및 repair**

`docs/review/2026-08-30-issue-867-pre-pr.md`에 duplicate/contradiction, issue acceptance, Korean public prose, live dependency/BOM, workflow/stale, rollback, CI evidence를 통합한다. P0/P1이 하나라도 있으면 PR 생성을 중지하고 정확한 파일을 수정한 뒤 영향을 받은 lane과 tests를 재실행한다. P2/P3은 수정하거나 follow-up 근거를 기록한다.

- [ ] **Step 3: writer gate와 final checks**

통합 review와 lesson 모두 `SPW-01`~`SPW-05=PASS`를 포함한다. 실행:

```bash
git diff --check
placeholder_regex='T''ODO|FIX''ME|T''BD|implement'' later|추''후|나''중에'
if rg -n -i "$placeholder_regex" docs/superpowers docs/review docs/lessons leader/job-safety-lab; then exit 1; else echo 'placeholder scan: clean'; fi
```

예상: diff/placeholder 검사 PASS, integrated review P0=0/P1=0.

- [ ] **Step 4: commit**

```bash
git add docs/review/2026-08-30-issue-867-pre-pr.md docs/review/issue-867-workflow-checklist.md
git commit -m "issue 867 pre-PR review를 P0 P1 없이 수렴"
```

## Task 10: push, Korean PR, live CI와 merge-ready 상태를 증명한다

**Files/side effects:**
- Push branch `feat/issue-867-leader-audit-export` to origin.
- Create/update PR with Korean title/body and exact `Closes #867`.
- Modify live GitHub metadata only after re-reading authority and exact head.

- [ ] **Step 1: local final state 확인**

```bash
git status --short
git log -1 --format='%H%n%B'
git diff origin/develop...HEAD --stat
git diff --check
```

예상: intended files만 tracked/committed, Lore trailers 존재, diff clean.

- [ ] **Step 2: push와 PR 생성**

```bash
git push -u origin feat/issue-867-leader-audit-export
gh pr create --base develop --head feat/issue-867-leader-audit-export --title '[2.0.0] 기존 leader/job-safety-lab에 leader audit export 경계 적용' --body-file /tmp/issue-867-pr-body.md
```

PR 본문은 한국어로 작성하고 마지막에 다음 exact section을 둔다.

```markdown
## DoD 상태

- [x] Issue #867 수용 기준을 코드·테스트·문서에 매핑
- [x] 기본 MEMORY transport가 외부 endpoint/DNS/socket 없이 동작
- [x] 명시적 trusted HTTPS와 bounded retry/drop/close/cancel, redaction 검증
- [x] PostgreSQL authority와 기존 시나리오 보존
- [x] versionless BOM/catalog, README EN/KO, matrix/workflow/stale 검증
- [x] targeted/module/integration 검증 근거 첨부

Closes #867
```

PR labels, milestone `2.0.0`, assignee `debop`를 live issue와 parity로 맞춘다. PR body에는 exact tested commands, known upstream raw identity heap-byte limitation, merge 전 pending approval을 적는다.

- [ ] **Step 3: live CI/review read-back**

실행: `gh pr view <number> --json number,url,headRefOid,baseRefName,body,labels,milestone,assignees,reviews,statusCheckRollup` 및 `gh pr checks <number> --watch`.

예상: remote head가 local exact SHA와 일치하고 required checks가 모두 SUCCESS다. 실패하면 `$gh-fix-ci` 절차로 raw log를 진단한 뒤 최소 수정/재검증한다.

- [ ] **Step 4: merge-ready report에서 중지**

checklist A-10/A-11과 common CG-11~CG-18을 current head evidence로 채운다. 최종 보고에는 `Required checks: X/Y; N/A: N; Blocked: 0`, exact PR/head, unchecked `CG-16`/merge approval gate를 포함한다. 새 fresh `승인` 없이는 merge/auto-merge/branch deletion을 실행하지 않는다.

## Traceability와 rollback

| Issue/Spec requirement | Plan task | Proof |
| --- | --- | --- |
| lifecycle report와 Micrometer decorator 연결 | 4–5 | report/controller/runtime tests, integration lifecycle |
| 기본 memory/no network | 1, 3, 5, 7 | fake client, properties/context tests, README curl |
| trusted HTTPS opt-in | 1, 3, 5 | endpoint/header/status/redirect tests |
| payload bound/redaction | 1–2, 4, 8 | encoder/tree/grep/aggregate overflow tests |
| queue/drop/retry/close/cancel | 3, 6, 8 | scripted/gated exporter and lifecycle tests |
| PostgreSQL authority 유지 | 3, 6 | admission-only sink and existing integration assertions |
| versionless BOM/docs/matrix/workflow/stale/lesson | 1, 7–10 | catalog/README/registry/lesson/verifier evidence |

Rollback은 Tasks 1–5의 audit configuration/adapter/encoder/report와 Jackson alias를 함께 revert하며, 기존 `JobSafetyConfiguration`의 two-argument elector 호출 및 PostgreSQL/Redis scenario path는 별도 commit으로 보존한다. upstream audit API가 snapshot에서 사라지거나 생성자 계약이 바뀌면 code 작업을 멈추고 producer release/scope를 먼저 복구한다. lifecycle timing failure는 subscription close → exporter close → HttpClient `shutdownNow`/bounded `awaitTermination` → scheduler/executor 종료 순서를 확인한 뒤 targeted/module/integration command를 직렬 재실행한다.

## SPW writer gate

- **SPW-01:** artifact=implementation plan, 독자=workshop contributor/operator, 목적=Issue #867의 public audit integration을 실행 가능한 TDD task로 분해, 근거=approved spec·live Issue #867·local/upstream source, 미확정=upstream pending raw identity의 heap byte bound는 명시된 범위 밖이다.
- **SPW-02:** 파일 책임 지도, 10개 ordered task, RED/GREEN commands, security/HTTP/lifecycle tests, docs/workflow hazards, traceability와 rollback을 포함했다.
- **SPW-03:** 한국어 기술 문체로 작성하고 Kotlin/API/path/command/metric/HTTP tokens는 원문을 보존했다. 모호한 future action과 홍보 표현을 사용하지 않았다.
- **SPW-04:** `bluetape4k-dependencies` BOM, versionless `bluetape4k-jackson3`, Java 25 `HttpClient`, upstream public audit classes, Spring Security/Actuator 경계를 source와 대조했다.
- **SPW-05:** Markdown heading/table/code fence, task signature consistency, placeholder scan 대상과 expected outputs를 read-back했다. plan review에서 transport-independent capture, bounded HTTP shutdown, listening publisher lifecycle, secret-independent header equality, gated queue test와 actual integration fixture를 반영했고 모든 spec acceptance criterion이 Task 1–10에 매핑됨을 확인했다.

**Plan stop condition:** Task 10 Step 4의 live PR/CI merge-ready report까지 완료하되, fresh exact-head `승인` 전에는 merge/cleanup을 하지 않는다.
