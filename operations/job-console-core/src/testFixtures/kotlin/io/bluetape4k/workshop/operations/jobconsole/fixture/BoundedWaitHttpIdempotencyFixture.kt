package io.bluetape4k.workshop.operations.jobconsole.fixture

import io.bluetape4k.junit5.http.idempotency.BoundedWaitHttpIdempotencyAdapter
import io.bluetape4k.junit5.http.idempotency.BoundedWaitHttpIdempotencyConformanceConfig
import io.bluetape4k.junit5.http.idempotency.HttpIdempotencyRequest
import io.bluetape4k.junit5.http.idempotency.HttpIdempotencyResponse
import io.bluetape4k.junit5.http.idempotency.HttpIdempotencyQuiescence
import io.bluetape4k.workshop.operations.jobconsole.api.FailureMode
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.idempotency.AbandonReason
import io.bluetape4k.workshop.operations.jobconsole.idempotency.InterruptibleWaitStrategy
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionClock
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionCommand
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionIdempotencyCoordinator
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionIdempotencyPolicy
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOwnerAction
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOutcome
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOwnership
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionSnapshotPolicy
import io.bluetape4k.workshop.operations.jobconsole.idempotency.PreparedJobSubmission
import io.bluetape4k.workshop.operations.jobconsole.idempotency.PollResult
import io.bluetape4k.workshop.operations.jobconsole.idempotency.ReplayableJobSubmission
import io.bluetape4k.workshop.operations.jobconsole.idempotency.Reservation
import io.bluetape4k.workshop.operations.jobconsole.idempotency.WaiterRegistration
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepositoryException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets.UTF_8
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Test-fixture-only adapter backed by the same coordinator used by the live adapters.
 *
 * The fixture deliberately keeps persistence in memory so that the upstream HTTP contract can
 * run quickly and deterministically. Framework tests wrap [exchange] in their real HTTP boundary;
 * this class never ships with a production application.
 */
