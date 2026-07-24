package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.support.requireNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class HighContentionReportTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `terminal result vocabulary and submission conservation are closed`() {
        HighContentionTerminalResult(
            terminalStatus = HighContentionTerminalStatus.PASS,
            correctness = HighContentionCorrectness.PASS,
            errorCode = HighContentionErrorCode.NONE,
            failedPhase = null,
            failedInvariantIds = emptyList(),
            redactedErrorClass = null,
        ).validate()
        HighContentionSubmissionConservation(
            scheduledCount = 8,
            dispatchedCount = 6,
            completedCount = 3,
            cancelledCount = 1,
            timedOutCount = 2,
            locallyRejectedCount = 2,
            terminalDispositionCounts = dispositionCounts(
                HighContentionTerminalDisposition.SUCCEEDED to 1,
                HighContentionTerminalDisposition.FAILED_CLOSED to 1,
                HighContentionTerminalDisposition.DUPLICATE_SUPPRESSED to 1,
                HighContentionTerminalDisposition.LOCALLY_REJECTED to 2,
                HighContentionTerminalDisposition.CANCELLED to 1,
                HighContentionTerminalDisposition.TIMED_OUT to 2,
            ),
        ).validate()
        HighContentionAttemptConservation(
            expectedAttemptsByIdentity = mapOf(
                0 to HighContentionIdentityAttemptExpectation(
                    attemptCount = 2,
                    winnerRule = HighContentionWinnerRule.EXACTLY_ONE,
                ),
                1 to HighContentionIdentityAttemptExpectation(
                    attemptCount = 1,
                    winnerRule = HighContentionWinnerRule.ZERO,
                ),
            ),
            attempts = listOf(
                attempt(0, 0, 0, winners = 1),
                attempt(1, 0, 1, winners = 0),
                attempt(2, 1, 0, winners = 0),
            ),
        ).validate()

        assertFailsWith<IllegalArgumentException> {
            HighContentionTerminalResult(
                terminalStatus = HighContentionTerminalStatus.ERROR,
                correctness = HighContentionCorrectness.NOT_EVALUATED,
                errorCode = HighContentionErrorCode.NONE,
                failedPhase = "workload",
                failedInvariantIds = emptyList(),
                redactedErrorClass = "java.lang.IllegalStateException",
            ).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            HighContentionAttemptConservation(
                expectedAttemptsByIdentity = mapOf(
                    0 to HighContentionIdentityAttemptExpectation(
                        attemptCount = 2,
                        winnerRule = HighContentionWinnerRule.EXACTLY_ONE,
                    ),
                ),
                attempts = listOf(
                    attempt(0, 0, 0, winners = 1),
                    attempt(0, 0, 1, winners = 1),
                ),
            ).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            HighContentionSubmissionConservation(
                scheduledCount = 8,
                dispatchedCount = 7,
                completedCount = 3,
                cancelledCount = 1,
                timedOutCount = 2,
                locallyRejectedCount = 2,
                terminalDispositionCounts = dispositionCounts(
                    HighContentionTerminalDisposition.SUCCEEDED to 1,
                    HighContentionTerminalDisposition.FAILED_CLOSED to 1,
                    HighContentionTerminalDisposition.DUPLICATE_SUPPRESSED to 1,
                    HighContentionTerminalDisposition.LOCALLY_REJECTED to 2,
                    HighContentionTerminalDisposition.CANCELLED to 1,
                    HighContentionTerminalDisposition.TIMED_OUT to 2,
                ),
            ).validate()
        }
    }

    @Test
    fun `percentiles expose partial sample status and saturation uses left-closed intervals`() {
        val percentiles = HighContentionMeasurements.percentiles((1L..20L).toList())

        percentiles.p50 shouldBeEqualTo HighContentionPercentile(
            status = HighContentionPercentileStatus.MEASURED,
            valueNanos = 10L,
        )
        percentiles.p95 shouldBeEqualTo HighContentionPercentile(
            status = HighContentionPercentileStatus.MEASURED,
            valueNanos = 19L,
        )
        percentiles.p99 shouldBeEqualTo HighContentionPercentile(
            status = HighContentionPercentileStatus.INSUFFICIENT_SAMPLES,
        )
        HighContentionMeasurements.notApplicablePercentiles().p50.status shouldBeEqualTo
            HighContentionPercentileStatus.NOT_APPLICABLE

        val saturation = HighContentionMeasurements.saturation(
            samples = listOf(
                HighContentionSaturationSample(atNanos = 100, used = 2, capacity = 2),
                HighContentionSaturationSample(atNanos = 130, used = 1, capacity = 2),
                HighContentionSaturationSample(atNanos = 160, used = 2, capacity = 2),
            ),
            lastTerminalNanos = 200,
        )
        saturation.sampleCount shouldBeEqualTo 3
        saturation.maxUsed shouldBeEqualTo 2
        saturation.timeAtCapacityNanos shouldBeEqualTo 70L
        assertFailsWith<IllegalArgumentException> {
            HighContentionMeasurements.saturation(
                samples = listOf(
                    HighContentionSaturationSample(atNanos = 100, used = 1, capacity = 2),
                    HighContentionSaturationSample(atNanos = 201, used = 1, capacity = 2),
                ),
                lastTerminalNanos = 200,
            )
        }
    }

    @Test
    fun `terminal report cross-validates operation and cleanup evidence`() {
        report().validate()
        report().deadlines.single().copy(
            expired = true,
            timeoutOrigin = HighContentionTimeoutOrigin.OPERATION,
        ).validate()

        assertFailsWith<IllegalArgumentException> {
            val workload = report().workload.requireNotNull("evaluated workload")
            report().copy(
                workload = workload.copy(
                    effectiveConfiguration = workload.effectiveConfiguration.copy(operationCount = 2),
                ),
            ).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            report().copy(
                cleanup = HighContentionCleanupSummary(
                    result = HighContentionCleanupResult.PASS,
                    resourceOutcomes = listOf(
                        HighContentionResourceOutcome("database", HighContentionResourceState.CLOSE_FAILED),
                    ),
                ),
            ).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            report().copy(
                deadlines = listOf(
                    report().deadlines.single().copy(
                        expired = true,
                        timeoutOrigin = HighContentionTimeoutOrigin.NONE,
                    ),
                ),
            ).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            report().copy(
                deadlines = listOf(
                    report().deadlines.single().copy(
                        timeoutOrigin = HighContentionTimeoutOrigin.OPERATION,
                    ),
                ),
            ).validate()
        }
    }

    @Test
    fun `execution failure preserves disposition failure point and terminal error evidence`() {
        val base = report()
        val baseWorkload = base.workload.requireNotNull("base workload")
        val executionFailureWorkload = baseWorkload.copy(
            submissionConservation = baseWorkload.submissionConservation.copy(
                terminalDispositionCounts = dispositionCounts(
                    HighContentionTerminalDisposition.EXECUTION_FAILED to 1,
                ),
            ),
            attemptConservation = HighContentionAttemptConservation(
                expectedAttemptsByIdentity = mapOf(
                    0 to HighContentionIdentityAttemptExpectation(
                        attemptCount = 1,
                        winnerRule = HighContentionWinnerRule.AT_MOST_ONE,
                    ),
                ),
                attempts = listOf(
                    attempt(
                        stableOrdinal = 0,
                        identityOrdinal = 0,
                        attemptOrdinal = 0,
                        winners = 0,
                        terminalDisposition = HighContentionTerminalDisposition.EXECUTION_FAILED,
                        failurePoint = HighContentionFailurePoint.UNKNOWN,
                    ),
                ),
            ),
        )
        base.copy(
            workload = executionFailureWorkload,
            result = HighContentionTerminalResult(
                terminalStatus = HighContentionTerminalStatus.ERROR,
                correctness = HighContentionCorrectness.PASS,
                errorCode = HighContentionErrorCode.EXECUTION_ERROR,
                failedPhase = "workload",
                failedInvariantIds = emptyList(),
                redactedErrorClass = "java.lang.IllegalStateException",
            ),
        ).validate()

        assertFailsWith<IllegalArgumentException> {
            base.copy(workload = executionFailureWorkload).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            executionFailureWorkload.copy(
                attemptConservation = executionFailureWorkload.attemptConservation.copy(
                    attempts = executionFailureWorkload.attemptConservation.attempts.map {
                        it.copy(failurePoint = HighContentionFailurePoint.NONE)
                    },
                ),
            ).validate()
        }
    }

    @Test
    fun `preflight unavailable preserves an honestly not-evaluated result`() {
        val unavailable = report().copy(
            workload = null,
            invariantResults = emptyList(),
            result = HighContentionTerminalResult(
                terminalStatus = HighContentionTerminalStatus.UNAVAILABLE,
                correctness = HighContentionCorrectness.NOT_EVALUATED,
                errorCode = HighContentionErrorCode.PREFLIGHT_UNAVAILABLE,
                failedPhase = "preflight",
                failedInvariantIds = emptyList(),
                redactedErrorClass = "java.lang.IllegalStateException",
            ),
        )

        unavailable.validate()
        val path = HighContentionArtifactStore.create(tempDir, "run-01")
            .writeTerminalReport("job-core", "burst", unavailable)
        Files.readString(path).contains("\"workload\":null") shouldBeEqualTo true
        assertFailsWith<IllegalArgumentException> {
            unavailable.copy(invariantResults = report().invariantResults).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            unavailable.copy(workload = report().workload).validate()
        }
    }

    @Test
    fun `artifact store validates run paths redacts sentinels and never replaces reports`() {
        val store = HighContentionArtifactStore.create(tempDir, "run-01")
        val report = report()

        val path = store.writeTerminalReport("job-core", "burst", report)
        Files.exists(path) shouldBeEqualTo true
        assertFailsWith<HighContentionArtifactException> {
            store.writeTerminalReport("job-core", "burst", report)
        }
        assertFailsWith<HighContentionArtifactException> {
            HighContentionArtifactStore.create(tempDir, "run-01")
        }
        assertFailsWith<IllegalArgumentException> {
            HighContentionArtifactStore.create(tempDir, "../escape")
        }
        val outside = Files.createTempDirectory("high-contention-artifact-outside")
        val linkedRoot = tempDir.resolve("linked-root")
        Files.createSymbolicLink(linkedRoot, outside)
        assertFailsWith<HighContentionArtifactException> {
            HighContentionArtifactStore.create(linkedRoot, "run-03")
        }
        assertFailsWith<HighContentionRedactionException> {
            store.verifyRedaction(Files.readString(path) + " sentinel-token")
        }
    }

    @Test
    fun `parent-owned artifact store opens only an existing trusted run root`() {
        Files.createDirectory(tempDir.resolve("parent-run"))
        val store = HighContentionArtifactStore.create(
            outputRoot = tempDir,
            runId = "parent-run",
            parentOwnedRun = true,
        )
        val path = store.writeTerminalReport(
            "job-core",
            "burst",
            report().copy(runId = "parent-run"),
        )

        Files.isRegularFile(path) shouldBeEqualTo true
        assertFailsWith<HighContentionArtifactException> {
            HighContentionArtifactStore.create(
                outputRoot = tempDir,
                runId = "missing-parent-run",
                parentOwnedRun = true,
            )
        }
    }

    @Test
    fun `serialization failure is preserved as a redacted fallback journal record`() {
        val journalPath = tempDir.resolve("journal.jsonl")
        val store = HighContentionArtifactStore.create(
            outputRoot = tempDir.resolve("artifacts"),
            runId = "run-02",
            forbiddenSentinels = setOf("raw-secret"),
            reportSerializer = HighContentionReportSerializer {
                error("raw-secret must not escape")
            },
        )

        HighContentionJournal.open(tempDir, Path.of("journal.jsonl")).use { journal ->
            val result = store.writeTerminalReportOrFallback(
                implementation = "job-core",
                profileId = "burst",
                report = report().copy(runId = "run-02"),
                journal = journal,
            )
            result shouldBeEqualTo HighContentionReportWriteResult.FALLBACK_JOURNALED
        }

        val fallback = HighContentionJournal.read(journalPath).single()
        fallback.payload.event shouldBeEqualTo HighContentionJournalEvent.REPORT_SERIALIZATION_FALLBACK
        fallback.payload.fields shouldBeEqualTo mapOf(
            "errorCode" to "REPORT_SERIALIZATION",
            "redactedErrorClass" to "java.lang.IllegalStateException",
            "terminalStatus" to "ERROR",
        )
    }

    private fun report(): HighContentionTerminalReport =
        HighContentionTerminalReport(
            reportSchemaVersion = 1,
            suiteSchemaVersion = 1,
            profileSchemaVersion = 1,
            runId = "run-01",
            mode = HighContentionMode.CI_CORRECTNESS,
            profileId = "burst",
            implementation = "job-core",
            startedAt = Instant.parse("2026-07-24T00:00:00Z"),
            endedAt = Instant.parse("2026-07-24T00:00:01Z"),
            environment = HighContentionEnvironmentEvidence(
                sourceCommit = "0123456789abcdef",
                sourceDirty = false,
                sourceReproducible = true,
            ),
            phaseDurationsNanos = mapOf("workload" to 1_000_000_000),
            workload = HighContentionWorkloadEvidence(
                effectiveConfiguration = HighContentionEffectiveConfiguration(
                    operationCount = 1,
                    concurrency = 1,
                    dispatcherBacklogCapacity = 0,
                    warmupOperationCount = 0,
                ),
                schedule = HighContentionScheduleEvidence(
                    expectedTokenCount = 1,
                    expectedScheduleSha256 = "0".repeat(64),
                    realizedTokenManifestSha256 = "0".repeat(64),
                ),
                submissionConservation = HighContentionSubmissionConservation(
                    scheduledCount = 1,
                    dispatchedCount = 1,
                    completedCount = 1,
                    cancelledCount = 0,
                    timedOutCount = 0,
                    locallyRejectedCount = 0,
                    terminalDispositionCounts = dispositionCounts(
                        HighContentionTerminalDisposition.SUCCEEDED to 1,
                    ),
                ),
                attemptConservation = HighContentionAttemptConservation(
                    expectedAttemptsByIdentity = mapOf(
                        0 to HighContentionIdentityAttemptExpectation(
                            attemptCount = 1,
                            winnerRule = HighContentionWinnerRule.EXACTLY_ONE,
                        ),
                    ),
                    attempts = listOf(attempt(0, 0, 0, winners = 1)),
                ),
            ),
            failureInjection = HighContentionFailureInjectionEvidence(
                type = FailureKind.NONE,
                steps = emptyList(),
            ),
            invariantResults = listOf(
                HighContentionInvariantResult(
                    invariantId = "job-authority",
                    authority = "postgresql",
                    expectation = "one winner",
                    observation = "one winner",
                    status = HighContentionInvariantStatus.PASS,
                    evidenceReference = "evidence/job-authority.json",
                ),
            ),
            observations = HighContentionReportObservations(),
            deadlines = listOf(
                HighContentionDeadlineEvidence(
                    phase = "workload",
                    configuredBudgetNanos = 1_000_000_000,
                    effectiveBudgetNanos = 1_000_000_000,
                    absoluteDeadlineNanos = 2_000_000_000,
                    actualDurationNanos = 1_000_000_000,
                    expired = false,
                    timeoutOrigin = HighContentionTimeoutOrigin.NONE,
                ),
            ),
            result = HighContentionTerminalResult(
                terminalStatus = HighContentionTerminalStatus.PASS,
                correctness = HighContentionCorrectness.PASS,
                errorCode = HighContentionErrorCode.NONE,
                failedPhase = null,
                failedInvariantIds = emptyList(),
                redactedErrorClass = null,
            ),
            cleanup = HighContentionCleanupSummary(HighContentionCleanupResult.PASS),
            knownLimitations = listOf("developer-or-ci-reference"),
        )

    private fun attempt(
        stableOrdinal: Int,
        identityOrdinal: Int,
        attemptOrdinal: Int,
        winners: Int,
        terminalDisposition: HighContentionTerminalDisposition = HighContentionTerminalDisposition.SUCCEEDED,
        failurePoint: HighContentionFailurePoint = HighContentionFailurePoint.NONE,
    ): HighContentionAttemptEvidence =
        HighContentionAttemptEvidence(
            stableOrdinal = stableOrdinal,
            identityOrdinal = identityOrdinal,
            attemptOrdinal = attemptOrdinal,
            terminalDisposition = terminalDisposition,
            failurePoint = failurePoint,
            authorityWinnerCount = winners,
            effectWinnerCount = winners,
            receiptWinnerCount = winners,
        )

    private fun dispositionCounts(
        vararg counts: Pair<HighContentionTerminalDisposition, Int>,
    ): Map<HighContentionTerminalDisposition, Int> {
        val overrides = counts.toMap()
        return HighContentionTerminalDisposition.entries.associateWith { overrides[it] ?: 0 }
    }
}
