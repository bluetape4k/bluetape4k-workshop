package io.bluetape4k.workshop.commerce.metering.eventsourcing

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.javatimes.nanos
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.BillingCloseBatchResult
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.BillingCloseService
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.BillingLifecycleCommandService
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.DomainEventJsonCodec
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.MeterCommandService
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.ReconciliationQuery
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.ReconciliationService
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.DomainEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterReducer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterState
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PersistedEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PricePoint
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageAccepted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.AggregateReplayer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.AggregateSnapshotSeed
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventTypeQuery
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.OccurredEventCursor
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.ReplayPolicy
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.StreamAppend
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.BillingReadModelRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreDatabaseFixture
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.NewAggregateSnapshot
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionCheckpointRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionGenerationRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.SnapshotRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionCoordinator
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionEventContext
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionHandlers
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionModelType
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.*

@Tag("stress")
/**
 * BillingEventSourcingStressTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class BillingEventSourcingStressTest {

    companion object: KLogging() {
/**
 * TENANT은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val TENANT = "tenant-stress"
/**
 * METER은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val METER = "api_calls"
/**
 * PERIOD은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val PERIOD = "2026-07"
/**
 * CURRENCY은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val CURRENCY = "USD"
/**
 * PROJECTION은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val PROJECTION = "billing"
/**
 * USAGE_COUNT은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val USAGE_COUNT = 10_000
/**
 * APPEND_BATCH_SIZE은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val APPEND_BATCH_SIZE = 250
/**
 * CLOSE_BATCH_SIZE은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val CLOSE_BATCH_SIZE = 1_000
/**
 * PROJECTION_BATCH_SIZE은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val PROJECTION_BATCH_SIZE = 100
/**
 * QUERY_PAGE_SIZE은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val QUERY_PAGE_SIZE = 1_000
/**
 * REDUCER_VERSION은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val REDUCER_VERSION = 1
/**
 * SHA256_HEX_LENGTH은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val SHA256_HEX_LENGTH = 64
/**
 * MILLIS_PER_SECOND은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val MILLIS_PER_SECOND = 1_000.0
/**
 * START은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val START: Instant = Instant.parse("2026-07-01T00:00:00Z")
/**
 * END은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val END: Instant = START + 3_600.seconds()
/**
 * UNIT_PRICE은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val UNIT_PRICE: BigDecimal = BigDecimal("0.10")
/**
 * EXPECTED_TOTAL은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val EXPECTED_TOTAL: BigDecimal = BigDecimal("1000.00")
/**
 * LEASE_DURATION은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val LEASE_DURATION: Duration = 30.minutes()
    }

/**
 * fixture은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val fixture = EventStoreDatabaseFixture()
/**
 * eventStore은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val eventStore = EventStoreRepository()
/**
 * codec은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val codec = DomainEventJsonCodec()
/**
 * generations은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val generations = ProjectionGenerationRepository()
/**
 * checkpoints은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val checkpoints = ProjectionCheckpointRepository()
/**
 * readModels은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val readModels = BillingReadModelRepository()
/**
 * handlers은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val handlers = ProjectionHandlers(readModels)
/**
 * coordinator은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val coordinator = ProjectionCoordinator(checkpoints)

    @Test
/**
 * 이 시나리오는 "ten thousand usage events survive close replay and generation rebuild" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `ten thousand usage events survive close replay and generation rebuild`() {
        fixture.reset()
        val startedAt = System.nanoTime()
        seedAuthority()
        appendUsageEvents()
        closeBillingPeriod()

        val rated = loadAll("usage.rated")
        rated shouldHaveSize USAGE_COUNT
        rated.map { it.eventId }.distinct() shouldHaveSize USAGE_COUNT
        rated.all { it.previousHash == null && it.eventHash.length == SHA256_HEX_LENGTH }.shouldBeTrue()

        verifySnapshotReplay()
        val highWatermark = lastGlobalPosition()
        rebuildAndSwitch(highWatermark)

        val entries = fixture.executor.transaction {
            readModels.entries(PROJECTION, 2, TENANT)
        }
        val charges = entries.filter {
            it.modelType == ProjectionModelType.LEDGER_DEBIT
        }
        charges.size.shouldBeEqualTo(USAGE_COUNT)
        charges.map { it.provenance }.distinct() shouldHaveSize USAGE_COUNT
        EXPECTED_TOTAL.compareTo(charges.sumOf { checkNotNull(it.amount) }) shouldBeEqualTo 0

        val reconciliation = ReconciliationService(eventStore, codec, generations, readModels)
        val finding = fixture.executor.transaction {
            val query = ReconciliationQuery(TENANT, PROJECTION, START.minusSeconds(1), END + 1.seconds())
            reconciliation.inspect(query)
        }
        finding.shouldBeNull()
        fixture.executor.transaction {
            generations.active(PROJECTION)?.generation
        } shouldBeEqualTo 2

        reportThroughput(startedAt)
    }

/**
 * seedAuthority은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun seedAuthority() {
        val meters = MeterCommandService(eventStore, codec)
        val billing = BillingLifecycleCommandService(eventStore, codec)

        fixture.executor.transaction {
            meters.register(TENANT, METER, "request", CURRENCY, START)
            meters.activatePrice(TENANT, METER, UNIT_PRICE, CURRENCY, START)
            billing.open(TENANT, PERIOD, CURRENCY, START, END)
            billing.startClose(TENANT, PERIOD, END)
        }
    }

/**
 * appendUsageEvents은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun appendUsageEvents() {
        (0 until USAGE_COUNT).chunked(APPEND_BATCH_SIZE).forEach { indexes ->
            val events = indexes.map { index ->
                val occurredAt = START + (index.toLong() + 1).millis()
                val usage = UsageAccepted(
                    sourceSystem = "stress-fixture",
                    sourceEventId = "usage-$index",
                    meterCode = METER,
                    quantity = BigDecimal.ONE,
                    occurredAt = occurredAt
                )
                StreamAppend(
                    stream = StreamKey(TENANT, "Usage", "usage-$index"),
                    expectedVersion = 0,
                    events = listOf(codec.encode(usage, occurredAt)),
                )
            }

            log.debug { "append events. size=${events.size}" }
            fixture.executor.transaction {
                eventStore.appendAll(events)
            }
        }
    }

/**
 * closeBillingPeriod은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun closeBillingPeriod() {
        val closer = BillingCloseService(eventStore, codec)
        var batches = 0
        var result: BillingCloseBatchResult
        do {
            result = fixture.executor.transaction {
                closer.closeNextBatch(TENANT, PERIOD, CLOSE_BATCH_SIZE, END + 1.seconds())
            }
            if (result == BillingCloseBatchResult.APPLIED) batches += 1
            log.debug { "result=$result" }
        } while (result == BillingCloseBatchResult.APPLIED || result == BillingCloseBatchResult.RETRY)

        batches shouldBeEqualTo USAGE_COUNT / CLOSE_BATCH_SIZE
    }

/**
 * verifySnapshotReplay은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun verifySnapshotReplay() {
        val meterStream = StreamKey(TENANT, "Meter", METER)
        val meterEvents = fixture.executor.transaction { eventStore.load(meterStream) }
        val snapshot = NewAggregateSnapshot(
            stream = meterStream,
            streamVersion = meterEvents.size.toLong(),
            reducerVersion = REDUCER_VERSION,
            statePayload = """{"meterCode":"$METER","unit":"request","currency":"$CURRENCY"}""",
            lastEventHash = meterEvents.last().eventHash,
            createdAt = END,
        )
        val stored = fixture.executor.transaction {
            SnapshotRepository().append(snapshot)
        }
        stored.shouldNotBeNull()
        val state = MeterState.Active(
            meterCode = METER,
            unit = "request",
            currency = CURRENCY,
            prices = listOf(PricePoint(CURRENCY, UNIT_PRICE, START))
        )
        val replayed = AggregateReplayer.replay(
            events = meterEvents,
            initialState = MeterState.Empty,
            reducer = MeterReducer,
            snapshot = AggregateSnapshotSeed(state, stored.streamVersion, stored.lastEventHash, stored.reducerVersion),
            policy = ReplayPolicy(codec.registry, REDUCER_VERSION),
        )
        replayed.state shouldBeEqualTo state
        replayed.streamVersion shouldBeEqualTo meterEvents.size.toLong()
    }

/**
 * rebuildAndSwitch은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun rebuildAndSwitch(highWatermark: Long) {
        fixture.executor.transaction {
            generations.createInitialActive(PROJECTION, 1, END)
            generations.createBuilding(PROJECTION, 2, highWatermark, END)
        }

        val lease = projectGeneration(generation = 2, highWatermark = highWatermark, release = false)
        fixture.executor.transaction {
            generations.switchActive(lease, expectedActiveGeneration = 1, END + 2.seconds()).shouldBeTrue()
            checkpoints.releaseLease(lease, END + 2.seconds())
        }
    }

/**
 * projectGeneration은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun projectGeneration(
        generation: Int,
        highWatermark: Long,
        release: Boolean = true,
    ): io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionLease {
/**
 * lease은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val lease = fixture.executor.transaction {
            checkpoints.acquireLease(
                projectionName = PROJECTION,
                generation = generation,
                ownerToken = UUID.randomUUID(),
                now = END,
                duration = LEASE_DURATION
            )
                .shouldNotBeNull()
                .also {
                    checkpoints.raiseHighWatermark(it, highWatermark, END)
                }
        }
        while (true) {
/**
 * applied은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val applied = fixture.executor.transaction {
/**
 * checkpoint은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val checkpoint = checkpoints.requireOwnership(lease).checkpoint
/**
 * page은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val page = eventStore.loadAfterGlobalPosition(checkpoint, PROJECTION_BATCH_SIZE)

                page.forEach { persisted ->
                    coordinator.apply(lease, persisted.eventId, persisted.globalPosition) {
/**
 * event은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                        val event = codec.registry.decode(
                            eventType = persisted.eventType,
                            schemaVersion = persisted.schemaVersion,
                            payload = persisted.payload,
                        ).shouldBeInstanceOf<DomainEvent>()

                        handlers.handle(
                            ProjectionEventContext(
                                projectionName = PROJECTION,
                                generation = generation,
                                tenantId = persisted.stream.tenantId,
                                eventId = persisted.eventId,
                                globalPosition = persisted.globalPosition,
                                occurredAt = persisted.occurredAt,
                            ),
                            event,
                        )
                    }
                }
                page.size
            }
            log.debug { "applied=$applied" }
            if (applied == 0) break
        }
        if (release) fixture.executor.transaction {
            checkpoints.releaseLease(lease, END + 2.seconds())
        }
        return lease
    }

    @Suppress("SameParameterValue")
/**
 * loadAll은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun loadAll(eventType: String): List<PersistedEvent> {
        val events = mutableListOf<PersistedEvent>()
        var cursor: OccurredEventCursor? = null
        do {
            val page = fixture.executor.transaction {
                eventStore.loadByType(
                    EventTypeQuery(
                        tenantId = TENANT,
                        eventType = eventType,
                        startsAt = START,
                        endsAt = END + 2.seconds(),
                        after = cursor,
                        limit = QUERY_PAGE_SIZE
                    ),
                )
            }
            events += page
            cursor = page.lastOrNull()?.let { OccurredEventCursor(it.occurredAt, it.eventId) }
        } while (page.size == QUERY_PAGE_SIZE)
        return events
    }

/**
 * lastGlobalPosition은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun lastGlobalPosition(): Long {
        var position = 0L
        while (true) {
            val page = fixture.executor.transaction {
                eventStore.loadAfterGlobalPosition(position, QUERY_PAGE_SIZE)
            }
            log.debug { "position=$position" }
            if (page.isEmpty()) return position
            position = page.last().globalPosition
        }
    }

/**
 * reportThroughput은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun reportThroughput(startedAt: Long) {
        val elapsed = (System.nanoTime() - startedAt).nanos()
        val throughput = USAGE_COUNT.toDouble() / elapsed.toMillis().coerceAtLeast(1) * MILLIS_PER_SECOND
        println(
            "stress.usage.count=$USAGE_COUNT stress.elapsed.ms=${elapsed.toMillis()} " +
                    "stress.throughput.events_per_second=${"%.2f".format(Locale.ROOT, throughput)} " +
                    "stress.peak.batch=$PROJECTION_BATCH_SIZE",
        )
    }
}
