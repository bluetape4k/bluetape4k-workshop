package io.bluetape4k.workshop.optimization.planning.web

import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.optimization.planning.PlanningContractsApplication
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningAggregateTable
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningAuditTable
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningCallbackInboxTable
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningOutboxTable
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningRequestTable
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper

@SpringBootTest(classes = [PlanningContractsApplication::class])
@AutoConfigureMockMvc
internal class PlanningControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
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
    }

    @Test
    fun `request callback query and command expose only the redacted contract`() {
        val createResult = mockMvc.perform(
            post("/api/planning/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"aggregateId":"roster-42","aggregateVersion":7,"datasetId":"dataset-42","provider":"FAKE"}""",
                ),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andReturn()
        val requestId = objectMapper.readTree(createResult.response.contentAsString)["id"].stringValue()

        mockMvc.perform(post("/api/planning/process"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.processed").value(1))

        mockMvc.perform(
            post("/api/planning/callbacks/fake")
                .header("X-Planning-Signature", "fake")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"eventId":"event-42","planningRequestId":"$requestId","providerRevision":2,"status":"SUCCEEDED","scoreSummary":"0hard/-2soft","constraintExplanations":["balanced workload"]}""",
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.decision").value("ACCEPTED"))

        val queryBody = mockMvc.perform(get("/api/planning/requests/$requestId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.acceptedRevision").value(2))
            .andExpect(jsonPath("$.scoreSummary").value("0hard/-2soft"))
            .andReturn()
            .response
            .contentAsString
        listOf("payload", "signature", "secret", "password", "jdbc:").forEach { forbidden ->
            check(!queryBody.contains(forbidden, ignoreCase = true)) { "response leaked $forbidden" }
        }

        mockMvc.perform(post("/api/planning/requests/$requestId/commands"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.acceptedRevision").value(2))
    }

    @Test
    fun `invalid request is rejected without internal details`() {
        mockMvc.perform(
            post("/api/planning/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"aggregateId":" ","aggregateVersion":-1,"datasetId":"dataset-42","provider":"FAKE"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value(400))
    }

    @Test
    fun `oversized callback body is rejected before parsing`() {
        mockMvc.perform(
            post("/api/planning/callbacks/fake")
                .header("X-Planning-Signature", "fake")
                .contentType(MediaType.APPLICATION_JSON)
                .content("x".repeat(256 * 1024 + 1)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("invalid request"))
    }

    companion object {
        private val postgres = PostgreSQLServer.Launcher.postgres

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { requireNotNull(postgres.username) }
            registry.add("spring.datasource.password") { requireNotNull(postgres.password) }
        }
    }
}
