package io.bluetape4k.workshop.observability.basic.client

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.observability.basic.model.Inventory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitExchangeOrNull
import org.springframework.web.reactive.function.client.awaitBodyOrNull
import org.springframework.web.reactive.function.client.createExceptionAndAwait

/**
 * HTTP client for the downstream inventory service.
 *
 * ## Behavior / Contract
 * - W3C traceparent header is propagated automatically via the injected `WebClient.Builder`
 *   (configured by Spring Boot's OpenTelemetry auto-configuration).
 * - 4xx responses (including 404) are treated as "not found" and return `null`.
 * - 5xx responses propagate as exceptions to the caller.
 * - No manual Observation span is created here; `http.client.requests` span is produced
 *   automatically by Micrometer's WebClient instrumentation.
 */
@Component
class InventoryClient(
    builder: WebClient.Builder,
    @Value("\${workshop.observability.inventory.base-url}") private val baseUrl: String,
) {
    companion object : KLoggingChannel()

    private val client: WebClient = builder.baseUrl(baseUrl).build()

    /**
     * Fetches inventory availability for the given [itemId].
     *
     * Returns `null` when the item is not found (4xx) or the upstream returns an empty body.
     * Throws on 5xx server errors.
     */
    suspend fun fetchInventory(itemId: Long): Inventory? {
        val validItemId = itemId.requirePositiveNumber("itemId")
        val result = client.get()
            .uri("/inventory/{id}", validItemId)
            .awaitExchangeOrNull { response ->
                when {
                    response.statusCode().is4xxClientError -> null
                    response.statusCode().is5xxServerError -> throw response.createExceptionAndAwait()
                    else -> response.awaitBodyOrNull<Inventory>()
                }
            }
        if (result == null) warn { "fetchInventory returned null for itemId=$validItemId" }
        return result
    }
}
