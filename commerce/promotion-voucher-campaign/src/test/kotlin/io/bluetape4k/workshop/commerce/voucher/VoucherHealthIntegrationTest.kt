package io.bluetape4k.workshop.commerce.voucher

import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.test.web.reactive.server.expectBody

internal class VoucherHealthIntegrationTest: AbstractVoucherIntegrationTest() {

    companion object: KLogging()

    @LocalManagementPort
    private var managementPort: Int = 0

    private val management by lazy { testClient(managementPort) }

    @Test
    fun `management is isolated and exposes health probes plus Prometheus only`() {

        webTestClient.get()
            .uri("/actuator/health")
            .exchange()
            .expectStatus().isNotFound

        management.get()
            .uri("/actuator/health")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.components.voucherDatabase.status").isEqualTo("UP")

        management.get()
            .uri("/actuator/health/readiness")
            .exchange()
            .expectStatus().isOk

        management.get()
            .uri("/actuator/health/liveness")
            .exchange()
            .expectStatus().isOk

        management.get()
            .uri("/actuator/prometheus")
            .exchange()
            .expectStatus().isOk
            .expectBody<String>()
            .value { body ->
                check(body != null && "voucher_sse_active" in body)
            }

        listOf(
            "env",
            "configprops",
            "heapdump",
            "threaddump"
        ).forEach { endpoint ->
            management.get()
                .uri("/actuator/$endpoint")
                .exchange()
                .expectStatus().isNotFound
        }
    }
}
