package io.bluetape4k.workshop.optimization.planning.adapter.http

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.testcontainers.http.WireMockServer
import io.bluetape4k.workshop.optimization.planning.domain.AggregateId
import io.bluetape4k.workshop.optimization.planning.domain.AggregateVersion
import io.bluetape4k.workshop.optimization.planning.domain.DatasetId
import io.bluetape4k.workshop.optimization.planning.domain.PlanningStatus
import io.bluetape4k.workshop.optimization.planning.domain.PlanningSubmission
import io.bluetape4k.workshop.optimization.planning.domain.ProviderRequestId
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

            assertFailsWith<PlanningProviderException> {
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

            assertFailsWith<PlanningProviderException> {
                engine.submit(submission())
            }
        }
    }

    @Test
    fun `response exactly at the byte limit is accepted for known length and chunked bodies`() {
        val prefix = "{\"providerRequestId\":\"provider-42\",\"status\":\"SUBMITTED\"}"
        val responseBody = prefix + " ".repeat(MAX_RESPONSE_BYTES - prefix.toByteArray().size)

        listOf(
            aResponse()
                .withStatus(202)
                .withHeader("Content-Type", "application/json")
                .withBody(responseBody),
            aResponse()
                .withStatus(202)
                .withHeader("Content-Type", "application/json")
                .withChunkedDribbleDelay(2, 1)
                .withBody(responseBody),
        ).forEach { response ->
            TimefoldPlatformPlanningEngine(wireMock.baseUrl, jacksonObjectMapper()).use { engine ->
                wireMock.stubFor(post(urlEqualTo(engine.submitPath)).willReturn(response))

                engine.submit(submission()).providerRequestId.value shouldBeEqualTo "provider-42"
                wireMock.resetAll()
            }
        }
    }

    @Test
    fun `multibyte response is rejected by byte limit without exposing the body`() {
        TimefoldPlatformPlanningEngine(wireMock.baseUrl, jacksonObjectMapper()).use { engine ->
            wireMock.stubFor(
                post(urlEqualTo(engine.submitPath))
                    .willReturn(
                        aResponse()
                            .withStatus(202)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"providerRequestId\":\"${"가".repeat(MAX_RESPONSE_BYTES)}\"}"),
                    ),
            )

            val failure = assertFailsWith<PlanningProviderException> {
                engine.submit(submission())
            }
            failure.message shouldBeEqualTo "provider response exceeded the configured limit"
            failure.message.orEmpty().contains("가").shouldBeEqualTo(false)
        }
    }

    @Test
    fun `status preserves 404 null and bounded cleanup while decoding successful responses`() {
        TimefoldPlatformPlanningEngine(wireMock.baseUrl, jacksonObjectMapper()).use { engine ->
            val providerRequestId = ProviderRequestId("provider-42")
            wireMock.stubFor(get(urlEqualTo(engine.statusPathForTest()))
                .willReturn(aResponse().withStatus(404)))

            engine.status(providerRequestId) shouldBeEqualTo null
            wireMock.resetAll()

            wireMock.stubFor(
                get(urlEqualTo(engine.statusPathForTest()))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(
                                """
                                {"requestId":"019c6b9e-4dc0-7e73-9cf8-84ecfda3fd8b","revision":7,"status":"SUCCEEDED","scoreSummary":"score","constraintExplanations":["one","two"]}
                                """.trimIndent(),
                            ),
                    ),
            )
            val result = engine.status(providerRequestId)
            result?.providerRequestId?.value shouldBeEqualTo "provider-42"
            result?.constraintExplanations shouldBeEqualTo listOf("one", "two")
        }
    }

    private fun submission() = PlanningSubmission(
        requestId = UUID.fromString("019c6b9e-4dc0-7e73-9cf8-84ecfda3fd8b"),
        datasetId = DatasetId("dataset-42"),
        aggregate = AggregateVersion(AggregateId("roster-42"), 7),
        parentRevision = null,
    )

    private fun HttpPlanningEngine.statusPathForTest(): String = "/api/models/planning/jobs/provider-42"

    companion object {
        private const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}
