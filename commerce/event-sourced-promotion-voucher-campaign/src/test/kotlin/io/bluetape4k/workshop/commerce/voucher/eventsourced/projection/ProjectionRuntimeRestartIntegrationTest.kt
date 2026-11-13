package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ActiveProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.support.EventSourcedPostgresTestDatabase
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.awaitility.kotlin.withPollInterval
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class ProjectionRuntimeRestartIntegrationTest {

    private companion object: KLogging() {
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }

        private const val PROJECTION = "voucher-lifecycle-runtime-restart"
        private const val TARGET_POSITION = 2L
        private val STARTED_AT: Instant = Instant.parse("2026-07-23T13:00:00Z")
        private val RESTARTED_AT: Instant = STARTED_AT + 1.seconds()
        private val RECOVERY_TIMEOUT: kotlin.time.Duration = 5.seconds
        private val RECOVERY_INTERVAL: kotlin.time.Duration = 100.milliseconds
    }


    private val repository = ProjectionRebuildRepository()

    @Test
    fun `fresh datasource and Exposed registration recover durable cancellation after runtime restart`() {
        val candidateKey = prepareCancellingCandidate()
        val restarted = EventSourcedPostgresTestDatabase(
            postgres = postgres,
            poolName = "issue-538-rebuild-restarted",
            maximumPoolSize = 1,
        )

        try {
            await atMost RECOVERY_TIMEOUT withPollInterval RECOVERY_INTERVAL untilAsserted {
                val recovered = transaction(restarted.database) {
                    ProjectionRebuildMaintenance().recover(candidateKey, RESTARTED_AT)
                }.shouldNotBeNull()

                log.debug { "recovered: $recovered" }
                recovered.state shouldBeEqualTo ProjectionGenerationState.CANCELLED
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
        val initial = EventSourcedPostgresTestDatabase(
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
}
