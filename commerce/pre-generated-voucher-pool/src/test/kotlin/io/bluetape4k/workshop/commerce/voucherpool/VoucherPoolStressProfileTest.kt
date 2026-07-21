package io.bluetape4k.workshop.commerce.voucherpool

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
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

@Tag("stress")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class VoucherPoolStressProfileTest {
    private val runId = System.getProperty("voucherPool.stressRun", "dev")
    private val outputRoot = Path.of("build", "voucher-pool-stress")
    private val probe = VoucherPoolPerformanceProbe(PostgreSQLServer.Launcher.postgres, outputRoot)
    private val profiles = linkedSetOf<String>()

    @ParameterizedTest(name = "{0} clients with Redis {1}")
    @CsvSource("64,HEALTHY", "64,UNAVAILABLE", "128,HEALTHY", "128,UNAVAILABLE")
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
        assertVoucherPoolStressHardGates(evidence)
        profiles += "$clients-${redis.name.lowercase()}"
    }

    @Test
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
        checksums.toSet().size shouldBeEqualTo checksums.size
        checksums.forEach { checksum -> manifest.contains(checksum).shouldBeTrue() }
    }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    companion object {
        private val EXPECTED_PROFILES = linkedSetOf(
            "64-healthy",
            "64-unavailable",
            "128-healthy",
            "128-unavailable",
        )
    }
}

internal class VoucherPoolStressHardGateTest {
    @Test
    fun `hard gates require observed samples and reject a measured over-limit wait`() {
        val passing = passingHardGateEvidence()
        assertVoucherPoolStressHardGates(passing)

        listOf(
            passing.copy(hikariAcquisitionWaitSamples = 0),
            passing.copy(foregroundWaitSamples = 0),
            passing.copy(workerWaitSamples = 0),
            passing.copy(sseWaitSamples = 0),
            passing.copy(foregroundWaitMaxMillis = 251),
        ).forEach { invalidEvidence ->
            assertFailsWith<AssertionError> { assertVoucherPoolStressHardGates(invalidEvidence) }
        }
    }

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

private fun assertVoucherPoolStressHardGates(evidence: VoucherPoolStressEvidence) {
    (evidence.hikariActiveMax <= 16).shouldBeTrue()
    (evidence.hikariAcquisitionWaitSamples > 0).shouldBeTrue()
    (evidence.hikariAcquisitionWaitMaxMillis <= 2_000).shouldBeTrue()
    (evidence.totalPermitHoldersMax <= 15).shouldBeTrue()
    (evidence.foregroundWaitSamples > 0).shouldBeTrue()
    (evidence.foregroundWaitMaxMillis <= 250).shouldBeTrue()
    (evidence.workerWaitSamples > 0).shouldBeTrue()
    (evidence.workerWaitMaxMillis <= 1_000).shouldBeTrue()
    (evidence.sseWaitSamples > 0).shouldBeTrue()
    (evidence.sseWaitMaxMillis <= 1_000).shouldBeTrue()
    evidence.deadlineViolations shouldBeEqualTo 0
    (evidence.hikariPendingMax <= 1).shouldBeTrue()
    (evidence.hikariPendingDrainMillis <= 12_000).shouldBeTrue()
    (evidence.workerCheckpointProgress > 0).shouldBeTrue()
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
