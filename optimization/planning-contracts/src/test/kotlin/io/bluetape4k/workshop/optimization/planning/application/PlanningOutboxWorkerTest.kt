package io.bluetape4k.workshop.optimization.planning.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.optimization.planning.PlanningContractsApplication
import io.bluetape4k.workshop.optimization.planning.domain.PlanningEngine
import io.bluetape4k.workshop.optimization.planning.domain.PlanningProvider
import io.bluetape4k.workshop.optimization.planning.domain.PlanningResult
import io.bluetape4k.workshop.optimization.planning.domain.PlanningStatus
import io.bluetape4k.workshop.optimization.planning.domain.PlanningSubmission
import io.bluetape4k.workshop.optimization.planning.domain.PlanningSubmissionResult
import io.bluetape4k.workshop.optimization.planning.domain.ProviderRequestId
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningAggregateTable
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningAuditTable
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningCallbackInboxTable
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningOutboxRepository
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningOutboxStatus
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningOutboxTable
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningRequestRepository
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningRequestTable
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@SpringBootTest(
    classes = [PlanningContractsApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@Import(PlanningOutboxWorkerTestConfiguration::class)
internal class PlanningOutboxWorkerTest @Autowired constructor(
    private val requestService: PlanningRequestService,
    private val worker: PlanningOutboxWorker,
    private val engine: RecordingPlanningEngine,
    private val requestRepository: PlanningRequestRepository,
    private val outboxRepository: PlanningOutboxRepository,
) {

    @BeforeEach
    fun resetSchema() {
        engine.reset()
        transaction {
            SchemaUtils.drop(
                PlanningAuditTable,
                PlanningCallbackInboxTable,
                PlanningOutboxTable,
                PlanningRequestTable,
                PlanningAggregateTable,
            )
            SchemaUtils.create(
                PlanningAggregateTable,
                PlanningRequestTable,
                PlanningOutboxTable,
                PlanningCallbackInboxTable,
                PlanningAuditTable,
            )
        }
    }

    @Test
    fun `due request is submitted on a bluetape Java 25 virtual thread`() {
        requestService.create(
            CreatePlanningRequest(
                aggregateId = "roster-42",
                aggregateVersion = 7,
                datasetId = "dataset-42",
                parentRevision = null,
                provider = PlanningProvider.FAKE,
            ),
            REQUEST_ID,
        )

        worker.processDue().single().get()

        engine.executedOnVirtualThread.get().shouldBeTrue()
        transaction {
            requestRepository.findById(REQUEST_ID).status shouldBeEqualTo PlanningStatus.SUBMITTED
            outboxRepository.findByRequestId(REQUEST_ID)!!.status shouldBeEqualTo PlanningOutboxStatus.COMPLETED
        }
    }

    companion object {
        private val postgres = PostgreSQLServer.Launcher.postgres
        private val REQUEST_ID = UUID.fromString("019c6b9e-4dc0-7e73-9cf8-84ecfda3fd8b")

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { requireNotNull(postgres.username) }
            registry.add("spring.datasource.password") { requireNotNull(postgres.password) }
        }
    }
}

internal class RecordingPlanningEngine: PlanningEngine {
    override val provider: PlanningProvider = PlanningProvider.FAKE
    val executedOnVirtualThread = AtomicBoolean()

    override fun submit(request: PlanningSubmission): PlanningSubmissionResult {
        executedOnVirtualThread.set(Thread.currentThread().isVirtual)
        return PlanningSubmissionResult(
            providerRequestId = ProviderRequestId("recording-${request.requestId}"),
            status = PlanningStatus.SUBMITTED,
        )
    }

    override fun status(providerRequestId: ProviderRequestId): PlanningResult? = null

    fun reset() {
        executedOnVirtualThread.set(false)
    }
}

internal class PlanningOutboxWorkerTestConfiguration {
    @Bean
    @Primary
    fun recordingPlanningEngine(): RecordingPlanningEngine = RecordingPlanningEngine()
}
