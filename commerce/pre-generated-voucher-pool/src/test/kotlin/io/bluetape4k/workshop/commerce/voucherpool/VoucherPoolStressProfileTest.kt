package io.bluetape4k.workshop.commerce.voucherpool

import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucherpool.performance.RedisMode
import io.bluetape4k.workshop.commerce.voucherpool.performance.VoucherPoolPerformanceProbe
import io.bluetape4k.workshop.commerce.voucherpool.performance.VoucherPoolStressEvidence
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
        assertHardGates(evidence)
        profiles += "$clients-${redis.name.lowercase()}"
    }

    @Test
    fun `Hikari acquisition timeout is retryable and never terminal`() {
        val timeout = probe.acquisitionTimeoutSemantics()
        assertEquals(503, timeout.status)
        assertEquals("BACKEND_TIMEOUT", timeout.code)
        assertTrue(timeout.retryable)
        assertTrue(timeout.ownerReleased)
        assertEquals(false, timeout.terminalDescriptorWritten)
    }

    @AfterAll
    fun `manifest contains exactly four complete checksum-valid profiles`() {
        assertEquals(EXPECTED_PROFILES, profiles)
        val runDirectory = outputRoot.resolve(runId)
        val manifest = Files.readString(runDirectory.resolve("manifest.json"))
        assertEquals(4, Regex("\\\"profile\\\"").findAll(manifest).count())
        val artifacts = EXPECTED_PROFILES.flatMap { profile ->
            listOf(runDirectory.resolve("$profile.json"), runDirectory.resolve("$profile.threads.txt"))
        }
        assertTrue(artifacts.all { Files.size(it) > 0L })
        val checksums = artifacts.map(::sha256)
        assertEquals(checksums.size, checksums.toSet().size)
        checksums.forEach { checksum -> assertTrue(manifest.contains(checksum)) }
    }

    private fun assertHardGates(evidence: VoucherPoolStressEvidence) {
        assertTrue(evidence.hikariActiveMax <= 16)
        assertTrue(evidence.hikariAcquisitionWaitMaxMillis <= 2_000)
        assertTrue(evidence.totalPermitHoldersMax <= 16)
        assertTrue(evidence.foregroundWaitMaxMillis <= 250)
        assertTrue(evidence.workerWaitMaxMillis <= 1_000)
        assertTrue(evidence.sseWaitMaxMillis <= 1_000)
        assertEquals(0, evidence.deadlineViolations)
        assertTrue(evidence.hikariPendingMax <= 1)
        assertTrue(evidence.hikariPendingDrainMillis <= 12_000)
        assertTrue(evidence.workerCheckpointProgress > 0)
        assertEquals(0, evidence.duplicateWinnerCount)
        assertEquals(evidence.winners, evidence.authoritativeAllocationCount)
        assertEquals(evidence.successfulResponses, evidence.authoritativeAllocationCount)
        assertEquals(evidence.authoritativeReservationCount, evidence.authoritativeAllocationCount)
        assertEquals(evidence.stateCountSum, evidence.entryCount)
        assertEquals(0, evidence.acquisitionTimeoutTerminalDescriptors)
        assertEquals(0, evidence.connectionLeaks)
        assertEquals(0, evidence.permitLeaks)
        assertEquals(0L, evidence.counterDrift)
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