class BoundedWaitHttpIdempotencyFixture(
    private val config: BoundedWaitHttpIdempotencyConformanceConfig,
) : BoundedWaitHttpIdempotencyAdapter, AutoCloseable {
    private val policy =
        JobSubmissionIdempotencyPolicy(
            ownerLease = Duration.ofMinutes(2),
            prepareDeadline = Duration.ofMinutes(1),
            waiterTimeout = config.waitTimeout,
            maxWaitersPerKey = config.maxWaitersPerKey,
            retention = config.retention,
            maxKeyBytes = config.maxIdempotencyKeyBytes,
            maxBodyBytes = config.maxRequestBodyBytes,
            maxReplayBytes = config.maxReplayBodyBytes,
            maxHeaderNames = config.maxReplayHeaderNames,
            maxHeaderValues = config.maxReplayValuesPerHeader,
            maxHeaderValueBytes = config.maxReplayHeaderValueBytes,
            maxAggregateHeaderBytes = config.maxReplayHeaderBytes,
        )
    private val virtualClock = FixtureClock()
    private val waitStrategy = FixtureVirtualWaitStrategy(virtualClock::monotonicNanos)
    private val repository = FixtureRepository(policy, virtualClock)
    private val prepareExecutor = Executors.newVirtualThreadPerTaskExecutor()
    private val coordinator =
        JobSubmissionIdempotencyCoordinator(
            repository = repository,
            policy = policy,
            clock = virtualClock,
            waiter = waitStrategy,
            snapshotPolicy = JobSubmissionSnapshotPolicy.syntheticForTests(policy),
            waitJitter = { _, interval -> interval },
            prepareExecutor = prepareExecutor,
            databasePermits = Semaphore(policy.idempotencyDbConcurrency, true),
        )
    private val controls = ConcurrentHashMap<CommandKey, OwnerControl>()
    private val ownerStartCounts = ConcurrentHashMap<CommandKey, AtomicInteger>()
    private val ownerStartObservations = ConcurrentHashMap<CommandKey, AtomicInteger>()
    private val ownerOwnerships = ConcurrentHashMap<Job, JobSubmissionOwnership>()
    private val suppressedContentTypes = ConcurrentHashMap.newKeySet<CommandKey>()
    private val activeOwnerControls = ConcurrentHashMap<CommandKey, OwnerControl>()
    private val activeJobs = ConcurrentHashMap.newKeySet<Job>()
    private val sideEffects = ConcurrentHashMap<CommandKey, AtomicInteger>()
    private val closed = AtomicInteger()
    private val jsonMapper: JsonMapper =
        JsonMapper.builder(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build(),
        ).addModule(kotlinModule())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .build()

    init {
        repository.onMutation = waitStrategy::signal
    }

    override suspend fun exchange(request: HttpIdempotencyRequest): HttpIdempotencyResponse {
        val job = coroutineContext[Job]
        if (job != null) activeJobs.add(job)
        try {
            return exchangeInternal(request, job)
        } catch (cancelled: CancellationException) {
            job?.let(::abandonCancelledOwner)
            throw cancelled
        } catch (failure: Throwable) {
            if (job?.isCancelled == true) job.let(::abandonCancelledOwner)
            throw failure
        } finally {
            if (job != null) activeJobs.remove(job)
        }
    }

    override suspend fun awaitOwnerStarted(request: HttpIdempotencyRequest) {
        val key = keyOf(request)
        val target = ownerStartObservations.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
        withTimeout(config.scenarioTimeout.toMillis()) {
            while ((ownerStartCounts[key]?.get() ?: 0) < target) yield()
        }
    }

    override suspend fun awaitWaiterCount(request: HttpIdempotencyRequest, expected: Int) {
        withTimeout(config.scenarioTimeout.toMillis()) {
            while (true) {
                val actual = repository.waiterCount(keyOf(request))
                if (actual == expected) {
                    return@withTimeout
                }
                yield()
            }
        }
    }

    override suspend fun completeOwner(request: HttpIdempotencyRequest, outcome: HttpIdempotencyResponse) {
        val ownerControl = activeOwnerControls[keyOf(request)] ?: control(request)
        val key = keyOf(request)
        if (nominatesHeader(outcome, "content-type")) suppressedContentTypes += key
        ownerControl.completion.complete(OwnerSignal.Complete(sanitize(outcome)))
        if (ownerControl.holdDelivery) {
            withTimeout(config.scenarioTimeout.toMillis()) { ownerControl.finalized.await() }
        }
        waitStrategy.signal()
    }

    override suspend fun holdOwnerResponseDelivery(request: HttpIdempotencyRequest) {
        (activeOwnerControls[keyOf(request)] ?: control(request)).holdDelivery = true
    }

    override suspend fun releaseOwnerResponseDelivery(request: HttpIdempotencyRequest) {
        (activeOwnerControls[keyOf(request)] ?: control(request)).releaseDelivery()
    }

    override suspend fun abandonOwner(request: HttpIdempotencyRequest, outcome: HttpIdempotencyResponse) {
        (activeOwnerControls[keyOf(request)] ?: control(request)).completion.complete(OwnerSignal.Abandon(sanitizeTransient(outcome)))
        waitStrategy.signal()
    }

    override suspend fun advanceTimeBy(duration: Duration) {
        require(!duration.isNegative) { "duration must not be negative" }
        val finalPollGuard = Duration.ofMillis(100)
        val adjustedDuration =
            if (duration >= config.waitTimeout.minusNanos(1) && duration < config.waitTimeout) {
                // The coordinator reserves a 1 ms final-poll budget. Keep the synthetic
                // just-before-deadline case outside that guard so the owner can complete first.
                config.waitTimeout.minus(finalPollGuard)
            } else {
                duration
            }
        virtualClock.advance(adjustedDuration)
        waitStrategy.signal()
    }

    override suspend fun resetScenario() {
        withContext(NonCancellable) {
            activeJobs.toList().forEach(Job::cancel)
            withTimeout(config.scenarioTimeout.toMillis()) {
                while (activeJobs.isNotEmpty()) {
                    yield()
                }
            }
            controls.values.forEach(OwnerControl::releaseDelivery)
            controls.clear()
            ownerStartCounts.clear()
            ownerStartObservations.clear()
            ownerOwnerships.clear()
            suppressedContentTypes.clear()
            activeOwnerControls.clear()
            repository.clear()
            sideEffects.clear()
            virtualClock.reset()
            waitStrategy.signal()
        }
    }

    override fun sideEffectCount(request: HttpIdempotencyRequest): Int {
        val scope = resolveScope(request.authenticationProfile) ?: return 0
        val rawKey = request.idempotencyKeys.singleOrNull() ?: return 0
        val keyHash = runCatching { keyHash(scope, rawKey) }.getOrNull() ?: return 0
        return sideEffects[CommandKey(scope.tenantId, scope.submitterHash, keyHash)]?.get() ?: 0
    }

    override fun quiescence(): HttpIdempotencyQuiescence =
        HttpIdempotencyQuiescence(
            activeWaiters = repository.totalWaiters(),
            openGates = controls.values.count(OwnerControl::isDeliveryHeld),
            activeChildTasks = activeJobs.size,
        )

    override fun close() {
        if (closed.compareAndSet(0, 1)) {
            prepareExecutor.shutdownNow()
            waitStrategy.signal()
        }
    }

    private suspend fun exchangeInternal(request: HttpIdempotencyRequest, ownerJob: Job?): HttpIdempotencyResponse {
        val scope = resolveScope(request.authenticationProfile) ?: return unauthorized(request.authenticationProfile)
        val rawKey = request.idempotencyKeys.singleOrNull() ?: return invalidRequest()
        if (rawKey.isEmpty() || rawKey.hasUnpairedSurrogate() || rawKey.any { it.code !in 0x21..0x7e } || rawKey.contains(',') ||
            rawKey.toByteArray(UTF_8).size > config.maxIdempotencyKeyBytes
        ) {
            return invalidRequest()
        }
        val keyHash = runCatching { keyHash(scope, rawKey) }.getOrElse { return invalidRequest() }
        val bodyBytes = request.requestBody.toByteArray(UTF_8)
        if (bodyBytes.size > config.maxRequestBodyBytes) return oversizedRequest()
        if (request.requestBody.hasUnpairedSurrogate()) return invalidRequest()
        val canonicalBody = runCatching { canonicalJson(request.requestBody) }.getOrElse { return invalidRequest() }
        val commandRequest = SubmitJobRequest(JobType.DOCUMENT_EXPORT, 1, FailureMode.NONE)
        val key = CommandKey(scope.tenantId, scope.submitterHash, keyHash)
        controls.compute(key) { _, existing ->
            if (existing == null || existing.isDone()) OwnerControl() else existing
        }
        val command =
            JobSubmissionCommand(
                scope = scope,
                keyHash = keyHash,
                requestFingerprint = fingerprint(request.operation, request.resourceIdentity, canonicalBody),
                request = commandRequest,
                policyFingerprint = policy.fingerprint,
            )
        val outcome =
            try {
                runInterruptible(Dispatchers.IO) {
                    waitStrategy.beginWaiter()
                    try {
                        coordinator.execute(
                            command,
                            ownerAction(request, key, commandRequest, ownerJob),
                        )
                    } finally {
                        waitStrategy.endWaiter()
                    }
                }
            } catch (failure: JobRepositoryException) {
                if (failure.code == JobProblemCode.IDEMPOTENCY_SNAPSHOT_REJECTED) {
                    sideEffects[key]?.let { counter ->
                        if (counter.decrementAndGet() <= 0) sideEffects.remove(key, counter)
                    }
                    return unsafeSnapshot()
                }
                throw failure
            }
        return mapOutcome(outcome, key)
    }

    private fun ownerAction(
        request: HttpIdempotencyRequest,
        key: CommandKey,
        commandRequest: SubmitJobRequest,
        ownerJob: Job?,
    ): JobSubmissionOwnerAction =
        object : JobSubmissionOwnerAction {
            override fun prepare(ownership: JobSubmissionOwnership): PreparedJobSubmission {
                val control = controls[key] ?: error("owner control missing")
                activeOwnerControls[key] = control
                ownerJob?.let { ownerOwnerships[it] = ownership }
                sideEffects.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
                ownerStartCounts.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
                control.started.complete(Unit)
                return when (val signal = control.completion.await()) {
                    is OwnerSignal.Complete -> {
                        prepared(commandRequest, signal.response)
                    }
                    is OwnerSignal.Abandon -> {
                        activeOwnerControls.remove(key, control)
                        throw FixtureAbandon(signal.response)
                    }
                }
            }

        override fun commit(
                connection: Connection,
                ownership: JobSubmissionOwnership,
                prepared: PreparedJobSubmission,
        ): ReplayableJobSubmission {
            val finalized = repository.finalizeOwner(connection, ownership, prepared, virtualClock.databaseNow())
            val control = activeOwnerControls[key] ?: controls[key] ?: return finalized
            control.finalized.complete(Unit)
            control.awaitDeliveryIfHeld()
            activeOwnerControls.remove(key, control)
            return finalized
            }
        }

    private fun abandonCancelledOwner(ownerJob: Job) {
        val ownership = ownerOwnerships.remove(ownerJob) ?: return
        activeOwnerControls.remove(CommandKey(ownership.scope.tenantId, ownership.scope.submitterHash, ownership.keyHash))
        repository.abandon(ownership, AbandonReason.CANCELLED, virtualClock.databaseNow())
    }

    private fun prepared(request: SubmitJobRequest, response: HttpIdempotencyResponse): PreparedJobSubmission =
        response.headers["content-type"].orEmpty().let { contentTypeValues ->
            val validContentType = contentTypeValues.singleOrNull()?.takeIf { value ->
                value.toByteArray(UTF_8).size <= policy.maxHeaderValueBytes &&
                    ("content-type".toByteArray(UTF_8).size + value.toByteArray(UTF_8).size) <= policy.maxAggregateHeaderBytes &&
                    value.all { it.code in 0x20..0x7e }
            }
            PreparedJobSubmission(
                request = request,
                responseStatus = response.statusCode,
                responseBody = response.body.toByteArray(UTF_8),
                responseContentType = validContentType ?: if (contentTypeValues.isEmpty()) "application/json" else "invalid-content-type",
                responseHeaders = response.headers.filterKeys { it != "content-type" && it != "idempotency-replayed" },
            )
        }

    private fun mapOutcome(outcome: JobSubmissionOutcome, key: CommandKey): HttpIdempotencyResponse =
        when (outcome) {
            is JobSubmissionOutcome.OwnerCompleted -> snapshotResponse(outcome.snapshot, false, key)
            is JobSubmissionOutcome.Replayed -> snapshotResponse(outcome.snapshot, true, key)
            JobSubmissionOutcome.Conflict -> problem(409, "idempotency_key_reused")
            JobSubmissionOutcome.InFlightTimeout -> problem(409, "idempotency_in_flight", config.inFlightRetryAfter.seconds)
            JobSubmissionOutcome.WaiterOverflow -> problem(429, "idempotency_waiters_exceeded", config.overflowRetryAfter.seconds)
            JobSubmissionOutcome.Abandoned -> transientFailure()
        }

    private fun snapshotResponse(snapshot: ReplayableJobSubmission, replayed: Boolean, key: CommandKey): HttpIdempotencyResponse =
        HttpIdempotencyResponse(
            statusCode = snapshot.responseStatus,
            body = snapshot.responseBody.toString(UTF_8),
            headers = buildMap {
                putAll(snapshot.responseHeaders)
                if (key !in suppressedContentTypes) put("content-type", listOf(snapshot.responseContentType))
                put("idempotency-replayed", listOf(replayed.toString()))
            },
            problemCode = "validation_failed".takeIf { snapshot.responseStatus == 422 },
        )

    private fun control(request: HttpIdempotencyRequest): OwnerControl {
        val key = keyOf(request)
        return checkNotNull(controls.compute(key) { _, existing ->
            when {
                activeOwnerControls.containsKey(key) -> activeOwnerControls[key]
                existing == null || existing.isDone() -> OwnerControl()
                else -> existing
            }
        })
    }

    private fun keyOf(request: HttpIdempotencyRequest): CommandKey {
        val scope = resolveScope(request.authenticationProfile) ?: error("request is not authorized")
        val key = request.idempotencyKeys.single()
        return CommandKey(scope.tenantId, scope.submitterHash, keyHash(scope, key))
    }

    private fun keyHash(scope: DemoCallerScope, key: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest("${scope.tenantId}\u0000${scope.submitterHash}\u0000$key".toByteArray(UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun resolveScope(profile: String): DemoCallerScope? =
        when (profile) {
            "tenant-a-principal" -> DemoCallerScope("tenant-a", "principal-a")
            "tenant-b-principal" -> DemoCallerScope("tenant-b", "principal-b")
            "unauthenticated", "tenant-a-read-only" -> null
            else -> DemoCallerScope("tenant-a", profile.take(64).ifBlank { "principal-a" })
        }

    private fun unauthorized(profile: String): HttpIdempotencyResponse =
        if (profile == "unauthenticated") problem(401, "authentication_required") else problem(403, "forbidden")

    private fun invalidRequest(): HttpIdempotencyResponse = problem(400, "invalid_idempotency_request")

    private fun oversizedRequest(): HttpIdempotencyResponse = problem(413, "idempotency_request_too_large")

    private fun transientFailure(): HttpIdempotencyResponse = problem(503, "temporarily_unavailable")

    private fun unsafeSnapshot(): HttpIdempotencyResponse = problem(500, "idempotency_snapshot_rejected")

    private fun problem(status: Int, code: String, retryAfter: Long? = null): HttpIdempotencyResponse =
        HttpIdempotencyResponse(
            statusCode = status,
            body = if (code == "validation_failed") "{\"code\":\"$code\"}" else "{\"code\":\"$code\"}",
            headers = buildMap {
                put("content-type", listOf("application/problem+json"))
                retryAfter?.let { put("retry-after", listOf(it.toString())) }
            },
            problemCode = code,
        )

    private fun sanitize(response: HttpIdempotencyResponse): HttpIdempotencyResponse {
        val nominatedHeaders = response.headers["connection"].orEmpty()
            .flatMap { value -> value.split(',').map(String::trim).filter(String::isNotEmpty) }
            .map(String::lowercase)
            .toSet()
        val contentTypes = response.headers["content-type"].orEmpty()
        val contentTypeValues = if (contentTypes.isEmpty()) {
            listOf(
                when (response.statusCode) {
                    201 -> "application/json"
                    422 -> "application/problem+json"
                    else -> "invalid-content-type"
                },
            )
        } else {
            contentTypes
        }
        val headers = response.headers.filterKeys { it != "content-type" && it != "idempotency-replayed" }
            .filterKeys { it == "etag" && it !in nominatedHeaders }
        return response.copy(
            headers = buildMap {
                if ("content-type" !in nominatedHeaders) put("content-type", contentTypeValues)
                putAll(headers)
            },
        )
    }

    private fun sanitizeTransient(response: HttpIdempotencyResponse): HttpIdempotencyResponse =
        response.copy(headers = mapOf("content-type" to listOf("application/problem+json")))

    private fun nominatesHeader(response: HttpIdempotencyResponse, headerName: String): Boolean =
        response.headers["connection"].orEmpty()
            .flatMap { value -> value.split(',').map(String::trim) }
            .any { it.equals(headerName, ignoreCase = true) }

    private fun canonicalJson(raw: String): String {
        val node = jsonMapper.readTree(raw)
        require(node != null) { "request body must not be empty" }
        return canonicalNode(node)
    }

    private fun canonicalNode(node: JsonNode): String =
        when {
            node.isObject -> node.properties().asSequence().sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") {
                "\"${escape(it.key)}\":${canonicalNode(it.value)}"
            }
            node.isArray -> node.iterator().asSequence().joinToString(prefix = "[", postfix = "]", separator = ",") { canonicalNode(it) }
            node.isString() -> node.asString().let { value ->
                require(!value.hasUnpairedSurrogate()) { "JSON string contains an unpaired surrogate" }
                "\"${escape(value)}\""
            }
            node.isNumber -> node.decimalValue().stripTrailingZeros().toPlainString()
            else -> node.toString()
        }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    private fun String.hasUnpairedSurrogate(): Boolean {
        var index = 0
        while (index < length) {
            val character = this[index]
            when {
                character.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> index += 2
                character.isHighSurrogate() || character.isLowSurrogate() -> return true
                else -> index++
            }
        }
        return false
    }

    private fun fingerprint(operation: String, resource: String, body: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest("$operation\u0000$resource\u0000$body".toByteArray(UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class CommandKey(val tenant: String, val submitter: String, val keyHash: String)

    private sealed interface OwnerSignal {
        data class Complete(val response: HttpIdempotencyResponse) : OwnerSignal
        data class Abandon(val response: HttpIdempotencyResponse) : OwnerSignal
    }

    private class FixtureAbandon(val response: HttpIdempotencyResponse) : RuntimeException()

    private class OwnerControl {
        val started = CompletableDeferred<Unit>()
        val finalized = CompletableDeferred<Unit>()
        val completion = BlockingSignal<OwnerSignal>()
        private val delivery = CountDownLatch(1)
        @Volatile var holdDelivery: Boolean = false

        fun awaitDeliveryIfHeld() {
            if (!holdDelivery) return
            delivery.await()
        }

        fun releaseDelivery() {
            delivery.countDown()
        }

        fun isDeliveryHeld(): Boolean = holdDelivery && delivery.count > 0

        fun isDone(): Boolean = completion.isDone()
    }

    private class BlockingSignal<T> {
        private val latch = CountDownLatch(1)
        @Volatile private var value: T? = null

        fun complete(next: T): Boolean {
            if (latch.count == 0L) return false
            value = next
            latch.countDown()
            return true
        }

        fun await(): T {
            latch.await()
            return checkNotNull(value)
        }

        fun isDone(): Boolean = latch.count == 0L
    }

    private class FixtureClock : JobSubmissionClock {
        private val nanos = AtomicLong()
        private val epoch = Instant.parse("2026-08-16T00:00:00Z")

        override fun databaseNow(): Instant = epoch.plusNanos(nanos.get())

        override fun monotonicNanos(): Long = nanos.get()

        fun advance(duration: Duration) {
            nanos.addAndGet(duration.toNanos())
        }

        fun reset() {
            nanos.set(0)
        }
    }

    private class FixtureRepository(
        private val policy: JobSubmissionIdempotencyPolicy,
        private val clock: FixtureClock,
    ) : io.bluetape4k.workshop.operations.jobconsole.persistence.JobSubmissionIdempotencyRepository {
        var onMutation: () -> Unit = {}
        private val rows = linkedMapOf<CommandKey, Row>()
        private val connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "isClosed" -> false
                "isValid" -> true
                "toString" -> "fixture-connection"
                else -> defaultValue(method.returnType)
            }
        } as Connection

        @Synchronized
        override fun reserve(command: JobSubmissionCommand, now: Instant): Reservation {
            val key = CommandKey(command.scope.tenantId, command.scope.submitterHash, command.keyHash)
            val existing = rows[key]
            if (existing != null && existing.state == State.TERMINAL && now >= existing.retainedUntil) rows.remove(key)
            val row = rows[key]
            if (row == null || row.state == State.ABANDONED) {
                val generation = (row?.generation ?: 0L) + 1
                val ownership =
                    JobSubmissionOwnership(
                        scope = command.scope,
                        keyHash = command.keyHash,
                        generation = generation,
                        jobId = UUID.randomUUID(),
                        ownerToken = UUID.randomUUID(),
                        leaseExpiresAt = now.plus(policy.ownerLease),
                    )
                rows[key] = Row(command.requestFingerprint, generation, ownership, State.IN_FLIGHT)
                onMutation()
                return Reservation.Owner(ownership)
            }
            if (row.requestFingerprint != command.requestFingerprint) return Reservation.Conflict
            return when (row.state) {
                State.IN_FLIGHT -> Reservation.Wait(checkNotNull(row.ownership), now)
                State.TERMINAL -> Reservation.Replay(checkNotNull(row.snapshot))
                State.ABANDONED -> error("abandoned row is handled above")
            }
        }

        @Synchronized
        override fun registerWaiter(ownership: io.bluetape4k.workshop.operations.jobconsole.idempotency.InFlightOwnership, now: Instant): WaiterRegistration =
            registerWaiter(ownership, now, policy.waiterTimeout, now.plus(policy.waiterTimeout))

        @Synchronized
        override fun registerWaiter(
            ownership: io.bluetape4k.workshop.operations.jobconsole.idempotency.InFlightOwnership,
            now: Instant,
            waiterTtl: Duration,
            deadlineAt: Instant,
        ): WaiterRegistration {
            val key = CommandKey(ownership.ownership.scope.tenantId, ownership.ownership.scope.submitterHash, ownership.ownership.keyHash)
            val row = rows[key] ?: return WaiterRegistration.DeadlineExceeded
            if (row.state != State.IN_FLIGHT || row.generation != ownership.ownership.generation) return WaiterRegistration.DeadlineExceeded
            if (row.waiters.size >= policy.maxWaitersPerKey) return WaiterRegistration.Overflow
            val token = UUID.randomUUID()
            row.waiters[token] = minOf(deadlineAt, now.plus(waiterTtl))
            onMutation()
            return WaiterRegistration.Registered(token, row.generation)
        }

        @Synchronized
        override fun removeWaiter(scope: DemoCallerScope, keyHash: String, generation: Long, waiterToken: UUID): Boolean =
            rows[CommandKey(scope.tenantId, scope.submitterHash, keyHash)]?.waiters?.remove(waiterToken)?.also {
                onMutation()
            } != null

        @Synchronized
        override fun poll(scope: DemoCallerScope, keyHash: String, generation: Long, now: Instant): PollResult {
            val row = rows[CommandKey(scope.tenantId, scope.submitterHash, keyHash)] ?: return PollResult.Abandoned(generation)
            row.waiters.entries.removeIf { (_, expiresAt) -> expiresAt <= now }
            if (row.generation != generation) return PollResult.Abandoned(generation)
            return when (row.state) {
                State.IN_FLIGHT -> PollResult.StillInFlight
                State.ABANDONED -> PollResult.Abandoned(generation)
                State.TERMINAL -> PollResult.Terminal(checkNotNull(row.snapshot))
            }
        }

        override fun <T> withTransaction(block: (Connection) -> T): T = block(connection)

        @Synchronized
        override fun finalizeOwner(ownership: JobSubmissionOwnership, prepared: PreparedJobSubmission, now: Instant): ReplayableJobSubmission =
            finalizeOwner(connection, ownership, prepared, now)

        @Synchronized
        override fun finalizeOwner(connection: Connection, ownership: JobSubmissionOwnership, prepared: PreparedJobSubmission, now: Instant): ReplayableJobSubmission {
            val key = CommandKey(ownership.scope.tenantId, ownership.scope.submitterHash, ownership.keyHash)
            val row = rows[key]
            require(row?.state == State.IN_FLIGHT && row.ownership?.ownerToken == ownership.ownerToken) { "owner lease lost" }
            val snapshot =
                ReplayableJobSubmission(
                    jobId = ownership.jobId,
                    enqueueSequence = ownership.generation,
                    responseStatus = prepared.responseStatus,
                    responseBody = prepared.responseBody.copyOf(),
                    responseContentType = prepared.responseContentType,
                    responseHeaders = prepared.responseHeaders.mapValues { it.value.toList() },
                )
            row.state = State.TERMINAL
            row.snapshot = snapshot
            row.retainedUntil = now.plus(policy.retention)
            row.ownership = null
            onMutation()
            return snapshot
        }

        @Synchronized
        override fun abandon(ownership: JobSubmissionOwnership, reason: AbandonReason, now: Instant): Boolean {
            val key = CommandKey(ownership.scope.tenantId, ownership.scope.submitterHash, ownership.keyHash)
            val row = rows[key]
            if (row?.state != State.IN_FLIGHT || row.ownership?.ownerToken != ownership.ownerToken) return false
            row.state = State.ABANDONED
            row.ownership = null
            row.abandonedUntil = now
            onMutation()
            return true
        }

        override fun cleanupExpired(now: Instant, batchSize: Int): io.bluetape4k.workshop.operations.jobconsole.idempotency.CleanupReport =
            io.bluetape4k.workshop.operations.jobconsole.idempotency.CleanupReport(0, 0)

        @Synchronized
        fun waiterCount(key: CommandKey): Int {
            rows[key]?.waiters?.entries?.removeIf { (_, expiresAt) -> expiresAt <= clock.databaseNow() }
            return rows[key]?.waiters?.size ?: 0
        }

        @Synchronized
        fun totalWaiters(): Int = rows.values.sumOf { it.waiters.size }

        @Synchronized
        fun clear() {
            rows.clear()
        }

        private data class Row(
            val requestFingerprint: String,
            val generation: Long,
            var ownership: JobSubmissionOwnership?,
            var state: State,
            var snapshot: ReplayableJobSubmission? = null,
            var retainedUntil: Instant = Instant.MIN,
            var abandonedUntil: Instant = Instant.MIN,
            val waiters: MutableMap<UUID, Instant> = linkedMapOf(),
        )

        private enum class State { IN_FLIGHT, TERMINAL, ABANDONED }
    }

    private companion object {
        fun defaultValue(type: Class<*>): Any? =
            when {
                !type.isPrimitive -> null
                type == Boolean::class.javaPrimitiveType -> false
                type == Byte::class.javaPrimitiveType -> 0.toByte()
                type == Short::class.javaPrimitiveType -> 0.toShort()
                type == Int::class.javaPrimitiveType -> 0
                type == Long::class.javaPrimitiveType -> 0L
                type == Float::class.javaPrimitiveType -> 0f
                type == Double::class.javaPrimitiveType -> 0.0
                type == Char::class.javaPrimitiveType -> '\u0000'
                else -> null
            }
    }
}

/**
 * 인메모리 conformance fixture가 사용하는 가상 시간 대기 전략입니다.
 *
 * waiter가 coordinator에 진입하기 전에 mutation epoch을 기록합니다. poll 반환 후 waiter가
 * condition variable에 도달하기 전에 owner가 완료될 수 있으므로, await 진입 시점에만 epoch을
 * 읽으면 이미 발생한 wake-up을 잃고 가상 시간이 멈춘 상태에서 영원히 대기할 수 있습니다.
 */
class FixtureVirtualWaitStrategy(
    private val monotonicNanos: () -> Long,
) : InterruptibleWaitStrategy {
    private val wakeup = java.util.concurrent.locks.ReentrantLock()
    private val wakeupCondition = wakeup.newCondition()
    private val mutationEpoch = AtomicLong()
    private val observedEpoch = ThreadLocal<Long?>()

    fun beginWaiter() {
        observedEpoch.set(mutationEpoch.get())
    }

    fun endWaiter() {
        observedEpoch.remove()
    }

    fun signal() {
        wakeup.lock()
        try {
            mutationEpoch.incrementAndGet()
            wakeupCondition.signalAll()
        } finally {
            wakeup.unlock()
        }
    }

    override fun await(interval: Duration) {
        val deadline = Math.addExact(monotonicNanos(), interval.toNanos())
        val baseline = observedEpoch.get() ?: mutationEpoch.get()
        wakeup.lock()
        try {
            while (monotonicNanos() < deadline && mutationEpoch.get() == baseline) {
                wakeupCondition.await(25L, TimeUnit.MILLISECONDS)
            }
            observedEpoch.set(mutationEpoch.get())
        } finally {
            wakeup.unlock()
        }
    }
}
