package io.bluetape4k.workshop.commerce.voucherpool

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucherpool.performance.RedisMode
import io.bluetape4k.workshop.commerce.voucherpool.performance.VoucherPoolPerformanceProbe
import io.bluetape4k.workshop.commerce.voucherpool.performance.VoucherPoolStressEvidence
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.*

@Tag("stress")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/**
 * VoucherPoolStressProfileTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherPoolStressProfileTest {

/**
 * runId은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val runId = System.getProperty("voucherPool.stressRun", "dev")
/**
 * outputRoot은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val outputRoot = Path.of("build", "voucher-pool-stress")
/**
 * probe은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val probe = VoucherPoolPerformanceProbe(PostgreSQLServer.Launcher.postgres, outputRoot)
/**
 * profiles은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val profiles = linkedSetOf<String>()

    @ParameterizedTest(name = "{0} clients with Redis {1}")
    @CsvSource("64,HEALTHY", "64,UNAVAILABLE", "128,HEALTHY", "128,UNAVAILABLE")
/**
 * 이 시나리오는 "virtual client matrix preserves hard resource bounds" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `virtual client matrix preserves hard resource bounds`(clients: Int, redis: RedisMode) {
        val evidence = probe.runProfile(
            runId = runId,
            entries = 10_000,
            clients = clients,
            sameUserPercent = 50,
            redis = redis,
            workerLoad = true,
            sseSubscribers = 16,
            freshDatabase = true,
        )
        profiles += "$clients-${redis.name.lowercase()}"
        assertVoucherPoolStressHardGates(evidence)
    }

    @Test
/**
 * 이 시나리오는 "Hikari acquisition timeout is retryable and never terminal" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `Hikari acquisition timeout is retryable and never terminal`() {
        val timeout = probe.acquisitionTimeoutSemantics()
        timeout.status shouldBeEqualTo 503
        timeout.code shouldBeEqualTo "BACKEND_TIMEOUT"
        timeout.retryable.shouldBeTrue()
        timeout.ownerReleased.shouldBeTrue()
        timeout.terminalDescriptorWritten.shouldBeFalse()
    }

    @AfterAll
    fun `manifest contains exactly four complete checksum-valid profiles`() {
        profiles shouldBeEqualTo EXPECTED_PROFILES
        val runDirectory = outputRoot.resolve(runId)
        val manifest = Files.readString(runDirectory.resolve("manifest.json"))
        Regex("\\\"profile\\\"").findAll(manifest).count() shouldBeEqualTo 4
        val artifacts = EXPECTED_PROFILES.flatMap { profile ->
            listOf(runDirectory.resolve("$profile.json"), runDirectory.resolve("$profile.threads.txt"))
        }
        artifacts.all { Files.size(it) > 0L }.shouldBeTrue()
        val checksums = artifacts.map(::sha256)
        checksums.toSet() shouldHaveSize checksums.size
        checksums.forEach { checksum -> manifest shouldContain checksum }
    }

/**
 * sha256은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }

    companion object: KLogging() {
/**
 * EXPECTED_PROFILES은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val EXPECTED_PROFILES = linkedSetOf(
            "64-healthy",
            "64-unavailable",
            "128-healthy",
            "128-unavailable",
        )
    }
}

/**
 * VoucherPoolStressHardGateTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherPoolStressHardGateTest {

    @Test
/**
 * 이 시나리오는 "hard gates require observed samples and reject a scheduler-inclusive over-limit wait"
 * 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `hard gates require observed samples and reject a scheduler-inclusive over-limit wait`() {
        val passing = passingHardGateEvidence()
        assertVoucherPoolStressHardGates(passing)
        assertVoucherPoolStressHardGates(
            passing.copy(
                foregroundWaitMaxMillis = FOREGROUND_OBSERVED_WAIT_HARD_LIMIT_MILLIS,
                hikariPendingMax = 3,
            ),
        )
        val waitFailure = assertFailsWith<AssertionError> {
            assertVoucherPoolStressHardGates(
                passing.copy(foregroundWaitMaxMillis = FOREGROUND_OBSERVED_WAIT_HARD_LIMIT_MILLIS + 1),
            )
        }
        waitFailure.message shouldBeEqualTo
                "foreground permit wait exceeded hard limit: actual=501 ms maximum=500 ms"

        listOf(
            passing.copy(hikariAcquisitionWaitSamples = 0),
            passing.copy(foregroundWaitSamples = 0),
            passing.copy(workerWaitSamples = 0),
            passing.copy(sseWaitSamples = 0),
            passing.copy(hikariPendingDrainMillis = 12_001),
        ).forEach { invalidEvidence ->
            assertFailsWith<AssertionError> { assertVoucherPoolStressHardGates(invalidEvidence) }
        }
    }

/**
 * passingHardGateEvidence은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun passingHardGateEvidence(): VoucherPoolStressEvidence =
        VoucherPoolStressEvidence(
            runId = "controlled-hard-gate",
            clients = 1,
            redisMode = RedisMode.HEALTHY,
            winners = 1,
            successfulResponses = 1,
            authoritativeReservationCount = 1,
            authoritativeAllocationCount = 1,
            duplicateWinnerCount = 0,
            stateCountSum = 1,
            entryCount = 1,
            hikariActiveMax = 1,
            hikariAcquisitionWaitMaxMillis = 1,
            hikariAcquisitionWaitSamples = 1,
            totalPermitHoldersMax = 1,
            foregroundWaitMaxMillis = 1,
            foregroundWaitSamples = 1,
            workerWaitMaxMillis = 1,
            workerWaitSamples = 1,
            sseWaitMaxMillis = 1,
            sseWaitSamples = 1,
            acquisitionTimeouts = 0,
            acquisitionTimeoutTerminalDescriptors = 0,
            deadlineViolations = 0,
            hikariPendingMax = 0,
            hikariPendingDrainMillis = 0,
            workerCheckpointProgress = 1,
            connectionLeaks = 0,
            permitLeaks = 0,
            counterDrift = 0,
        )
}

/**
 * assertVoucherPoolStressHardGates은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
private fun assertVoucherPoolStressHardGates(evidence: VoucherPoolStressEvidence) {
    assertMaximum("Hikari active connections", evidence.hikariActiveMax.toLong(), 16, "connections")
    evidence.hikariAcquisitionWaitSamples shouldBeGreaterThan 0
    assertMaximum("Hikari acquisition wait", evidence.hikariAcquisitionWaitMaxMillis, 2_000, "ms")
    assertMaximum("total permit holders", evidence.totalPermitHoldersMax.toLong(), 15, "holders")
    evidence.foregroundWaitSamples shouldBeGreaterThan 0

    assertMaximum(
        name = "foreground permit wait",
        actual = evidence.foregroundWaitMaxMillis,
        maximum = FOREGROUND_OBSERVED_WAIT_HARD_LIMIT_MILLIS,
        unit = "ms",
    )
    evidence.workerWaitSamples shouldBeGreaterThan 0
    assertMaximum("worker permit wait", evidence.workerWaitMaxMillis, 1_000, "ms")
    evidence.sseWaitSamples shouldBeGreaterThan 0
    assertMaximum("SSE permit wait", evidence.sseWaitMaxMillis, 1_000, "ms")
    evidence.deadlineViolations shouldBeEqualTo 0

    assertMaximum("Hikari pending drain", evidence.hikariPendingDrainMillis, 12_000, "ms")

    evidence.workerCheckpointProgress shouldBeGreaterThan 0
    evidence.duplicateWinnerCount shouldBeEqualTo 0
    evidence.authoritativeAllocationCount shouldBeEqualTo evidence.winners
    evidence.authoritativeAllocationCount shouldBeEqualTo evidence.successfulResponses
    evidence.authoritativeAllocationCount shouldBeEqualTo evidence.authoritativeReservationCount
    evidence.entryCount shouldBeEqualTo evidence.stateCountSum
    evidence.acquisitionTimeoutTerminalDescriptors shouldBeEqualTo 0
    evidence.connectionLeaks shouldBeEqualTo 0
    evidence.permitLeaks shouldBeEqualTo 0
    evidence.counterDrift shouldBeEqualTo 0L
}

/**
 * assertMaximum은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
private fun assertMaximum(name: String, actual: Long, maximum: Long, unit: String) {
    if (actual > maximum) {
        throw AssertionError("$name exceeded hard limit: actual=$actual $unit maximum=$maximum $unit")
    }
}

/**
 * FOREGROUND_OBSERVED_WAIT_HARD_LIMIT_MILLIS은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
private const val FOREGROUND_OBSERVED_WAIT_HARD_LIMIT_MILLIS = 500L
