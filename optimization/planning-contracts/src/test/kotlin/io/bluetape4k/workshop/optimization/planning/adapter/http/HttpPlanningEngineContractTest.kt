package io.bluetape4k.workshop.optimization.planning.adapter.http

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.testcontainers.http.WireMockServer
import io.bluetape4k.workshop.optimization.planning.domain.AggregateId
import io.bluetape4k.workshop.optimization.planning.domain.AggregateVersion
import io.bluetape4k.workshop.optimization.planning.domain.DatasetId
import io.bluetape4k.workshop.optimization.planning.domain.PlanningStatus
import io.bluetape4k.workshop.optimization.planning.domain.PlanningSubmission
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

internal class HttpPlanningEngineContractTest {

    private val wireMock = WireMockServer.Launcher.wireMock

    @BeforeEach
    fun resetWireMock() {
        wireMock.resetAll()
    }

    @Test
    fun `timefold and custom solver map provider responses to one contract`() {
        listOf(
            TimefoldPlatformPlanningEngine(wireMock.baseUrl, jacksonObjectMapper()),
            CustomSolverPlanningEngine(wireMock.baseUrl, jacksonObjectMapper()),
        ).forEach { engine ->
            engine.use {
                wireMock.stubFor(
                    post(urlEqualTo(engine.submitPath))
                        .withHeader("Content-Type", containing("application/json"))
                        .willReturn(
                            aResponse()
                                .withStatus(202)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"providerRequestId\":\"provider-42\",\"status\":\"SUBMITTED\"}"),
                        ),
                )

                val submitted = engine.submit(submission())
                submitted.providerRequestId.value shouldBeEqualTo "provider-42"
                submitted.status shouldBeEqualTo PlanningStatus.SUBMITTED
            }
            wireMock.resetAll()
        }
    }

    @Test
    fun `provider submit POST is not retried after server failure`() {
        TimefoldPlatformPlanningEngine(wireMock.baseUrl, jacksonObjectMapper()).use { engine ->
            wireMock.stubFor(
                post(urlEqualTo(engine.submitPath))
                    .inScenario("ambiguous-submit")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willSetStateTo("second-attempt")
                    .willReturn(aResponse().withStatus(503).withBody("unavailable")),
            )
            wireMock.stubFor(
                post(urlEqualTo(engine.submitPath))
                    .inScenario("ambiguous-submit")
                    .whenScenarioStateIs("second-attempt")
                    .willReturn(
                        aResponse()
                            .withStatus(202)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"providerRequestId\":\"retried\",\"status\":\"SUBMITTED\"}"),
                    ),
            )

            assertThrows(PlanningProviderException::class.java) {
                engine.submit(submission())
            }
        }
    }

    @Test
    fun `provider response is rejected before reading beyond the byte limit`() {
        TimefoldPlatformPlanningEngine(wireMock.baseUrl, jacksonObjectMapper()).use { engine ->
            wireMock.stubFor(
                post(urlEqualTo(engine.submitPath))
                    .willReturn(
                        aResponse()
                            .withStatus(202)
                            .withHeader("Content-Type", "application/json")
                            .withBody("x".repeat(65 * 1024)),
                    ),
            )

            assertThrows(PlanningProviderException::class.java) {
                engine.submit(submission())
            }
        }
    }

    private fun submission() = PlanningSubmission(
        requestId = UUID.fromString("019c6b9e-4dc0-7e73-9cf8-84ecfda3fd8b"),
        datasetId = DatasetId("dataset-42"),
        aggregate = AggregateVersion(AggregateId("roster-42"), 7),
        parentRevision = null,
    )
}
