package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.workshop.commerce.voucherpool.AbstractVoucherPoolIntegrationTest
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolProperties
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.core.MethodParameter
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.ServletWebRequest

@Import(TenantPrincipalTestConfiguration::class)
internal class TenantPrincipalAuthorizationIntegrationTest : AbstractVoucherPoolIntegrationTest() {
    @Test
    fun `loopback demo auth resolves bounded tenant and principal`() {
        customerGet("tenant-a", "principal-a", "tenant-a", "principal-a")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.tenantId").isEqualTo("tenant-a")
            .jsonPath("$.principalId").isEqualTo("principal-a")
    }

    @Test
    fun `cross tenant cross principal and forged demo identity are uniform not found`() {
        customerGet("tenant-b", "principal-a", "tenant-a", "principal-a")
            .exchange().expectStatus().isNotFound
        customerGet("tenant-a", "principal-b", "tenant-a", "principal-a")
            .exchange().expectStatus().isNotFound
        customerGet("tenant-a", "principal-a", "tenant-a", "principal-a")
            .header("X-Demo-Principal", "forged")
            .exchange().expectStatus().isNotFound
    }

    @Test
    fun `public bind without production authentication adapter fails startup`() {
        ApplicationContextRunner()
            .withUserConfiguration(PublicBindValidationConfiguration::class.java)
            .withPropertyValues(
                "server.address=0.0.0.0",
                "workshop.voucher-pool.http.demo-auth-enabled=true",
                VALID_OPERATOR_SECRET_PROPERTY,
                VALID_OPERATOR_GUARD_PROPERTY,
            )
            .run { context ->
                context.startupFailure?.message?.contains("PUBLIC_BIND_REQUIRES_PRODUCTION_AUTH") shouldBeEqualTo true
            }
    }

    @Test
    fun `public bind with production authentication adapter passes the startup boundary`() {
        ApplicationContextRunner()
            .withUserConfiguration(PublicBindWithAdapterValidationConfiguration::class.java)
            .withPropertyValues(
                "server.address=0.0.0.0",
                VALID_OPERATOR_SECRET_PROPERTY,
                VALID_OPERATOR_GUARD_PROPERTY,
            )
            .run { context -> context.startupFailure.shouldBeNull() }
    }

    @Test
    fun `missing operator credentials fail startup by default on loopback`() {
        ApplicationContextRunner()
            .withUserConfiguration(PublicBindValidationConfiguration::class.java)
            .withPropertyValues("server.address=127.0.0.1")
            .run { context ->
                (context.startupFailure != null) shouldBeEqualTo true
            }
    }

    @Test
    fun `oversized operator credentials fail startup on loopback`() {
        ApplicationContextRunner()
            .withUserConfiguration(PublicBindValidationConfiguration::class.java)
            .withPropertyValues(
                "server.address=127.0.0.1",
                "workshop.voucher-pool.http.operator-secret=${"s".repeat(257)}",
                "workshop.voucher-pool.http.operator-guard=${"g".repeat(257)}",
            )
            .run { context ->
                context.startupFailure?.message
                    ?.contains("INVALID_OPERATOR_CREDENTIAL_CONFIGURATION") shouldBeEqualTo true
            }
    }

    @Test
    fun `multiple production adapters fail startup on every bind`() {
        ApplicationContextRunner()
            .withUserConfiguration(MultipleAdapterValidationConfiguration::class.java)
            .withPropertyValues(
                "server.address=127.0.0.1",
                VALID_OPERATOR_SECRET_PROPERTY,
                VALID_OPERATOR_GUARD_PROPERTY,
            )
            .run { context ->
                context.startupFailure?.message?.contains("MULTIPLE_PRODUCTION_AUTH_ADAPTERS") shouldBeEqualTo true
            }
    }

    @Test
    fun `resolver fails closed instead of throwing bean ambiguity for multiple adapters`() {
        val beans =
            StaticListableBeanFactory(
                mapOf(
                    "adapterOne" to VoucherPoolProductionAuthAdapter { TenantPrincipal("one", "one") },
                    "adapterTwo" to VoucherPoolProductionAuthAdapter { TenantPrincipal("two", "two") },
                ),
            )
        val resolver =
            TenantPrincipalResolver(
                VoucherPoolProperties(
                    http =
                        VoucherPoolHttpProperties(
                            operatorSecret = "test-operator-secret-0000000000000001",
                            operatorGuard = "test-voucher-pool-operator-guard",
                        ),
                ),
                MockEnvironment().withProperty("server.address", "127.0.0.1"),
                beans.getBeanProvider(VoucherPoolProductionAuthAdapter::class.java),
            )
        val method =
            TenantPrincipalProbeController::class.java.getDeclaredMethod(
                "principal",
                TenantPrincipal::class.java,
                String::class.java,
                String::class.java,
            )

        val failure =
            assertFailsWith<VoucherPoolApiException> {
                resolver.resolveArgument(
                    MethodParameter(method, 0),
                    null,
                    ServletWebRequest(MockHttpServletRequest("GET", "/api/v1/test/principals/one/one")),
                    null,
                )
            }

        failure.status shouldBeEqualTo 404
    }

