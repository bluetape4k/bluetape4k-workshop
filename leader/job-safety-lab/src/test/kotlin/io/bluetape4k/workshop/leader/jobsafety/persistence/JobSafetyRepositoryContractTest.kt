package io.bluetape4k.workshop.leader.jobsafety.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import io.bluetape4k.workshop.leader.jobsafety.domain.ExecutionContractVersion
import io.bluetape4k.workshop.leader.jobsafety.domain.MembershipRevision
import io.bluetape4k.workshop.leader.jobsafety.domain.NamespaceEpoch
import io.bluetape4k.workshop.leader.jobsafety.domain.RegionEpoch
import io.bluetape4k.workshop.leader.jobsafety.domain.RegionId
import io.bluetape4k.workshop.leader.jobsafety.domain.TenantId
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

internal class JobSafetyRepositoryContractTest {
    @Test
    fun `all concrete repositories implement bluetape ExposedJdbcRepository`() {
        val repositoryTypes =
            listOf(
                JobAssignmentRepository::class.java,
                JobRolloutMarkerRepository::class.java,
                JobResourceRepository::class.java,
                JobExecutionRepository::class.java,
                JobCheckpointRepository::class.java,
                JobOutboxRepository::class.java,
                JobEffectReceiptRepository::class.java,
            )

        repositoryTypes.all(ExposedJdbcRepository::class.java::isAssignableFrom).shouldBeTrue()
    }

    @Test
    fun `fixture seeds current authority through Exposed`() {
        JobSafetyDatabaseFixture().use { fixture ->
            val repositories = JobSafetyRepositories(fixture.executor)

            fixture.seedAuthority(authority())

            val assignment = repositories.assignment.findByTenant(TenantId("tenant-a"))
            assignment?.membershipRevision shouldBeEqualTo MembershipRevision(7L)
            assignment?.regionId shouldBeEqualTo RegionId("region-a")
            repositories.rollout.current()?.minimumWriterVersion shouldBeEqualTo ExecutionContractVersion(2)
        }
    }

    @Test
    fun `module contains no raw database access escape hatch`() {
        val moduleRoot = locateModuleRoot()
        val violations = Files.walk(moduleRoot).use { paths ->
            paths.filter(Files::isRegularFile)
                .filter { it.toString().endsWith(".kt") }
                .filter { it.fileName.toString() != "JobSafetyRepositoryContractTest.kt" }
                .filter { path -> RAW_DATABASE_MARKERS.any(Files.readString(path)::contains) }
                .toList()
        }

        violations.isEmpty().shouldBeTrue()
        Files.exists(moduleRoot.resolve("src/main/resources/db/" + "migration")).shouldBeFalse()
    }

    private fun authority(): JobAuthoritySeed =
        JobAuthoritySeed(
            tenantId = TenantId("tenant-a"),
            membershipRevision = MembershipRevision(7L),
            regionId = RegionId("region-a"),
            regionEpoch = RegionEpoch(3L),
            namespaceEpoch = NamespaceEpoch(2L),
            minimumWriterVersion = ExecutionContractVersion(2),
            checkpointSchemaVersion = 1,
        )

    private fun locateModuleRoot(): Path {
        val current = Path.of("").toAbsolutePath()
        if (current.fileName.toString() == "job-safety-lab") return current
        return current.resolve("leader/job-safety-lab")
    }

    companion object {
        private val RAW_DATABASE_MARKERS =
            listOf(
                "Jdbc" + "Template",
                "Prepared" + "Statement",
                ".prepareStatement(",
                ".create" + "Statement(",
                "dataSource.connection",
                "DriverManager.getConnection(",
                "Transaction." + "exec",
                ".exec(\"",
            )
    }
}
