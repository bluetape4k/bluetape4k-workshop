package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.workshop.commerce.voucher.AbstractVoucherIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

internal class OperatorAccessFilterIntegrationTest : AbstractVoucherIntegrationTest() {
    @Test
    fun `operator route rejects ambient or incorrect credentials`() {
        webTestClient.post().uri("/operator/api/v1/reconciliation/run")
            .header(TENANT_HEADER, "tenant-a")
            .bodyValue(emptyMap<String, String>())
            .exchange().expectStatus().isForbidden
            .expectBody().jsonPath("$.code").isEqualTo("OPERATOR_ACCESS_DENIED")

        webTestClient.post().uri("/operator/api/v1/reconciliation/run")
            .header(TENANT_HEADER, "tenant-a")
            .header(OPERATOR_SECRET_HEADER, "wrong-secret-000000000000000000000000")
            .header(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)
            .header("Origin", "https://untrusted.example")
            .bodyValue(emptyMap<String, String>())
            .exchange().expectStatus().isForbidden
            .expectBody().jsonPath("$.code").isEqualTo("OPERATOR_ACCESS_DENIED")
    }

    @Test
    fun `operator route rejects unsafe origin host content type and preflight before dispatch`() {
        val path = "/operator/api/v1/reconciliation/run"
        val tenant = randomIdentifier()

        webTestClient.post().uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .header(TENANT_HEADER, tenant)
            .header(IDEMPOTENCY_HEADER, randomIdentifier())
            .header(OPERATOR_SECRET_HEADER, OPERATOR_SECRET)
            .header(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)
            .bodyValue(emptyMap<String, String>())
            .exchange().expectStatus().isForbidden

        webTestClient.post().uri(path)
            .contentType(MediaType.TEXT_PLAIN)
            .header("Origin", "http://127.0.0.1:$port")
            .header(TENANT_HEADER, tenant)
            .header(IDEMPOTENCY_HEADER, randomIdentifier())
            .header(OPERATOR_SECRET_HEADER, OPERATOR_SECRET)
            .header(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)
            .bodyValue("{}")
            .exchange().expectStatus().isForbidden

        webTestClient.options().uri(path)
            .header("Origin", "http://127.0.0.1:$port")
            .header("Access-Control-Request-Method", "POST")
            .header(TENANT_HEADER, tenant)
            .header(OPERATOR_SECRET_HEADER, OPERATOR_SECRET)
            .header(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)
            .exchange().expectStatus().isForbidden
    }

    @Test
    fun `same origin browser GET uses explicit origin header when browsers omit Origin`() {
        val tenant = randomIdentifier()

        webTestClient.get().uri("/operator/api/v1/reviews?status=OPEN&limit=1")
            .header("X-Workshop-Origin", "http://127.0.0.1:$port")
            .header(TENANT_HEADER, tenant)
            .header(OPERATOR_SECRET_HEADER, OPERATOR_SECRET)
            .header(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)
            .exchange().expectStatus().isOk

        webTestClient.get().uri("/operator/api/v1/reviews?status=OPEN&limit=1")
            .header("X-Workshop-Origin", "https://untrusted.example")
            .header(TENANT_HEADER, tenant)
            .header(OPERATOR_SECRET_HEADER, OPERATOR_SECRET)
            .header(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)
            .exchange().expectStatus().isForbidden
    }
}
