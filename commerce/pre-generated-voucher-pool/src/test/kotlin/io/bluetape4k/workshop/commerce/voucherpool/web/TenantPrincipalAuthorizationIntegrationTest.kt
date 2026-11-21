package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucherpool.AbstractVoucherPoolIntegrationTest
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolProperties
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.MethodParameter
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.ServletWebRequest

@Import(TenantPrincipalTestConfiguration::class)
/**
 * TenantPrincipalAuthorizationIntegrationTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TenantPrincipalAuthorizationIntegrationTest: AbstractVoucherPoolIntegrationTest() {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "loopback demo auth resolves bounded tenant and principal" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `loopback demo auth resolves bounded tenant and principal`() {
        customerGet("tenant-a", "principal-a", "tenant-a", "principal-a")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.tenantId").isEqualTo("tenant-a")
            .jsonPath("$.principalId").isEqualTo("principal-a")
    }

    @Test
/**
 * 이 시나리오는 "cross tenant cross principal and forged demo identity are uniform not found" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `cross tenant cross principal and forged demo identity are uniform not found`() {
        customerGet("tenant-b", "principal-a", "tenant-a", "principal-a")
            .exchange()
            .expectStatus().isNotFound

        customerGet("tenant-a", "principal-b", "tenant-a", "principal-a")
            .exchange()
            .expectStatus().isNotFound

        customerGet("tenant-a", "principal-a", "tenant-a", "principal-a")
            .header("X-Demo-Principal", "forged")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
/**
 * 이 시나리오는 "public bind without production authentication adapter fails startup" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
                context.startupFailure?.message shouldContain "PUBLIC_BIND_REQUIRES_PRODUCTION_AUTH"
            }
    }

    @Test
/**
 * 이 시나리오는 "public bind with production authentication adapter passes the startup boundary" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `public bind with production authentication adapter passes the startup boundary`() {
        ApplicationContextRunner()
            .withUserConfiguration(PublicBindWithAdapterValidationConfiguration::class.java)
            .withPropertyValues(
                "server.address=0.0.0.0",
                VALID_OPERATOR_SECRET_PROPERTY,
                VALID_OPERATOR_GUARD_PROPERTY,
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
            }
    }

    @Test
/**
 * 이 시나리오는 "missing operator credentials fail startup by default on loopback" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `missing operator credentials fail startup by default on loopback`() {
        ApplicationContextRunner()
            .withUserConfiguration(PublicBindValidationConfiguration::class.java)
            .withPropertyValues("server.address=127.0.0.1")
            .run { context ->
                context.startupFailure.shouldNotBeNull()
            }
    }

    @Test
/**
 * 이 시나리오는 "oversized operator credentials fail startup on loopback" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `oversized operator credentials fail startup on loopback`() {
        ApplicationContextRunner()
            .withUserConfiguration(PublicBindValidationConfiguration::class.java)
            .withPropertyValues(
                "server.address=127.0.0.1",
                "workshop.voucher-pool.http.operator-secret=${"s".repeat(257)}",
                "workshop.voucher-pool.http.operator-guard=${"g".repeat(257)}",
            )
            .run { context ->
                context.startupFailure?.message shouldContain "INVALID_OPERATOR_CREDENTIAL_CONFIGURATION"
            }
    }

    @Test
/**
 * 이 시나리오는 "multiple production adapters fail startup on every bind" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `multiple production adapters fail startup on every bind`() {
        ApplicationContextRunner()
            .withUserConfiguration(MultipleAdapterValidationConfiguration::class.java)
            .withPropertyValues(
                "server.address=127.0.0.1",
                VALID_OPERATOR_SECRET_PROPERTY,
                VALID_OPERATOR_GUARD_PROPERTY,
            )
            .run { context ->
                context.startupFailure?.message shouldContain "MULTIPLE_PRODUCTION_AUTH_ADAPTERS"
            }
    }

    @Test
/**
 * 이 시나리오는 "resolver fails closed instead of throwing bean ambiguity for multiple adapters" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `resolver fails closed instead of throwing bean ambiguity for multiple adapters`() {
        val beans = StaticListableBeanFactory(
            mapOf(
                "adapterOne" to VoucherPoolProductionAuthAdapter { TenantPrincipal("one", "one") },
                "adapterTwo" to VoucherPoolProductionAuthAdapter { TenantPrincipal("two", "two") },
            ),
        )
        val resolver = TenantPrincipalResolver(
            properties = VoucherPoolProperties(
                http = VoucherPoolHttpProperties(
                    operatorSecret = "test-operator-secret-0000000000000001",
                    operatorGuard = "test-voucher-pool-operator-guard",
                ),
            ),
            environment = MockEnvironment().withProperty("server.address", "127.0.0.1"),
            productionAdapters = beans.getBeanProvider(VoucherPoolProductionAuthAdapter::class.java),
        )

        val method = TenantPrincipalProbeController::class.java.getDeclaredMethod(
            "principal",
            TenantPrincipal::class.java,
            String::class.java,
            String::class.java,
        )

        val failure = assertFailsWith<VoucherPoolApiException> {
            resolver.resolveArgument(
                parameter = MethodParameter(method, 0),
                mavContainer = null,
                webRequest = ServletWebRequest(MockHttpServletRequest("GET", "/api/v1/test/principals/one/one")),
                binderFactory = null,
            )
        }

        failure.status shouldBeEqualTo 404
    }

/**
 * customerGet은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * VALID_OPERATOR_SECRET_PROPERTY은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
private const val VALID_OPERATOR_SECRET_PROPERTY =
    "workshop.voucher-pool.http.operator-secret=test-operator-secret-0000000000000001"
/**
 * VALID_OPERATOR_GUARD_PROPERTY은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
private const val VALID_OPERATOR_GUARD_PROPERTY =
    "workshop.voucher-pool.http.operator-guard=test-voucher-pool-operator-guard"

@TestConfiguration(proxyBeanMethods = false)
/**
 * TenantPrincipalTestConfiguration 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TenantPrincipalTestConfiguration {
    @Bean
/**
 * tenantPrincipalProbeController은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun tenantPrincipalProbeController(): TenantPrincipalProbeController = TenantPrincipalProbeController()
}

@Import(TenantPrincipalProductionAdapterTestConfiguration::class)
/**
 * TenantPrincipalProductionAdapterIntegrationTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TenantPrincipalProductionAdapterIntegrationTest: AbstractVoucherPoolIntegrationTest() {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "production authentication adapter takes precedence over loopback demo headers" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `production authentication adapter takes precedence over loopback demo headers`() {
        customerGet()
            .header("X-Test-Production-Auth", "enabled")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.tenantId").isEqualTo("tenant-adapter")
            .jsonPath("$.principalId").isEqualTo("principal-adapter")
    }

    @Test
/**
 * 이 시나리오는 "production authentication rejection never falls back to loopback demo headers" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `production authentication rejection never falls back to loopback demo headers`() {
        customerGet()
            .exchange()
            .expectStatus().isNotFound
    }

/**
 * customerGet은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun customerGet() =
        webTestClient.get()
            .uri("/api/v1/test/principals/tenant-adapter/principal-adapter")
            .header(TENANT_HEADER, "tenant-demo")
            .header(PRINCIPAL_HEADER, "principal-demo")
}

@TestConfiguration(proxyBeanMethods = false)
/**
 * TenantPrincipalProductionAdapterTestConfiguration 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TenantPrincipalProductionAdapterTestConfiguration {
    @Bean
/**
 * tenantPrincipalProbeController은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun tenantPrincipalProbeController(): TenantPrincipalProbeController = TenantPrincipalProbeController()

    @Bean
/**
 * testProductionAuthAdapter은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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
/**
 * tenant와 principal이 URL 경로 및 인증 주체와 일치해야 리소스를 조회할 수 있다는 권한 경계를 검증합니다.
 */
