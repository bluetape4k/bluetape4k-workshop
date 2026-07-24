import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HighContentionProfileTasksTest {

    @Test
    fun `registration fixes roots runtime isolation and task selection`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("java")

        val task = project.registerHighContentionProfileTask(
            name = "highContentionCiProfile",
            mode = "ci-correctness",
            implementation = "job-core",
        ).get()

        assertEquals(1, task.maxParallelForks)
        assertEquals("false", task.systemProperties["junit.jupiter.execution.parallel.enabled"])
        assertEquals("ci-correctness", task.systemProperties["highContentionMode"])
        assertEquals("job-core", task.systemProperties["highContentionImplementation"])
        assertEquals("25", task.systemProperties["highContentionExpectedJavaVersion"])
        assertTrue(
            task.systemProperties.getValue("highContentionContractRoot")
                .toString()
                .endsWith("profiles/high-contention/v1"),
        )
        assertTrue(task.outputs.files.files.any { it.path.endsWith("build/high-contention") })
        assertTrue(task.javaLauncher.isPresent)
        assertEquals(25, task.javaLauncher.get().metadata.languageVersion.asInt())
    }

    @Test
    fun `selection rejects spoofed reserved properties and unknown profiles`() {
        assertFailsWith<IllegalArgumentException> {
            HighContentionProfileSelection.validate(
                runId = "run-1",
                profileId = "unknown",
                reservedSystemProperties = emptySet(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            HighContentionProfileSelection.validate(
                runId = "run-1",
                profileId = "burst",
                reservedSystemProperties = setOf("highContentionRunId"),
            )
        }

        val selection = HighContentionProfileSelection.validate(
            runId = "run-1",
            profileId = "worker-restart",
            reservedSystemProperties = emptySet(),
        )
        assertEquals("run-1", selection.runId)
        assertEquals("worker-restart", selection.profileId)
        assertFalse(selection.runId.contains('/'))
    }

    @Test
    fun `registered task is a dedicated JUnit test task`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("java")

        val task = project.registerHighContentionProfileTask(
            name = "highContentionLocalReferenceProfile",
            mode = "local-reference",
            implementation = "job-core",
        ).get()

        assertEquals("verification", task.group)
    }
}
