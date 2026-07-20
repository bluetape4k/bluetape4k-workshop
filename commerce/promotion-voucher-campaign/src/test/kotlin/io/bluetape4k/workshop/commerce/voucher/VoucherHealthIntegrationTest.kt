package io.bluetape4k.workshop.commerce.voucher

import org.junit.jupiter.api.Test
import org.springframework.boot.test.web.server.LocalManagementPort

internal class VoucherHealthIntegrationTest : AbstractVoucherIntegrationTest() {
    @LocalManagementPort
    private var managementPort: Int = 0

    @Test
    fun `management is isolated and exposes health probes plus Prometheus only`() {
        webTestClient.get().uri("/actuator/health").exchange().expectStatus().isNotFound

        management.get().uri("/actuator/health").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.components.voucherDatabase.status").isEqualTo("UP")
        management.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk
        management.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk
        management.get().uri("/actuator/prometheus").exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .value { body -> check(body != null && "voucher_sse_active" in body) }
        listOf("env", "configprops", "heapdump", "threaddump").forEach { endpoint ->
            management.get().uri("/actuator/$endpoint").exchange().expectStatus().isNotFound
        }
    }

    private val management by lazy { testClient(managementPort) }
}
