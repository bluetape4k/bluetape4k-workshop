package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ActiveProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.support.EventSourcedPostgresTestDatabase
import org.awaitility.Awaitility.await
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

@Tag("integration")
internal class ProjectionRuntimeRestartIntegrationTest {
    private val postgres = PostgreSQLServer.Launcher.postgres
    private val repository = ProjectionRebuildRepository()

    @Test
    fun `fresh datasource and Exposed registration recover durable cancellation after runtime restart`() {
        val candidateKey = prepareCancellingCandidate()
        val restarted =
            EventSourcedPostgresTestDatabase(
                postgres = postgres,
                poolName = "issue-538-rebuild-restarted",
                maximumPoolSize = 1,
            )

        try {
            await().atMost(RECOVERY_TIMEOUT).untilAsserted {
                val recovered =
                    transaction(restarted.database) {
                        ProjectionRebuildMaintenance().recover(candidateKey, RESTARTED_AT)
                    }

                recovered.shouldNotBeNull().state shouldBeEqualTo ProjectionGenerationState.CANCELLED
                recovered.fencingToken shouldBeEqualTo FIRST_REBUILD_FENCING_TOKEN + 1
            }
        } finally {
            transaction(restarted.database) {
                SchemaUtils.drop(ActiveProjectionGenerations, ProjectionGenerations)
            }
            restarted.close()
        }
    }

    private fun prepareCancellingCandidate(): ProjectionKey {
        val initial =
            EventSourcedPostgresTestDatabase(
                postgres = postgres,
                poolName = "issue-538-rebuild-initial",
                maximumPoolSize = 1,
            )
        return try {
            transaction(initial.database) {
                SchemaUtils.create(ProjectionGenerations, ActiveProjectionGenerations)
                repository.initializeActive(PROJECTION, STARTED_AT)
                val candidate = repository.start(PROJECTION, TARGET_POSITION, STARTED_AT)
                repository.requestCancellation(candidate.key, STARTED_AT).shouldNotBeNull()
                candidate.key
            }
        } finally {
            initial.close()
        }
    }

    private companion object {
        private const val PROJECTION = "voucher-lifecycle-runtime-restart"
        private const val TARGET_POSITION = 2L
        private val STARTED_AT: Instant = Instant.parse("2026-07-23T13:00:00Z")
        private val RESTARTED_AT: Instant = STARTED_AT.plusSeconds(1)
        private val RECOVERY_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