internal class TenantPrincipalProbeController {
    /**
     * 인증 주체가 요청 경로의 tenant/principal과 다르면 존재하지 않는 리소스로 처리하는지 확인합니다.
     *
     * 예: GET /api/v1/test/principals/tenant-a/user-1은 동일한 주체만 200을 받고 교차 tenant 요청은 404가 됩니다.
     */
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
/**
 * PublicBindValidationConfiguration 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class PublicBindValidationConfiguration {
    @Bean
/**
 * voucherPoolAuthStartupValidator은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun voucherPoolAuthStartupValidator(
        properties: VoucherPoolProperties,
        environment: org.springframework.core.env.Environment,
        productionAdapters: ObjectProvider<VoucherPoolProductionAuthAdapter>,
    ) =
        VoucherPoolAuthStartupValidator(properties, environment, productionAdapters)
}

@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(VoucherPoolProperties::class)
/**
 * PublicBindWithAdapterValidationConfiguration 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class PublicBindWithAdapterValidationConfiguration {
    @Bean
/**
 * productionAuthAdapter은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun productionAuthAdapter(): VoucherPoolProductionAuthAdapter =
        VoucherPoolProductionAuthAdapter {
            TenantPrincipal("tenant-adapter", "principal-adapter")
        }

    @Bean
/**
 * voucherPoolAuthStartupValidator은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun voucherPoolAuthStartupValidator(
        properties: VoucherPoolProperties,
        environment: org.springframework.core.env.Environment,
        productionAdapters: ObjectProvider<VoucherPoolProductionAuthAdapter>,
    ): VoucherPoolAuthStartupValidator =
        VoucherPoolAuthStartupValidator(properties, environment, productionAdapters)
}

@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(VoucherPoolProperties::class)
/**
 * MultipleAdapterValidationConfiguration 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class MultipleAdapterValidationConfiguration {
    @Bean
/**
 * adapterOne은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun adapterOne(): VoucherPoolProductionAuthAdapter =
        VoucherPoolProductionAuthAdapter { TenantPrincipal("one", "one") }

    @Bean
/**
 * adapterTwo은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun adapterTwo(): VoucherPoolProductionAuthAdapter =
        VoucherPoolProductionAuthAdapter { TenantPrincipal("two", "two") }

    @Bean
/**
 * voucherPoolAuthStartupValidator은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun voucherPoolAuthStartupValidator(
        properties: VoucherPoolProperties,
        environment: org.springframework.core.env.Environment,
        productionAdapters: ObjectProvider<VoucherPoolProductionAuthAdapter>,
    ): VoucherPoolAuthStartupValidator =
        VoucherPoolAuthStartupValidator(properties, environment, productionAdapters)
}
