package io.bluetape4k.workshop.optimization.planning.adapter.http

import io.bluetape4k.http.hc5.classic.productionVirtualThreadHttpClientOf
import io.bluetape4k.workshop.optimization.planning.domain.PlanningEngine
import io.bluetape4k.workshop.optimization.planning.domain.PlanningProvider
import io.bluetape4k.workshop.optimization.planning.domain.PlanningResult
import io.bluetape4k.workshop.optimization.planning.domain.PlanningRevision
import io.bluetape4k.workshop.optimization.planning.domain.PlanningStatus
import io.bluetape4k.workshop.optimization.planning.domain.PlanningSubmission
import io.bluetape4k.workshop.optimization.planning.domain.PlanningSubmissionResult
import io.bluetape4k.workshop.optimization.planning.domain.ProviderRequestId
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.classic.methods.HttpPost
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.HttpEntity
import org.apache.hc.core5.http.io.entity.EntityUtils
import org.apache.hc.core5.http.io.entity.StringEntity
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.util.UUID

internal class PlanningProviderException(message: String): RuntimeException(message)

internal abstract class HttpPlanningEngine(
    final override val provider: PlanningProvider,
    baseUrl: String,
    internal val submitPath: String,
    private val statusPath: String,
    private val objectMapper: ObjectMapper,
    private val client: CloseableHttpClient = productionVirtualThreadHttpClientOf(
        builder = { disableAutomaticRetries() },
    ),
): PlanningEngine, AutoCloseable {

    private val normalizedBaseUrl = baseUrl.trimEnd('/')

    init {
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
            "provider baseUrl must use HTTP or HTTPS"
        }
    }

    override fun submit(request: PlanningSubmission): PlanningSubmissionResult {
        val post = HttpPost(normalizedBaseUrl + submitPath).apply {
            entity = StringEntity(
                objectMapper.writeValueAsString(
                    mapOf(
                        "requestId" to request.requestId.toString(),
                        "datasetId" to request.datasetId.value,
                        "aggregateId" to request.aggregate.aggregateId.value,
                        "aggregateVersion" to request.aggregate.version,
                        "parentRevision" to request.parentRevision?.value,
                    ),
                ),
                ContentType.APPLICATION_JSON,
            )
        }
        return client.execute(post) { response ->
            val body = boundedBody(response.entity)
            if (response.code !in 200..299) {
                throw PlanningProviderException("provider submit failed with status ${response.code}")
            }
            val decoded = objectMapper.readValue(body, HttpSubmissionResponse::class.java)
            PlanningSubmissionResult(
                providerRequestId = ProviderRequestId(decoded.providerRequestId),
                status = PlanningStatus.valueOf(decoded.status),
            )
        }
    }

    override fun status(providerRequestId: ProviderRequestId): PlanningResult? {
        val get = HttpGet(
            normalizedBaseUrl + statusPath.replace("{providerRequestId}", providerRequestId.value),
        )
        return client.execute(get) { response ->
            if (response.code == 404) {
                EntityUtils.consume(response.entity)
                return@execute null
            }
            val body = boundedBody(response.entity)
            if (response.code !in 200..299) {
                throw PlanningProviderException("provider status failed with status ${response.code}")
            }
            val decoded = objectMapper.readValue(body, HttpPlanningResultResponse::class.java)
            PlanningResult(
                requestId = UUID.fromString(decoded.requestId),
                providerRequestId = providerRequestId,
                revision = PlanningRevision(decoded.revision),
                status = PlanningStatus.valueOf(decoded.status),
                scoreSummary = decoded.scoreSummary.take(160),
                constraintExplanations = decoded.constraintExplanations
                    .take(MAX_EXPLANATIONS)
                    .map { it.take(MAX_EXPLANATION_LENGTH) },
            )
        }
    }

    override fun close() {
        client.close()
    }

    private fun boundedBody(entity: HttpEntity?): String {
        if (entity == null) return ""
        if (entity.contentLength > MAX_RESPONSE_BYTES) {
            throw PlanningProviderException("provider response exceeded the configured limit")
        }
        val bytes = entity.content.use { content -> content.readNBytes(MAX_RESPONSE_BYTES + 1) }
        if (bytes.size > MAX_RESPONSE_BYTES) {
            throw PlanningProviderException("provider response exceeded the configured limit")
        }
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private data class HttpSubmissionResponse(
        val providerRequestId: String,
        val status: String,
    )

    private data class HttpPlanningResultResponse(
        val requestId: String,
        val revision: Long,
        val status: String,
        val scoreSummary: String,
        val constraintExplanations: List<String> = emptyList(),
    )

    companion object {
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private const val MAX_EXPLANATIONS = 20
        private const val MAX_EXPLANATION_LENGTH = 240
    }
}

internal class TimefoldPlatformPlanningEngine(
    baseUrl: String,
    objectMapper: ObjectMapper,
): HttpPlanningEngine(
    provider = PlanningProvider.TIMEFOLD_PLATFORM,
    baseUrl = baseUrl,
    submitPath = "/api/models/planning/jobs",
    statusPath = "/api/models/planning/jobs/{providerRequestId}",
    objectMapper = objectMapper,
)

internal class CustomSolverPlanningEngine(
    baseUrl: String,
    objectMapper: ObjectMapper,
): HttpPlanningEngine(
    provider = PlanningProvider.CUSTOM_SOLVER,
    baseUrl = baseUrl,
    submitPath = "/api/plans",
    statusPath = "/api/plans/{providerRequestId}",
    objectMapper = objectMapper,
)
