package io.bluetape4k.workshop.commerce.voucher.web

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.voucher.AbstractVoucherIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

internal class VoucherRuntimeIntegrationTest : AbstractVoucherIntegrationTest() {
    @Autowired
    private lateinit var hikari: HikariDataSource

    @Test
    fun `live request uses a Java 25 virtual thread and Hikari stays bounded`() {
        webTestClient.get().uri("/internal/runtime-thread")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.virtual").isEqualTo(true)
            .jsonPath("$.javaFeature").isEqualTo(25)

        hikari.maximumPoolSize shouldBeEqualTo 16
        hikari.minimumIdle shouldBeEqualTo 4
        hikari.connectionTimeout shouldBeEqualTo 60_000L
    }
}
