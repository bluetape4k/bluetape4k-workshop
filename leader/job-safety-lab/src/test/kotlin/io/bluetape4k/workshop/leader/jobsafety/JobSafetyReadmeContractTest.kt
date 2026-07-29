package io.bluetape4k.workshop.leader.jobsafety

import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

internal class JobSafetyReadmeContractTest {
    private val moduleRoot = locateModuleRoot()

    @Test
    fun `both readmes explain all scenarios guarantees and operating boundaries`() {
        listOf("README.md", "README.ko.md").forEach { name ->
            val text = Files.readString(moduleRoot.resolve(name))

            SCENARIOS.all(text::contains).shouldBeTrue()
            GUARANTEES.all(text::contains).shouldBeTrue()
            REQUIRED_TERMS.all(text::contains).shouldBeTrue()
        }
    }

    @Test
    fun `both readmes expose all canonical diagrams`() {
        listOf("README.md", "README.ko.md").forEach { name ->
            val text = Files.readString(moduleRoot.resolve(name))
            DIAGRAMS.all(text::contains).shouldBeTrue()
        }
    }

    private fun locateModuleRoot(): Path {
        val current = Path.of("").toAbsolutePath()
        if (current.fileName.toString() == "job-safety-lab") return current
        return current.resolve("leader/job-safety-lab")
    }

    companion object {
        private val SCENARIOS =
            listOf(
                "CROSS_JOB_COLLISION",
                "LEASE_OVERRUN",
                "DYNAMIC_TENANT",
                "REGION_PARTITION",
                "MIXED_VERSION_ROLLOUT",
                "NON_FENCEABLE_EFFECT",
            )
        private val GUARANTEES =
            listOf("mutual exclusion", "failover", "replay safety", "fencing", "durable completion")
        private val REQUIRED_TERMS =
            listOf("Java 25", "Spring Boot", "ExposedJdbcRepository", "PostgreSQL", "Redis", "operator")
        private val DIAGRAMS =
            listOf(
                "leader-job-safety-lab-architecture-01.png",
                "leader-job-safety-lab-state-01.png",
                "leader-job-safety-lab-takeover-sequence-01.png",
                "leader-job-safety-lab-microservices-01.png",
            )
    }
}