    private fun customerGet(
        tenantHeader: String,
        principalHeader: String,
        tenantPath: String,
        principalPath: String,
    ) =
        webTestClient.get().uri("/api/v1/test/principals/$tenantPath/$principalPath")
            .header(TENANT_HEADER, tenantHeader)
            .header(PRINCIPAL_HEADER, principalHeader)
}

private const val VALID_OPERATOR_SECRET_PROPERTY =
    "workshop.voucher-pool.http.operator-secret=test-operator-secret-0000000000000001"
private const val VALID_OPERATOR_GUARD_PROPERTY =
    "workshop.voucher-pool.http.operator-guard=test-voucher-pool-operator-guard"

@TestConfiguration(proxyBeanMethods = false)
internal class TenantPrincipalTestConfiguration {
    @Bean
    fun tenantPrincipalProbeController(): TenantPrincipalProbeController = TenantPrincipalProbeController()
}

@Import(TenantPrincipalProductionAdapterTestConfiguration::class)
internal class TenantPrincipalProductionAdapterIntegrationTest : AbstractVoucherPoolIntegrationTest() {
    @Test
    fun `production authentication adapter takes precedence over loopback demo headers`() {
        customerGet().header("X-Test-Production-Auth", "enabled")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.tenantId").isEqualTo("tenant-adapter")
            .jsonPath("$.principalId").isEqualTo("principal-adapter")
    }

    @Test
    fun `production authentication rejection never falls back to loopback demo headers`() {
        customerGet().exchange().expectStatus().isNotFound
    }

    private fun customerGet() =
        webTestClient.get().uri("/api/v1/test/principals/tenant-adapter/principal-adapter")
            .header(TENANT_HEADER, "tenant-demo")
            .header(PRINCIPAL_HEADER, "principal-demo")
}

@TestConfiguration(proxyBeanMethods = false)
internal class TenantPrincipalProductionAdapterTestConfiguration {
    @Bean
    fun tenantPrincipalProbeController(): TenantPrincipalProbeController = TenantPrincipalProbeController()

    @Bean
    fun testProductionAuthAdapter(): VoucherPoolProductionAuthAdapter =
        VoucherPoolProductionAuthAdapter { request ->
            if (request.getHeader("X-Test-Production-Auth") == "enabled") {
                TenantPrincipal("tenant-adapter", "principal-adapter")
            } else {
                null
            }
        }
}

@RestController
internal class TenantPrincipalProbeController {
    @GetMapping("/api/v1/test/principals/{tenantId}/{principalId}")
    fun principal(
        principal: TenantPrincipal,
        @PathVariable tenantId: String,
        @PathVariable principalId: String,
    ): TenantPrincipal {
        if (principal.tenantId != tenantId || principal.principalId != principalId) {
            throw resourceNotFound()
        }
        return principal
    }
}

@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(VoucherPoolProperties::class)
internal class PublicBindValidationConfiguration {
    @Bean
    fun voucherPoolAuthStartupValidator(
        properties: VoucherPoolProperties,
        environment: org.springframework.core.env.Environment,
        productionAdapters: ObjectProvider<VoucherPoolProductionAuthAdapter>,
    ) =
        VoucherPoolAuthStartupValidator(properties, environment, productionAdapters)
}

@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(VoucherPoolProperties::class)
internal class PublicBindWithAdapterValidationConfiguration {
    @Bean
    fun productionAuthAdapter(): VoucherPoolProductionAuthAdapter =
        VoucherPoolProductionAuthAdapter { TenantPrincipal("tenant-adapter", "principal-adapter") }

    @Bean
    fun voucherPoolAuthStartupValidator(
        properties: VoucherPoolProperties,
        environment: org.springframework.core.env.Environment,
        productionAdapters: ObjectProvider<VoucherPoolProductionAuthAdapter>,
    ) = VoucherPoolAuthStartupValidator(properties, environment, productionAdapters)
}

@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(VoucherPoolProperties::class)
internal class MultipleAdapterValidationConfiguration {
    @Bean
    fun adapterOne(): VoucherPoolProductionAuthAdapter =
        VoucherPoolProductionAuthAdapter { TenantPrincipal("one", "one") }

    @Bean
    fun adapterTwo(): VoucherPoolProductionAuthAdapter =
        VoucherPoolProductionAuthAdapter { TenantPrincipal("two", "two") }

    @Bean
    fun voucherPoolAuthStartupValidator(
        properties: VoucherPoolProperties,
        environment: org.springframework.core.env.Environment,
        productionAdapters: ObjectProvider<VoucherPoolProductionAuthAdapter>,
    ) = VoucherPoolAuthStartupValidator(properties, environment, productionAdapters)
}
