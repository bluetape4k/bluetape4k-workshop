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
 * downstream inventory service 를 호출하는 HTTP client 입니다.
 *
 * ## Behavior / Contract
 * - W3C traceparent header 는 주입된 `WebClient.Builder` 를 통해 자동 전파됩니다. 이 builder 는 Spring Boot OpenTelemetry auto-configuration 으로 구성됩니다.
 * - 404 를 포함한 4xx response 는 "not found" 로 취급하고 `null` 을 반환합니다.
 * - 5xx response 는 caller 에게 exception 으로 전파됩니다.
 * - 여기서는 manual Observation span 을 만들지 않습니다. `http.client.requests` span 은 Micrometer WebClient instrumentation 이 자동으로 생성합니다.
 */
@Component
class InventoryClient(
    builder: WebClient.Builder,
    @Value("\${workshop.observability.inventory.base-url}") private val baseUrl: String,
) {
    companion object : KLoggingChannel()

    private val client: WebClient = builder.baseUrl(baseUrl).build()

    /**
     * 주어진 [itemId] 의 inventory availability 를 조회합니다.
     *
     * item 을 찾지 못했거나(4xx) upstream 이 empty body 를 반환하면 `null` 을 반환합니다. 5xx server error 에서는 throw 합니다.
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
