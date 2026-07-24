import groovy.json.JsonOutput
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HighContentionProfileTasksTest {

    @Test
    fun `registration derives the implementation mode class and trusted roots`() {
        val root = ProjectBuilder.builder().withName("root").build()
        val project = ProjectBuilder.builder()
            .withName("operations-job-console-core")
            .withParent(root)
            .build()
        Files.createDirectories(project.projectDir.toPath())
        val contractRoot = root.projectDir.toPath().resolve("profiles/high-contention/v1")
        Files.createDirectories(contractRoot)
        contractRoot.resolve("suite-manifest.json").writeText("{}")
        project.pluginManager.apply("java")

        val task = project.registerHighContentionProfileTask("highContentionCiProfile").get()

        assertEquals(1, task.maxParallelForks)
        assertEquals("false", task.systemProperties["junit.jupiter.execution.parallel.enabled"])
        assertTrue(task.inputs.files.files.any { it.path.contains("profiles/high-contention/v1") })
        assertTrue(task.filter.includePatterns.contains(JOB_CORE_PROFILE_CLASS))
        assertTrue(task.javaLauncher.isPresent)
        assertEquals(25, task.javaLauncher.get().metadata.languageVersion.asInt())
        assertEquals("verification", task.group)
    }

    @Test
    fun `task identity is fixed from project path and task name`() {
        assertEquals(
            HighContentionTaskIdentity(
                mode = "ci-correctness",
                implementation = "job-spring",
                profileClass = SPRING_PROFILE_CLASS,
            ),
            HighContentionTaskIdentity.derive(
                projectPath = ":operations-job-console-spring",
                taskName = "highContentionCiProfile",
            ),
        )
        assertEquals(
            HighContentionTaskIdentity(
                mode = "local-reference",
                implementation = "ticket-spring",
                profileClass = TICKET_PROFILE_CLASS,
            ),
            HighContentionTaskIdentity.derive(
                projectPath = ":commerce-concert-ticket-flash-sale",
                taskName = "highContentionLocalReferenceProfile",
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            HighContentionTaskIdentity.derive(":unknown", "highContentionCiProfile")
        }
        assertFailsWith<IllegalArgumentException> {
            HighContentionTaskIdentity.derive(
                ":operations-job-console-core",
                "callerSelectedMode",
            )
        }
    }

    @Test
    fun `selection accepts only bounded run id and manifest allowlisted profile`() {
        val allowedProfiles = setOf("burst", "worker-restart")

        assertFailsWith<IllegalArgumentException> {
            HighContentionProfileSelection.validate(
                runId = "../../unsafe",
                profileId = "burst",
                allowedProfiles = allowedProfiles,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            HighContentionProfileSelection.validate(
                runId = "run-1",
                profileId = "unknown",
                allowedProfiles = allowedProfiles,
            )
        }

        val selection = HighContentionProfileSelection.validate(
            runId = "run-1",
            profileId = "worker-restart",
            allowedProfiles = allowedProfiles,
        )
        assertEquals("run-1", selection.runId)
        assertEquals("worker-restart", selection.profileId)
        assertFalse(selection.runId.contains('/'))
    }

    @Test
    fun `caller spoof detection rejects reserved property and environment channels`() {
        assertFailsWith<GradleException> {
            HighContentionCallerBoundary.validate(
                projectPropertyNames = setOf("highContentionRunId", "highContentionMode"),
                systemPropertyNames = emptySet(),
                environmentNames = emptySet(),
            )
        }
        assertFailsWith<GradleException> {
            HighContentionCallerBoundary.validate(
                projectPropertyNames = setOf("highContentionRunId"),
                systemPropertyNames = setOf("high.contention.output.root"),
                environmentNames = emptySet(),
            )
        }
        assertFailsWith<GradleException> {
            HighContentionCallerBoundary.validate(
                projectPropertyNames = setOf("highContentionRunId"),
                systemPropertyNames = emptySet(),
                environmentNames = setOf("HIGH_CONTENTION_PARENT_MODE"),
            )
        }

        HighContentionCallerBoundary.validate(
            projectPropertyNames = setOf("highContentionRunId", "highContentionProfileId"),
            systemPropertyNames = emptySet(),
            environmentNames = emptySet(),
        )
    }

    @Test
    fun `parent child descriptor verifies tuple manifest digest labels and absent targets`(
        @TempDir outputRoot: Path,
    ) {
        val capability = "test-capability-that-is-at-least-thirty-two-bytes"
        val liveParent = ProcessHandle.current().parent().orElseThrow()
        val runRoot = outputRoot.resolve("run-1")
        val descriptorRoot = runRoot.resolve("internal/children")
        Files.createDirectories(descriptorRoot)
        val parentManifest = runRoot.resolve("run-manifest.json")
        parentManifest.writeText("""{"schemaVersion":1}""")
        val fields = linkedMapOf<String, Any?>(
            "childDescriptorSchemaVersion" to 1,
            "runId" to "run-1",
            "profileId" to "burst",
            "mode" to "ci-correctness",
            "implementation" to "job-core",
            "parentManifestDigest" to sha256(Files.readAllBytes(parentManifest)),
            "parentPid" to liveParent.pid(),
            "parentStartEpochMillis" to liveParent.info().startInstant().orElseThrow().toEpochMilli(),
            "resourceLabels" to listOf(
                resourceLabels("run-1", "burst", "network", "network"),
                resourceLabels("run-1", "burst", "redis", "container"),
                resourceLabels("run-1", "burst", "toxiproxy", "container"),
            ),
        )
        fields["capabilityDigest"] =
            HighContentionChildDescriptorValidator.capabilityDigest(fields, capability)
        val descriptor = descriptorRoot.resolve("job-core-burst.json")
        descriptor.writeText(JsonOutput.toJson(fields))

        HighContentionChildDescriptorValidator.validate(
            descriptorValue = descriptor.toString(),
            outputRoot = outputRoot,
            runRoot = runRoot,
            selection = HighContentionProfileSelection("run-1", "burst"),
            identity = HighContentionTaskIdentity(
                mode = "ci-correctness",
                implementation = "job-core",
                profileClass = JOB_CORE_PROFILE_CLASS,
            ),
            capability = capability,
        )

        val journal = runRoot.resolve("children/job-core/burst/child-journal.jsonl")
        Files.createDirectories(journal.parent)
        journal.writeText("{}\n")
        assertFailsWith<GradleException> {
            HighContentionChildDescriptorValidator.validate(
                descriptorValue = descriptor.toString(),
                outputRoot = outputRoot,
                runRoot = runRoot,
                selection = HighContentionProfileSelection("run-1", "burst"),
                identity = HighContentionTaskIdentity(
                    mode = "ci-correctness",
                    implementation = "job-core",
                    profileClass = JOB_CORE_PROFILE_CLASS,
                ),
                capability = capability,
            )
        }
        Files.delete(journal)

        Files.createDirectories(runRoot.resolve("reports/job-core"))
        runRoot.resolve("reports/job-core/burst.json").writeText("{}")
        assertFailsWith<GradleException> {
            HighContentionChildDescriptorValidator.validate(
                descriptorValue = descriptor.toString(),
                outputRoot = outputRoot,
                runRoot = runRoot,
                selection = HighContentionProfileSelection("run-1", "burst"),
                identity = HighContentionTaskIdentity(
                    mode = "ci-correctness",
                    implementation = "job-core",
                    profileClass = JOB_CORE_PROFILE_CLASS,
                ),
                capability = capability,
            )
        }
    }

    @Test
    fun `caller-created child descriptor cannot impersonate the live coordinator`(
        @TempDir outputRoot: Path,
    ) {
        val capability = "caller-capability-that-is-at-least-thirty-two-bytes"
        val runRoot = outputRoot.resolve("run-1")
        val descriptorRoot = runRoot.resolve("internal/children")
        Files.createDirectories(descriptorRoot)
        val parentManifest = runRoot.resolve("run-manifest.json")
        parentManifest.writeText("""{"schemaVersion":1}""")
        val fields = linkedMapOf<String, Any?>(
            "childDescriptorSchemaVersion" to 1,
            "runId" to "run-1",
            "profileId" to "burst",
            "mode" to "ci-correctness",
            "implementation" to "job-core",
            "parentManifestDigest" to sha256(Files.readAllBytes(parentManifest)),
            "parentPid" to ProcessHandle.current().pid(),
            "parentStartEpochMillis" to ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli(),
            "resourceLabels" to listOf(
                resourceLabels("run-1", "burst", "network", "network"),
                resourceLabels("run-1", "burst", "redis", "container"),
                resourceLabels("run-1", "burst", "toxiproxy", "container"),
            ),
        )
        fields["capabilityDigest"] =
            HighContentionChildDescriptorValidator.capabilityDigest(fields, capability)
        val descriptor = descriptorRoot.resolve("job-core-burst.json")
        descriptor.writeText(JsonOutput.toJson(fields))

        assertFailsWith<GradleException> {
            HighContentionChildDescriptorValidator.validate(
                descriptorValue = descriptor.toString(),
                outputRoot = outputRoot,
                runRoot = runRoot,
                selection = HighContentionProfileSelection("run-1", "burst"),
                identity = HighContentionTaskIdentity(
                    mode = "ci-correctness",
                    implementation = "job-core",
                    profileClass = JOB_CORE_PROFILE_CLASS,
                ),
                capability = capability,
            )
        }
    }

    @Test
    fun `clean worktree preflight passes in a Gradle TestKit build`(@TempDir projectDir: Path) {
        writePreflightFixture(projectDir)
        git(projectDir, "init")
        git(projectDir, "config", "user.name", "Test User")
        git(projectDir, "config", "user.email", "test@example.com")
        git(projectDir, "add", ".")
        git(projectDir, "commit", "-m", "fixture")

        val result = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withArguments(
                "verifyClean",
                "--init-script",
                projectDir.resolve("preflight.init.gradle").toString(),
                "--stacktrace",
            )
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyClean")?.outcome)
    }

    @Test
    fun `dirty worktree preflight fails before creating a run directory`(@TempDir projectDir: Path) {
        writePreflightFixture(projectDir)
        git(projectDir, "init")
        git(projectDir, "config", "user.name", "Test User")
        git(projectDir, "config", "user.email", "test@example.com")
        git(projectDir, "add", ".")
        git(projectDir, "commit", "-m", "fixture")
        projectDir.resolve("dirty.txt").writeText("dirty")

        val result = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withArguments(
                "verifyClean",
                "--init-script",
                projectDir.resolve("preflight.init.gradle").toString(),
                "--stacktrace",
            )
            .buildAndFail()

        assertTrue(result.output.contains("high-contention source worktree must be clean"))
        assertFalse(Files.exists(projectDir.resolve("build/reports/high-contention/run-1")))
    }

    private fun writePreflightFixture(projectDir: Path) {
        projectDir.resolve("settings.gradle").writeText("rootProject.name = 'fixture'\n")
        projectDir.resolve("build.gradle").writeText("")
        projectDir.resolve(".gitignore").writeText(".gradle/\nbuild/\n")
        val implementationClasspath = HighContentionSourceState::class.java
            .protectionDomain
            .codeSource
            .location
            .toURI()
            .let(Path::of)
        projectDir.resolve("preflight.init.gradle").writeText(
            """
            initscript {
                dependencies {
                    classpath files('${implementationClasspath.toString().replace("\\", "\\\\")}')
                }
            }
            allprojects {
                tasks.register("verifyClean") {
                    doLast {
                        def sourceStateClass = Class.forName(
                            "HighContentionSourceState",
                            true,
                            this.class.classLoader,
                        )
                        def sourceState = sourceStateClass.getField("INSTANCE").get(null)
                        sourceStateClass.getMethod("requireClean", java.nio.file.Path)
                            .invoke(sourceState, rootDir.toPath())
                    }
                }
            }
            """.trimIndent(),
        )
    }

    private fun git(projectDir: Path, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", *arguments))
            .directory(projectDir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { output }
    }

    private fun resourceLabels(
        runId: String,
        profileId: String,
        resourceKey: String,
        resourceType: String,
    ): Map<String, Any> =
        linkedMapOf(
            "resourceKey" to resourceKey,
            "resourceType" to resourceType,
            "labels" to linkedMapOf(
                "io.bluetape4k.high-contention.run-id" to runId,
                "io.bluetape4k.high-contention.profile-id" to profileId,
                "io.bluetape4k.high-contention.resource-key" to resourceKey,
                "io.bluetape4k.high-contention.resource-type" to resourceType,
            ),
        )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val JOB_CORE_PROFILE_CLASS =
            "io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleCoreHighContentionProfileTest"
        const val SPRING_PROFILE_CLASS =
            "io.bluetape4k.workshop.operations.jobconsole.spring.SpringJobConsoleHighContentionProfileTest"
        const val TICKET_PROFILE_CLASS =
            "io.bluetape4k.workshop.commerce.ticket.highcontention.TicketHighContentionProfileTest"
    }
}
