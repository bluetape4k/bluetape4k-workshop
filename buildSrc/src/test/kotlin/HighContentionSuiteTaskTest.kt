import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HighContentionSuiteTaskTest {

    @Test
    fun `child failure diagnostic identifies tuple bounds output and redacts capabilities`(
        @org.junit.jupiter.api.io.TempDir root: Path,
    ) {
        val outputLog = root.resolve("child.log")
        val capability = "capability-secret"
        val ownerToken = "owner-token-secret"
        Files.writeString(
            outputLog,
            (1..45).joinToString("\n") { line ->
                "line-$line $capability $ownerToken"
            } + "\nThere were failing tests\n",
        )

        val diagnostic = HighContentionChildFailureDiagnostic.describe(
            child = HighContentionChildKey("worker-restart", "ticket-spring"),
            outputLog = outputLog,
            secrets = listOf(capability, ownerToken),
        )

        assertTrue(diagnostic.startsWith("ticket-spring/worker-restart: CHILD_TEST_FAILURE"))
        assertTrue("line-1 [REDACTED]" !in diagnostic)
        assertTrue("line-45 [REDACTED] [REDACTED]" in diagnostic)
        assertTrue(capability !in diagnostic)
        assertTrue(ownerToken !in diagnostic)
    }

    @Test
    fun `selection preserves manifest profile and implementation order`() {
        val entries = listOf(
            HighContentionMatrixEntry("burst", listOf("job-core", "job-spring")),
            HighContentionMatrixEntry("duplicate-storm", listOf("job-core", "job-spring")),
        )

        val selected = HighContentionSuiteSelection.select(
            entries = entries,
            profileId = null,
            implementation = null,
        )

        assertEquals(
            listOf(
                HighContentionChildKey("burst", "job-core"),
                HighContentionChildKey("burst", "job-spring"),
                HighContentionChildKey("duplicate-storm", "job-core"),
                HighContentionChildKey("duplicate-storm", "job-spring"),
            ),
            selected,
        )
    }

    @Test
    fun `workflow identity is derived only from exact hosted run ids`() {
        assertEquals("123-2", HighContentionWorkflowIdentity.fromRunId("examples-123-2"))
        assertEquals("456-1", HighContentionWorkflowIdentity.fromRunId("nightly-456-1"))
        assertEquals("local-0", HighContentionWorkflowIdentity.fromRunId("developer-run"))
        assertEquals("local-0", HighContentionWorkflowIdentity.fromRunId("examples-spoof-2"))
    }

    @Test
    fun `exact filters reject zero selection`() {
        assertFailsWith<IllegalArgumentException> {
            HighContentionSuiteSelection.select(
                entries = listOf(HighContentionMatrixEntry("burst", listOf("job-core"))),
                profileId = "slow-provider",
                implementation = null,
            )
        }
    }

    @Test
    fun `continuation policy fails closed on unsafe cleanup and missing artifacts`() {
        val safe = HighContentionChildGate(
            cleanupZeroLive = true,
            childProcessesZeroLive = true,
            artifactValid = true,
            parentCleanupSucceeded = true,
        )
        assertEquals(
            HighContentionContinuation.CONTINUE,
            HighContentionContinuationPolicy.evaluate(HighContentionChildStatus.PASS, safe),
        )
        assertEquals(
            HighContentionContinuation.CONTINUE_WITH_FINAL_FAILURE,
            HighContentionContinuationPolicy.evaluate(HighContentionChildStatus.FAIL, safe),
        )
        assertEquals(
            HighContentionContinuation.CONTINUE_WITH_FINAL_FAILURE,
            HighContentionContinuationPolicy.evaluate(HighContentionChildStatus.ERROR, safe),
        )
        assertEquals(
            HighContentionContinuation.CONTINUE_WITH_FINAL_FAILURE,
            HighContentionContinuationPolicy.evaluate(HighContentionChildStatus.UNAVAILABLE, safe),
        )

        listOf(
            safe.copy(cleanupZeroLive = false),
            safe.copy(childProcessesZeroLive = false),
            safe.copy(artifactValid = false),
            safe.copy(parentCleanupSucceeded = false),
        ).forEach { unsafe ->
            assertEquals(
                HighContentionContinuation.STOP,
                HighContentionContinuationPolicy.evaluate(HighContentionChildStatus.ERROR, unsafe),
            )
        }
    }

    @Test
    fun `run budget requires one complete profile and clips non cleanup phases`() {
        val budget = HighContentionDeadlineBudget(
            absoluteRunDeadlineNanos = 1_000,
            runCleanupReserveNanos = 200,
        )

        assertTrue(budget.canStartProfile(nowNanos = 100, profileDeadlineNanos = 600))
        assertFalse(budget.canStartProfile(nowNanos = 201, profileDeadlineNanos = 600))
        assertEquals(
            350,
            budget.clipExecutionPhase(
                configuredBudgetNanos = 900,
                nowNanos = 250,
                profileExecutionDeadlineNanos = 600,
            ),
        )
    }

    @Test
    fun `resource allocation has exact labels and rejects duplicate tuples`() {
        val allocator = HighContentionResourceLabelAllocator()
        val allocated = allocator.allocate("run-1", "burst")

        assertEquals(listOf("network", "redis", "toxiproxy"), allocated.map { it.resourceKey })
        allocated.forEach { resource ->
            assertEquals(
                setOf(
                    "io.bluetape4k.high-contention.run-id",
                    "io.bluetape4k.high-contention.profile-id",
                    "io.bluetape4k.high-contention.resource-key",
                    "io.bluetape4k.high-contention.resource-type",
                ),
                resource.labels.keys,
            )
        }
        assertFailsWith<IllegalStateException> {
            allocator.allocate("run-1", "burst")
        }
    }

    @Test
    fun `docker cleanup waits for quiet convergence and deletes only exact labels`() {
        val expected = HighContentionResourceLabelAllocator().allocate("run-1", "burst")
        val docker = FakeDockerCli(
            discoveries = ArrayDeque(
                listOf(
                    listOf(HighContentionDockerObject("container-1", expected[1].labels)),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                ),
            ),
        )
        val clock = MutableClock()

        HighContentionDockerCleanup(
            docker = docker,
            nanoTime = clock::nanoTime,
            sleepMillis = clock::advanceMillis,
        ).cleanup(
            expectedResources = expected,
            absoluteDeadlineNanos = 1_000_000_000,
            quietPeriodMillis = 20,
            pollIntervalMillis = 10,
        )

        assertEquals(listOf("container-1"), docker.deleted)
        assertTrue(docker.queryCount >= 3)
    }

    @Test
    fun `docker cleanup rejects label mismatch and collisions`() {
        val expected = HighContentionResourceLabelAllocator().allocate("run-1", "burst")
        val mismatched = expected[1].labels + (
            "io.bluetape4k.high-contention.profile-id" to "other"
            )
        assertFailsWith<IllegalStateException> {
            HighContentionDockerCleanup(
                docker = FakeDockerCli(
                    ArrayDeque(
                        listOf(listOf(HighContentionDockerObject("container-1", mismatched))),
                    ),
                ),
            ).cleanup(expected, Long.MAX_VALUE, 20, 10)
        }
        assertFailsWith<IllegalStateException> {
            HighContentionDockerCleanup(
                docker = FakeDockerCli(
                    ArrayDeque(
                        listOf(
                            listOf(
                                HighContentionDockerObject("container-1", expected[1].labels),
                                HighContentionDockerObject("container-2", expected[1].labels),
                            ),
                        ),
                    ),
                ),
            ).cleanup(expected, Long.MAX_VALUE, 20, 10)
        }
    }

    @Test
    fun `docker cleanup fails when its independent reserve is exhausted`() {
        val expected = HighContentionResourceLabelAllocator().allocate("run-1", "burst")
        val clock = MutableClock()
        val persistent = HighContentionDockerObject("container-1", expected[1].labels)

        assertFailsWith<IllegalStateException> {
            HighContentionDockerCleanup(
                docker = object : HighContentionDockerCli {
                    override fun find(
                        labels: Map<String, String>,
                        absoluteDeadlineNanos: Long,
                    ) = listOf(persistent)

                    override fun delete(
                        resource: HighContentionDockerObject,
                        absoluteDeadlineNanos: Long,
                    ) = true
                },
                nanoTime = clock::nanoTime,
                sleepMillis = clock::advanceMillis,
            ).cleanup(
                expectedResources = expected,
                absoluteDeadlineNanos = 25_000_000,
                quietPeriodMillis = 20,
                pollIntervalMillis = 10,
            )
        }
    }

    @Test
    fun `docker discovery tolerates exact inspect disappearance`() {
        val id = "a".repeat(12)
        val executor = SequencedDockerCommandExecutor(
            ArrayDeque(
                listOf(
                    HighContentionDockerCommandResult(0, "$id\n"),
                    HighContentionDockerCommandResult(0, ""),
                    HighContentionDockerCommandResult(1, "error: no such object: $id\n"),
                ),
            ),
        )

        val discovered = JdkHighContentionDockerCli(
            nanoTime = { 0L },
            commandExecutor = executor,
        ).find(
            labels = mapOf("io.bluetape4k.high-contention.run-id" to "run-1"),
            absoluteDeadlineNanos = 1_000_000_000,
        )

        assertTrue(discovered.isEmpty())
        assertEquals(3, executor.commands.size)
    }

    @Test
    fun `docker deletion tolerates exact resource disappearance`() {
        val id = "b".repeat(12)
        val executor = SequencedDockerCommandExecutor(
            ArrayDeque(
                listOf(
                    HighContentionDockerCommandResult(
                        1,
                        "Error response from daemon: No such container: $id\n",
                    ),
                ),
            ),
        )
        val deleted = JdkHighContentionDockerCli(
            nanoTime = { 0L },
            commandExecutor = executor,
        ).delete(
            HighContentionDockerObject(
                id = id,
                labels = mapOf(
                    "io.bluetape4k.high-contention.resource-type" to "container",
                ),
            ),
            absoluteDeadlineNanos = 1_000_000_000,
        )

        assertFalse(deleted)
        assertEquals(listOf("docker", "rm", "-f", id), executor.commands.single())
    }

    @Test
    fun `root caller boundary rejects internal channels and accepts exact filters`() {
        HighContentionRootCallerBoundary.validate(
            projectPropertyNames = setOf(
                "highContentionRunId",
                "highContentionProfileId",
                "highContentionImplementation",
            ),
            systemPropertyNames = emptySet(),
            environmentNames = emptySet(),
        )
        assertFailsWith<org.gradle.api.GradleException> {
            HighContentionRootCallerBoundary.validate(
                projectPropertyNames = setOf("highContentionOutputRoot"),
                systemPropertyNames = emptySet(),
                environmentNames = emptySet(),
            )
        }
        assertFailsWith<org.gradle.api.GradleException> {
            HighContentionRootCallerBoundary.validate(
                projectPropertyNames = emptySet(),
                systemPropertyNames = setOf("high.contention.output.root"),
                environmentNames = emptySet(),
            )
        }
    }

    @Test
    fun `run root rejects unsafe empty existing and symlinked paths`(@org.junit.jupiter.api.io.TempDir root: Path) {
        assertFailsWith<IllegalArgumentException> {
            HighContentionRunRoot.create(root, "")
        }
        assertFailsWith<IllegalArgumentException> {
            HighContentionRunRoot.create(root, "../../unsafe")
        }

        HighContentionRunRoot.create(root, "run-1")
        assertFailsWith<IllegalStateException> {
            HighContentionRunRoot.create(root, "run-1")
        }

        val outside = Files.createTempDirectory("high-contention-outside")
        val link = root.resolve("linked-parent")
        link.createSymbolicLinkPointingTo(outside)
        assertFailsWith<IllegalStateException> {
            HighContentionRunRoot.create(link, "run-2")
        }
    }

    @Test
    fun `ticket child finalization removes its transient journal between profiles`(
        @org.junit.jupiter.api.io.TempDir runRoot: Path,
    ) {
        val trustedRunRoot = runRoot.toRealPath()
        listOf("burst", "duplicate-storm").forEach { profileId ->
            val child = HighContentionChildKey(profileId, "ticket-spring")
            val legacyReport = trustedRunRoot.resolve("ticket-spring-$profileId-report.json")
            val transientJournal = trustedRunRoot.resolve("ticket-topology.jsonl")
            Files.writeString(legacyReport, """{"profileId":"$profileId"}""")
            Files.writeString(transientJournal, """{"event":"TOPOLOGY"}""")

            HighContentionChildArtifactFinalizer.finalize(trustedRunRoot, child)

            assertFalse(Files.exists(legacyReport))
            assertFalse(Files.exists(transientJournal))
            assertEquals(
                """{"profileId":"$profileId"}""",
                Files.readString(trustedRunRoot.resolve("reports/ticket-spring/$profileId.json")),
            )
        }
    }

    private class MutableClock {
        private var now = 0L

        fun nanoTime(): Long = now

        fun advanceMillis(millis: Long) {
            now += millis * 1_000_000
        }
    }

    private class FakeDockerCli(
        private val discoveries: ArrayDeque<List<HighContentionDockerObject>>,
    ) : HighContentionDockerCli {
        val deleted = mutableListOf<String>()
        var queryCount = 0

        override fun find(
            labels: Map<String, String>,
            absoluteDeadlineNanos: Long,
        ): List<HighContentionDockerObject> {
            queryCount += 1
            return discoveries.removeFirstOrNull() ?: emptyList()
        }

        override fun delete(
            resource: HighContentionDockerObject,
            absoluteDeadlineNanos: Long,
        ): Boolean {
            deleted += resource.id
            return true
        }
    }

    private class SequencedDockerCommandExecutor(
        private val results: ArrayDeque<HighContentionDockerCommandResult>,
    ) : HighContentionDockerCommandExecutor {
        val commands = mutableListOf<List<String>>()

        override fun execute(
            arguments: List<String>,
            timeoutMillis: Long,
        ): HighContentionDockerCommandResult {
            commands += arguments
            return results.removeFirst()
        }
    }
}
