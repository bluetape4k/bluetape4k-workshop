package io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant


@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/**
 * SnapshotPostgresIntegrationTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class SnapshotPostgresIntegrationTest {

    companion object: KLogging()

/**
 * fixture은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val fixture = EventStoreDatabaseFixture()
/**
 * repository은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val repository = SnapshotRepository()

    @Test
/**
 * 이 시나리오는 "latest selects the highest snapshot for the requested reducer version" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `latest selects the highest snapshot for the requested reducer version`() {
        fixture.reset()
        val stream = StreamKey("tenant-a", "Meter", "api_calls")
        fixture.executor.transaction {
            repository.append(snapshot(stream, 10, 1, "hash-10"))
            repository.append(snapshot(stream, 20, 1, "hash-20"))
            repository.append(snapshot(stream, 30, 2, "hash-30"))
        }

        val reducerOne = fixture.executor.transaction {
            repository.latest(stream, 1)
        }.shouldNotBeNull()
        val reducerTwo = fixture.executor.transaction {
            repository.latest(stream, 2)
        }.shouldNotBeNull()

        log.debug { "reducerOne: $reducerOne" }
        log.debug { "reducerTwo: $reducerTwo" }
        reducerOne.streamVersion shouldBeEqualTo 20L
        reducerOne.lastEventHash shouldBeEqualTo "hash-20"
        reducerTwo.streamVersion shouldBeEqualTo 30L

        fixture.executor.transaction {
            repository.latest(stream.copy(tenantId = "tenant-b"), 1)
        }.shouldBeNull()
    }

    @Test
/**
 * 이 시나리오는 "snapshot history is append-only" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `snapshot history is append-only`() {
        fixture.reset()
        val stream = StreamKey("tenant-a", "Meter", "api_calls")
        val stored = fixture.executor.transaction {
            repository.append(snapshot(stream, 10, 1, "hash-10"))
        }

        assertFailsWith<UnsupportedOperationException> {
            fixture.executor.transaction {
                repository.save(AggregateSnapshotEntity[stored.snapshotId])
            }
        }
    }

/**
 * snapshot은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun snapshot(
        stream: StreamKey,
        streamVersion: Long,
        reducerVersion: Int,
        lastEventHash: String,
    ): NewAggregateSnapshot =
        NewAggregateSnapshot(
            stream = stream,
            streamVersion = streamVersion,
            reducerVersion = reducerVersion,
            statePayload = """{"meterCode":"api_calls","version":$streamVersion}""",
            lastEventHash = lastEventHash,
            createdAt = Instant.parse("2026-07-01T00:00:00Z") + streamVersion.seconds(),
        )
}
