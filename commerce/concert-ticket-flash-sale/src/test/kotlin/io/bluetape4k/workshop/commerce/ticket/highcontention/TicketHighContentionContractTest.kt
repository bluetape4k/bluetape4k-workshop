package io.bluetape4k.workshop.commerce.ticket.highcontention

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.virtualthread.VirtualThreads
import io.bluetape4k.testcontainers.storage.RedisServer
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.DockerClientFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.APPEND
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class TicketHighContentionContractTest {
    @Test
    fun `profile application exposes production services from a Hikari backed Spring context`() {
        TicketHighContentionProfileApplication.start(RedisServer.Launcher.redis.url).use { application ->
            application.dataSource.maximumPoolSize shouldBeEqualTo 20
            application.dataSource.isClosed shouldBeEqualTo false
            application.jdbc
            application.purchases
            application.paymentWorker
            application.ticketWorker
        }
    }

    @Test
    fun `Ticket Redis path cuts old and new proxy connections then recovers`(@TempDir tempDir: Path) {
        val artifacts = TicketHighContentionArtifactStore.create(tempDir.toRealPath(), "topology-contract")
        artifacts.createJournal().use { journal ->
            TicketProxiedTopology.start("topology-contract", "redis-path-outage", journal).use { topology ->
                topology.routesOnlyThroughProxy().shouldBeTrue()
                topology.openConnection().use { oldConnection ->
                    oldConnection.ping() shouldBeEqualTo "PONG"
                    topology.cutExistingConnections()
                    await.atMost(Duration.ofSeconds(5)).untilAsserted {
                        assertFailsWith<Exception> { oldConnection.ping() }
                    }
                    topology.recover()
                    topology.openConnection().use { recovered ->
                        recovered.ping() shouldBeEqualTo "PONG"
                    }
                    topology.disableNewConnections()
                    await.atMost(Duration.ofSeconds(5)).untilAsserted {
                        assertFailsWith<Exception> {
                            topology.openConnection().use(TicketRedisProbeConnection::ping)
                        }
                    }
                    topology.recover()
                    topology.openConnection().use { recovered ->
                        await.atMost(Duration.ofSeconds(5)).untilAsserted {
                            recovered.ping() shouldBeEqualTo "PONG"
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `topology crash cutpoints preserve intent and clean every labeled Docker object`(@TempDir tempDir: Path) {
        listOf(
            TicketTopologyCutpoint.Phase.BEFORE_CREATE to "before",
            TicketTopologyCutpoint.Phase.AFTER_CREATE to "after",
        ).forEach { (phase, suffix) ->
            val runId = "ticket-cutpoint-$suffix-${UUID.randomUUID()}"
            val artifacts = TicketHighContentionArtifactStore.create(tempDir.toRealPath(), runId)
            val journalPath = artifacts.journalPath
            artifacts.createJournal().use { journal ->
                assertFailsWith<ExpectedTicketCutpointException> {
                    TicketProxiedTopology.start(
                        runId = runId,
                        profileId = "redis-path-outage",
                        journal = journal,
                        cutpoint = TicketTopologyCutpoint { observedPhase, resourceKey ->
                            if (observedPhase == phase && resourceKey == "redis") {
                                throw ExpectedTicketCutpointException()
                            }
                        },
                    )
                }
            }

            val redisRecords = TicketHighContentionJournal.read(journalPath).filter {
                it.payload.fields["resourceKey"] == "redis"
            }
            redisRecords.map { it.payload.event } shouldBeEqualTo listOf("DOCKER_CREATE_INTENT")
            assertNoDockerObjects(runId)
        }
    }

    @Test
    fun `Ticket independently loads every profile and matches every golden schedule vector`() {
        val contract = TicketHighContentionContractLoader().load(
            contractRoot(),
            TicketHighContentionMode.CI_CORRECTNESS,
        )

        contract.selections.map { it.profile.profileId } shouldBeEqualTo PROFILE_IDS
        contract.selections.all { it.implementation == "ticket-spring" }.shouldBeTrue()
        contract.scheduleVectors.vectors.forEach { vector ->
            TicketDeterministicSchedule.generate(vector) shouldBeEqualTo vector.expectedTokens
        }
    }

    @Test
    fun `duplicate and unknown fields fail closed`(@TempDir tempDir: Path) {
        val duplicateRoot = copyContract(tempDir.resolve("duplicate"))
        val duplicateManifest = duplicateRoot.resolve("suite-manifest.json")
        Files.writeString(
            duplicateManifest,
            Files.readString(duplicateManifest).replaceFirst("{", "{\"suiteSchemaVersion\":1,"),
        )
        assertFailsWith<TicketHighContentionContractException> {
            TicketHighContentionContractLoader().load(
                duplicateRoot,
                TicketHighContentionMode.CI_CORRECTNESS,
                "burst",
            )
        }

        val unknownRoot = copyContract(tempDir.resolve("unknown"))
        val unknownManifest = unknownRoot.resolve("suite-manifest.json")
        Files.writeString(
            unknownManifest,
            Files.readString(unknownManifest).replaceFirst("{", "{\"unexpected\":true,"),
        )
        assertFailsWith<TicketHighContentionContractException> {
            TicketHighContentionContractLoader().load(
                unknownRoot,
                TicketHighContentionMode.CI_CORRECTNESS,
                "burst",
            )
        }
    }

    @Test
    fun `symbolic links and identity replacement are rejected`(@TempDir tempDir: Path) {
        val linkedRoot = tempDir.resolve("linked")
        Files.createSymbolicLink(linkedRoot, contractRoot())
        assertFailsWith<TicketHighContentionContractException> {
            TicketHighContentionContractLoader().load(
                linkedRoot,
                TicketHighContentionMode.CI_CORRECTNESS,
                "burst",
            )
        }

        val replacedRoot = copyContract(tempDir.resolve("replaced"))
        val replacedFile = replacedRoot.resolve("schedule-vectors.json")
        val loader = TicketHighContentionContractLoader { path ->
            if (path == replacedFile) {
                val replacement = Files.createTempFile(replacedRoot, "replacement-", ".json")
                Files.writeString(replacement, Files.readString(path))
                Files.move(replacement, path, ATOMIC_MOVE, REPLACE_EXISTING)
            }
        }
        assertFailsWith<TicketHighContentionContractException> {
            loader.load(replacedRoot, TicketHighContentionMode.CI_CORRECTNESS, "burst")
        }
    }

    @Test
    fun `warm-up precedes baseline and stays outside measured conservation`() {
        val events = mutableListOf<String>()
        val adapter = object : TicketHighContentionWorkloadAdapter {
            override fun warmUp(identity: TicketWorkloadIdentity) {
                events += "warmup:${identity.namespace}:${identity.ordinal}"
            }

            override fun snapshotBaseline(): String {
                events += "baseline"
                return "baseline-1"
            }

            override fun execute(
                token: TicketScheduleToken,
                identity: TicketWorkloadIdentity,
            ): TicketWorkloadDisposition {
                events += "measured:${identity.namespace}:${identity.ordinal}"
                return TicketWorkloadDisposition.COMPLETED
            }
        }

        val result = TicketHighContentionWorkloadEngine(Duration.ofSeconds(5)).run(
            schedule = burstSchedule(operationCount = 3),
            warmupOperationCount = 2,
            warmupNamespace = "ticket:warmup:",
            measuredNamespace = "ticket:measured:",
            concurrency = 1,
            dispatcherBacklogCapacity = 3,
            maxScheduleDelayNanos = Long.MAX_VALUE,
            adapter = adapter,
        )

        events.take(3) shouldBeEqualTo listOf(
            "warmup:ticket:warmup::0",
            "warmup:ticket:warmup::1",
            "baseline",
        )
        events.drop(3).toSet() shouldBeEqualTo setOf(
            "measured:ticket:measured::0",
            "measured:ticket:measured::1",
            "measured:ticket:measured::2",
        )
        result.baseline shouldBeEqualTo "baseline-1"
        result.scheduledCount shouldBeEqualTo 3
        result.dispatchedCount shouldBeEqualTo 3
        result.completedCount shouldBeEqualTo 3
        result.expectedScheduleDigest shouldBeEqualTo result.realizedScheduleDigest
    }

    @Test
    fun `schedule threshold fault observer overlaps measured work`() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val completed = AtomicInteger()
        val observerCompletedCount = AtomicInteger(-1)
        val adapter = object : TicketHighContentionWorkloadAdapter {
            override fun warmUp(identity: TicketWorkloadIdentity) = Unit

            override fun snapshotBaseline(): String = "baseline"

            override fun execute(
                token: TicketScheduleToken,
                identity: TicketWorkloadIdentity,
            ): TicketWorkloadDisposition {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                completed.incrementAndGet()
                return TicketWorkloadDisposition.COMPLETED
            }
        }

        TicketHighContentionWorkloadEngine(Duration.ofSeconds(5)).run(
            schedule = burstSchedule(operationCount = 2),
            warmupOperationCount = 0,
            warmupNamespace = "ticket:warmup:",
            measuredNamespace = "ticket:measured:",
            concurrency = 2,
            dispatcherBacklogCapacity = 0,
            maxScheduleDelayNanos = Long.MAX_VALUE,
            adapter = adapter,
            faultObserverStartAfterScheduledCount = 2,
            faultObserver = {
                check(entered.await(5, TimeUnit.SECONDS))
                observerCompletedCount.set(completed.get())
                release.countDown()
            },
        )

        observerCompletedCount.get() shouldBeEqualTo 0
    }

    @Test
    fun `end of schedule fault observer starts after measured work completes`() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val observerStarted = CountDownLatch(1)
        val completed = AtomicInteger()
        val observerCompletedCount = AtomicInteger(-1)
        val adapter = object : TicketHighContentionWorkloadAdapter {
            override fun warmUp(identity: TicketWorkloadIdentity) = Unit

            override fun snapshotBaseline(): String = "baseline"

            override fun execute(
                token: TicketScheduleToken,
                identity: TicketWorkloadIdentity,
            ): TicketWorkloadDisposition {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                completed.incrementAndGet()
                return TicketWorkloadDisposition.COMPLETED
            }
        }

        VirtualThreads.executorService().use { controller ->
            val observerStartedBeforeRelease = controller.submit<Boolean> {
                check(entered.await(5, TimeUnit.SECONDS))
                observerStarted.await(250, TimeUnit.MILLISECONDS).also {
                    release.countDown()
                }
            }
            TicketHighContentionWorkloadEngine(Duration.ofSeconds(5)).run(
                schedule = burstSchedule(operationCount = 2),
                warmupOperationCount = 0,
                warmupNamespace = "ticket:warmup:",
                measuredNamespace = "ticket:measured:",
                concurrency = 2,
                dispatcherBacklogCapacity = 0,
                maxScheduleDelayNanos = Long.MAX_VALUE,
                adapter = adapter,
                faultObserverStartAfterScheduledCount = 2,
                faultObserverTiming = TicketFaultObserverTiming.WORKLOAD_COMPLETION,
                faultObserver = {
                    observerCompletedCount.set(completed.get())
                    observerStarted.countDown()
                },
            )

            observerStartedBeforeRelease.get(5, TimeUnit.SECONDS).shouldBeFalse()
        }
        observerCompletedCount.get() shouldBeEqualTo 2
    }

    @Test
    fun `overlapping warm-up namespace and invalid realization fail closed`() {
        val schedule = burstSchedule(operationCount = 2)
        val adapter = object : TicketHighContentionWorkloadAdapter {
            override fun warmUp(identity: TicketWorkloadIdentity) = Unit
            override fun snapshotBaseline(): String = "baseline"
            override fun execute(
                token: TicketScheduleToken,
                identity: TicketWorkloadIdentity,
            ): TicketWorkloadDisposition = TicketWorkloadDisposition.COMPLETED
        }
        assertFailsWith<IllegalArgumentException> {
            TicketHighContentionWorkloadEngine(Duration.ofSeconds(5)).run(
                schedule = schedule,
                warmupOperationCount = 1,
                warmupNamespace = "ticket:shared:",
                measuredNamespace = "ticket:shared:measured:",
                concurrency = 1,
                dispatcherBacklogCapacity = 1,
                maxScheduleDelayNanos = Long.MAX_VALUE,
                adapter = adapter,
            )
        }

        val first = TicketWorkloadRecord(
            token = schedule.first(),
            disposition = TicketWorkloadDisposition.COMPLETED,
            missedDeadline = false,
        )
        assertFailsWith<IllegalStateException> {
            TicketHighContentionWorkloadEngine.validateRealization(schedule, listOf(first))
        }
        assertFailsWith<IllegalStateException> {
            TicketHighContentionWorkloadEngine.validateRealization(schedule, listOf(first, first))
        }
        assertFailsWith<IllegalStateException> {
            TicketHighContentionWorkloadEngine.validateRealization(
                schedule,
                listOf(
                    first,
                    TicketWorkloadRecord(
                        token = schedule.last().copy(stableOrdinal = 99),
                        disposition = TicketWorkloadDisposition.COMPLETED,
                        missedDeadline = false,
                    ),
                ),
            )
        }
    }

    @Test
    fun `artifact reports are create-new contained complete and redacted`(@TempDir tempDir: Path) {
        val trustedRoot = tempDir.toRealPath()
        val store = TicketHighContentionArtifactStore.create(trustedRoot, "artifact-contract")
        val report = terminalReport()
        val reportPath = store.writeTerminalReport(
            implementation = "ticket-spring",
            profileId = "burst",
            report = report,
            requiredFields = report.keys,
            forbiddenPatterns = listOf("raw-ticket-sentinel"),
        )

        Files.isRegularFile(reportPath).shouldBeTrue()
        assertFailsWith<TicketHighContentionArtifactException> {
            store.writeTerminalReport("ticket-spring", "burst", report, report.keys, emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            store.writeTerminalReport(
                "ticket-spring",
                "slow-provider",
                report + ("observations" to "raw-ticket-sentinel"),
                report.keys,
                listOf("raw-ticket-sentinel"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            store.writeTerminalReport(
                "ticket-spring",
                "missing-field",
                report - "cleanup",
                report.keys,
                emptyList(),
            )
        }
        assertFailsWith<TicketHighContentionArtifactException> {
            TicketHighContentionArtifactStore.create(trustedRoot, "artifact-contract")
        }

        val outside = Files.createTempDirectory("ticket-artifact-outside")
        val linkedRoot = trustedRoot.resolve("linked-root")
        Files.createSymbolicLink(linkedRoot, outside)
        assertFailsWith<TicketHighContentionArtifactException> {
            TicketHighContentionArtifactStore.create(linkedRoot, "escaped")
        }
    }

    @Test
    fun `parent-owned Ticket artifact store opens only an existing trusted run root`(@TempDir tempDir: Path) {
        val trustedRoot = tempDir.toRealPath()
        Files.createDirectory(trustedRoot.resolve("parent-run"))
        val store = TicketHighContentionArtifactStore.create(
            outputRoot = trustedRoot,
            runId = "parent-run",
            parentOwnedRun = true,
        )
        val report = terminalReport() + ("runId" to "parent-run")
        val path = store.writeTerminalReport(
            implementation = "ticket-spring",
            profileId = "burst",
            report = report,
            requiredFields = report.keys,
            forbiddenPatterns = emptyList(),
        )

        Files.isRegularFile(path).shouldBeTrue()
        assertFailsWith<TicketHighContentionArtifactException> {
            TicketHighContentionArtifactStore.create(
                outputRoot = trustedRoot,
                runId = "missing-parent-run",
                parentOwnedRun = true,
            )
        }
    }

    @Test
    fun `journal validates its chain and ignores only a torn final record`(@TempDir tempDir: Path) {
        val store = TicketHighContentionArtifactStore.create(tempDir.toRealPath(), "journal-contract")
        val path = store.journalPath
        store.createJournal().use { journal ->
            journal.append("phase", mapOf("state" to "setup"))
            journal.append("phase", mapOf("state" to "workload"))
        }

        val records = TicketHighContentionJournal.read(path)
        records.map(TicketHighContentionJournalRecord::sequence) shouldBeEqualTo listOf(0L, 1L)
        records[1].previousRecordSha256 shouldBeEqualTo records[0].recordSha256

        Files.writeString(path, """{"sequence":2""", APPEND)
        TicketHighContentionJournal.read(path).size shouldBeEqualTo 2

        val corrupted = Files.readString(path).replaceFirst("\"setup\"", "\"tampered\"")
        Files.writeString(path, corrupted)
        assertFailsWith<TicketHighContentionJournalException> {
            TicketHighContentionJournal.read(path)
        }
    }

    private fun burstSchedule(operationCount: Int): List<TicketScheduleToken> =
        TicketDeterministicSchedule.generate(
            TicketScheduleVector(
                name = "ticket-contract-burst",
                profileSchemaVersion = 1,
                seed = "ticket-contract",
                curve = TicketArrivalCurve.BURST,
                operationCount = operationCount,
                durationNanos = 1,
                authorityWeights = listOf(1),
                epochs = emptyList(),
                retryShape = null,
                expectedTokens = emptyList(),
            ),
        )

    private fun terminalReport(): Map<String, Any?> =
        linkedMapOf(
            "reportSchemaVersion" to 1,
            "suiteSchemaVersion" to 1,
            "profileSchemaVersion" to 1,
            "runId" to "artifact-contract",
            "profileId" to "burst",
            "mode" to "ci-correctness",
            "implementation" to "ticket-spring",
            "startedAt" to "2026-07-24T00:00:00Z",
            "endedAt" to "2026-07-24T00:00:01Z",
            "environment" to emptyMap<String, String>(),
            "phaseDurationsNanos" to emptyMap<String, Long>(),
            "workload" to emptyMap<String, Any?>(),
            "failureInjection" to emptyMap<String, Any?>(),
            "invariantResults" to emptyList<Any>(),
            "observations" to emptyMap<String, Any?>(),
            "deadlines" to emptyList<Any>(),
            "observationScope" to "measured",
            "crossImplementationComparable" to true,
            "productionCapacityClaim" to false,
            "result" to mapOf("terminalStatus" to "PASS", "errorCode" to "NONE"),
            "cleanup" to mapOf("result" to "PASS"),
            "knownLimitations" to emptyList<String>(),
        )

    private fun assertNoDockerObjects(runId: String) {
        val docker = DockerClientFactory.instance().client()
        val labels = mapOf(TicketDockerResource.RUN_LABEL to runId)
        docker.listContainersCmd()
            .withShowAll(true)
            .withLabelFilter(labels)
            .exec() shouldBeEqualTo emptyList()
        docker.listNetworksCmd()
            .withFilter("label", labels.map { (key, value) -> "$key=$value" })
            .exec() shouldBeEqualTo emptyList()
    }

    private fun copyContract(target: Path): Path {
        val source = contractRoot()
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val destination = target.resolve(source.relativize(path))
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    Files.copy(path, destination)
                }
            }
        }
        return target
    }

    private fun contractRoot(): Path =
        Path.of(requireNotNull(System.getProperty("highContentionContractRoot")) {
            "highContentionContractRoot must be supplied by Gradle"
        })

    private companion object {
        val PROFILE_IDS = listOf(
            "burst",
            "duplicate-storm",
            "redis-path-outage",
            "redis-key-loss",
            "slow-provider",
            "worker-restart",
            "duplicate-delivery",
        )
    }
}

private class ExpectedTicketCutpointException : RuntimeException()
