package io.bluetape4k.workshop.optimization.planning.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.optimization.planning.PlanningContractsApplication
import io.bluetape4k.workshop.optimization.planning.adapter.http.CallbackSignatureVerifier
import io.bluetape4k.workshop.optimization.planning.domain.PlanningProvider
import io.bluetape4k.workshop.optimization.planning.domain.PlanningStatus
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningAggregateRepository
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningAggregateTable
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningAuditRepository
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningAuditTable
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningCallbackInboxRepository
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningCallbackInboxTable
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningOutboxTable
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningRequestTable
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningRequestRepository
import io.bluetape4k.workshop.optimization.planning.observability.PlanningObservations
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.UUID

@SpringBootTest(
    classes = [PlanningContractsApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
internal class PlanningCallbackServiceTest @Autowired constructor(
    private val requestService: PlanningRequestService,
    private val callbackService: PlanningCallbackService,
    private val commandService: PlanningCommandService,
    private val aggregateRepository: PlanningAggregateRepository,
    private val inboxRepository: PlanningCallbackInboxRepository,
    private val auditRepository: PlanningAuditRepository,
    private val signatureVerifier: CallbackSignatureVerifier,
    private val requestRepository: PlanningRequestRepository,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
    private val observations: PlanningObservations,
) {

    @BeforeEach
    fun resetSchema() {
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
        requestService.create(createRequest(), REQUEST_ID)
    }

    @Test
    fun `duplicate callback creates one accepted audit`() {
        callbackService.handle(callback("event-42", 2), RAW_BODY, "fake") shouldBeEqualTo
            PlanningCallbackDecision.ACCEPTED
        callbackService.handle(callback("event-42", 2), RAW_BODY, "fake") shouldBeEqualTo
            PlanningCallbackDecision.DUPLICATE

        transaction {
            inboxRepository.count() shouldBeEqualTo 1L
            auditRepository.count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `out of order callback is audited but cannot replace accepted revision`() {
        callbackService.handle(callback("event-42", 2), RAW_BODY, "fake") shouldBeEqualTo
            PlanningCallbackDecision.ACCEPTED
        callbackService.handle(callback("event-43", 1), RAW_BODY, "fake") shouldBeEqualTo
            PlanningCallbackDecision.STALE_REVISION

        transaction { auditRepository.count() } shouldBeEqualTo 2L
    }

    @Test
    fun `invalid signature changes no callback state`() {
        assertThrows(InvalidCallbackSignatureException::class.java) {
            callbackService.handle(callback("event-42", 2), RAW_BODY, "invalid")
        }

        transaction {
            inboxRepository.count() shouldBeEqualTo 0L
            auditRepository.count() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `callback rejects an oversized constraint explanation`() {
        assertThrows(IllegalArgumentException::class.java) {
            callback("event-42", 2).copy(constraintExplanations = listOf("x".repeat(241)))
        }
    }

    @Test
    fun `aggregate version change rejects callback and final command`() {
        transaction { aggregateRepository.updateVersion("roster-42", 8) shouldBeEqualTo true }

        callbackService.handle(callback("event-42", 2), RAW_BODY, "fake") shouldBeEqualTo
            PlanningCallbackDecision.AGGREGATE_CHANGED
        commandService.createCandidate(REQUEST_ID) shouldBeEqualTo
            PlanningCommandResult.Conflict("aggregate version changed")
    }

    @Test
    fun `callback from a different provider is audited without changing accepted state`() {
        val permissiveVerifier = CallbackSignatureVerifier { _, _, _ -> true }
        transactionTemplate.execute {
            freshService(permissiveVerifier).handle(
                callback("event-wrong-provider", 2).copy(provider = PlanningProvider.CUSTOM_SOLVER),
                RAW_BODY,
                "valid-provider-signature",
            )
        } shouldBeEqualTo PlanningCallbackDecision.PROVIDER_MISMATCH

        commandService.createCandidate(REQUEST_ID) shouldBeEqualTo
            PlanningCommandResult.Conflict("planning result is not accepted")
        transaction { auditRepository.count() } shouldBeEqualTo 1L
    }

    @Test
    fun `accepted callback produces a version-checked command candidate`() {
        callbackService.handle(callback("event-42", 2), RAW_BODY, "fake")

        commandService.createCandidate(REQUEST_ID) shouldBeEqualTo
            PlanningCommandResult.Ready(
                requestId = REQUEST_ID,
                aggregateId = "roster-42",
                aggregateVersion = 7,
                acceptedRevision = 2,
            )
    }

    @Test
    fun `reconstructed service converges duplicate delivery after restart`() {
        val beforeRestart = freshService()
        val afterRestart = freshService()

        transactionTemplate.execute {
            beforeRestart.handle(callback("event-restart", 2), RAW_BODY, "fake")
        } shouldBeEqualTo PlanningCallbackDecision.ACCEPTED
        transactionTemplate.execute {
            afterRestart.handle(callback("event-restart", 2), RAW_BODY, "fake")
        } shouldBeEqualTo PlanningCallbackDecision.DUPLICATE

        transaction {
            inboxRepository.count() shouldBeEqualTo 1L
            auditRepository.count() shouldBeEqualTo 1L
        }
    }

    private fun freshService(
        verifier: CallbackSignatureVerifier = signatureVerifier,
    ) = PlanningCallbackService(
        signatureVerifier = verifier,
        requestRepository = requestRepository,
        aggregateRepository = aggregateRepository,
        inboxRepository = inboxRepository,
        auditRepository = auditRepository,
        clock = clock,
        observations = observations,
    )

    private fun createRequest() = CreatePlanningRequest(
        aggregateId = "roster-42",
        aggregateVersion = 7,
        datasetId = "dataset-42",
        parentRevision = null,
        provider = PlanningProvider.FAKE,
    )

    private fun callback(eventId: String, revision: Long) = PlanningCallback(
        provider = PlanningProvider.FAKE,
        eventId = eventId,
        planningRequestId = REQUEST_ID,
        providerRevision = revision,
        status = PlanningStatus.SUCCEEDED,
        scoreSummary = "0hard/-2soft",
        constraintExplanations = listOf("balanced workload"),
    )

    companion object {
        private val postgres = PostgreSQLServer.Launcher.postgres
        private val REQUEST_ID = UUID.fromString("019c6b9e-4dc0-7e73-9cf8-84ecfda3fd8b")
        private val RAW_BODY = "{\"eventId\":\"event-42\"}".toByteArray(StandardCharsets.UTF_8)

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { requireNotNull(postgres.username) }
            registry.add("spring.datasource.password") { requireNotNull(postgres.password) }
        }
    }
}
