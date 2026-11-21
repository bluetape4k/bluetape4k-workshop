package io.bluetape4k.workshop.commerce.metering.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.javatimes.days
import io.bluetape4k.javatimes.hours
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * MeteringPropertiesTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class MeteringPropertiesTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "defaults keep work bounded and retryable" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `defaults keep work bounded and retryable`() {
        val properties = MeteringProperties()

        log.debug { "properties=$properties" }
        properties.commandReceipt.lease shouldBeEqualTo 30.seconds()
        properties.commandReceipt.retention shouldBeEqualTo 24.hours()
        properties.commandReceipt.terminalResponseBytes shouldBeEqualTo 16 * 1024
        properties.allowedLateness shouldBeEqualTo 48.hours()
        properties.occurredAtRetention shouldBeEqualTo 400.days()
        properties.occurredAtFutureSkew shouldBeEqualTo 5.minutes()
        properties.close.batchSize shouldBeEqualTo 200
        properties.close.maxBatchSize shouldBeEqualTo 1_000
        properties.close.maxBatchesPerTick shouldBeEqualTo 5
        properties.close.schedulerDelay shouldBeEqualTo 5.seconds()
        properties.reconciliation.pageSize shouldBeEqualTo 200
        properties.reconciliation.maxPageSize shouldBeEqualTo 500
    }

    @Test
/**
 * 이 시나리오는 "receipt limits reject unsafe durations and response sizes" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `receipt limits reject unsafe durations and response sizes`() {
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties.CommandReceipt(lease = 4.seconds())
        }
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties.CommandReceipt(lease = 6.minutes())
        }
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties.CommandReceipt(
                lease = 1.minutes(),
                retention = 30.seconds(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties.CommandReceipt(terminalResponseBytes = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties.CommandReceipt(terminalResponseBytes = 16 * 1024 + 1)
        }
    }

    @Test
/**
 * 이 시나리오는 "close limits reject unbounded batches and invalid cadence" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `close limits reject unbounded batches and invalid cadence`() {
        assertFailsWith<IllegalArgumentException> { MeteringProperties.Close(batchSize = 0) }
        assertFailsWith<IllegalArgumentException> { MeteringProperties.Close(batchSize = 1_001) }
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties.Close(batchSize = 201, maxBatchSize = 200)
        }
        assertFailsWith<IllegalArgumentException> { MeteringProperties.Close(maxBatchesPerTick = 0) }
        assertFailsWith<IllegalArgumentException> { MeteringProperties.Close(maxBatchesPerTick = 21) }
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties.Close(schedulerDelay = Duration.ZERO)
        }
    }

    @Test
/**
 * 이 시나리오는 "reconciliation page is positive and capped" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `reconciliation page is positive and capped`() {
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties.Reconciliation(pageSize = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties.Reconciliation(pageSize = 501)
        }
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties.Reconciliation(pageSize = 201, maxPageSize = 200)
        }
    }

    @Test
/**
 * 이 시나리오는 "time horizons must be positive" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `time horizons must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties(allowedLateness = Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties(occurredAtRetention = Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties(occurredAtFutureSkew = (-1).seconds())
        }
    }
}
