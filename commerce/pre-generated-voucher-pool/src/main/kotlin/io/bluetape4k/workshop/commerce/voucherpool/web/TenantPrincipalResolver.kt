package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolProperties
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.core.MethodParameter
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.io.Serializable
import java.net.InetAddress

/**
 * server-side authentication boundary에서 확립한 identity입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property principalId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 */
internal data class TenantPrincipal(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `principalId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val principalId: String,
) : Serializable {
    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/** production authentication integration point입니다. 구현체는 두 값을 모두 trusted server state에서 도출해야 합니다. */
/** production authentication integration point입니다. 구현체는 두 값을 모두 trusted server state에서 도출해야 합니다. */
internal fun interface VoucherPoolProductionAuthAdapter {
    /**
     * resolve은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun resolve(request: HttpServletRequest): TenantPrincipal?
}
/**
 * TenantPrincipalResolver은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property properties 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property environment 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property productionAdapters 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
internal class TenantPrincipalResolver(
    /**
     * `properties`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: VoucherPoolProperties,
    /**
     * `environment`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val environment: Environment,
    /**
     * `productionAdapters`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val productionAdapters: ObjectProvider<VoucherPoolProductionAuthAdapter>,
) : HandlerMethodArgumentResolver,
    WebMvcConfigurer {

    companion object: KLogging()

    /**
     * supportsParameter은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param parameter 업무 처리에 필요한 parameter 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.parameterType == TenantPrincipal::class.java

    /**
     * resolveArgument은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param parameter 업무 처리에 필요한 parameter 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param mavContainer 업무 처리에 필요한 mavContainer 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param webRequest 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param binderFactory 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     */
    @Suppress("ThrowsCount")
    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): TenantPrincipal {
        val request = webRequest.getNativeRequest(HttpServletRequest::class.java) ?: throw resourceNotFound()
        if (request.getHeader(DEMO_PRINCIPAL_HEADER) != null) throw resourceNotFound()
        val adapters = productionAdapters.orderedStream().limit(2).toList()
        if (adapters.size > 1) throw resourceNotFound()

        adapters.singleOrNull()?.let { adapter ->
            return adapter.resolve(request)?.validated() ?: throw resourceNotFound()
        }

        return if (request.hasLoopbackDemoIdentity()) {
            resolveLoopbackDemoPrincipal(request)
        } else {
            throw resourceNotFound()
        }
    }

    /**
     * addArgumentResolvers은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된
     * 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param resolvers 업무 처리에 필요한 resolvers 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(this)
    }

    /**
     * resolveLoopbackDemoPrincipal은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무
     * 오류를 계약된
     * 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun resolveLoopbackDemoPrincipal(request: HttpServletRequest): TenantPrincipal {
        val http = properties.http
        if (!http.demoAuthEnabled || !serverAddress().isLoopbackHost() || !request.remoteAddr.isLoopbackHost()) {
            throw resourceNotFound()
        }
        return boundedPrincipal(
            tenant = request.getHeader(TENANT_HEADER),
            principal = request.getHeader(PRINCIPAL_HEADER),
        )
    }

    /**
     * hasLoopbackDemoIdentity은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된
     * 응답으로
     * 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun HttpServletRequest.hasLoopbackDemoIdentity(): Boolean =
        properties.http.demoAuthEnabled &&
            serverAddress().isLoopbackHost() &&
            remoteAddr.isLoopbackHost() &&
            getHeader(TENANT_HEADER) != null &&
            getHeader(PRINCIPAL_HEADER) != null

    /**
     * validated은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun TenantPrincipal.validated(): TenantPrincipal =
        boundedPrincipal(tenantId, principalId)

    /**
     * boundedPrincipal은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenant 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param principal 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     */
    private fun boundedPrincipal(tenant: String?, principal: String?): TenantPrincipal {
        val http = properties.http

        return try {
            TenantPrincipal(
                tenantId = requireBoundedAscii(tenant, 1, http.maxTenantLength),
                principalId = requireBoundedAscii(principal, 1, http.maxPrincipalLength),
            )
        } catch (_: VoucherPoolApiException) {
            throw resourceNotFound()
        }
    }

    /**
     * serverAddress은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun serverAddress(): String =
        environment.getProperty("server.address", "127.0.0.1")
}

/**
 * unauthenticated workshop adapter가 public bind에서 접근 가능해지지 못하게 합니다.
 * @property properties 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property environment 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property productionAdapters 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Component
internal class VoucherPoolAuthStartupValidator(
    /**
     * `properties`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: VoucherPoolProperties,
    /**
     * `environment`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val environment: Environment,
    /**
     * `productionAdapters`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val productionAdapters: ObjectProvider<VoucherPoolProductionAuthAdapter>,
) : SmartInitializingSingleton {

    companion object: KLogging()

    /**
     * afterSingletonsInstantiated은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를
     * 계약된
     * 응답으로 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun afterSingletonsInstantiated() {
        validateOperatorCredentials()
        val adapterCount = productionAdapters.orderedStream().limit(2).count()
        if (adapterCount > 1) error(MULTIPLE_PRODUCTION_AUTH_ADAPTERS)
        val address = environment.getProperty("server.address", "127.0.0.1")
        if (!address.isLoopbackHost() && adapterCount != 1L) {
            error(PUBLIC_BIND_FAILURE)
        }
    }

    /**
     * validateOperatorCredentials은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와
     * 부분 저장을 막습니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun validateOperatorCredentials() {
        val http = properties.http
        if (!http.operatorSecret.isBoundedPrintableAscii(MIN_OPERATOR_SECRET_LENGTH) ||
            !http.operatorGuard.isBoundedPrintableAscii(MIN_OPERATOR_GUARD_LENGTH)
        ) {
            error(INVALID_OPERATOR_CREDENTIAL_CONFIGURATION)
        }
    }
}

/**
 * isLoopbackHost은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
internal fun String.isLoopbackHost(): Boolean =
    runCatching { InetAddress.getByName(this).isLoopbackAddress }.getOrDefault(false)

/**
 * isBoundedPrintableAscii은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된
 * 응답으로 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param minLength 업무 처리에 필요한 minLength 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun String.isBoundedPrintableAscii(minLength: Int): Boolean =
    length in minLength..MAX_OPERATOR_CREDENTIAL_LENGTH && all { it.code in PRINTABLE_OPERATOR_ASCII }

/**
 * `DEMO_PRINCIPAL_HEADER`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
 *
 * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
 */
private const val DEMO_PRINCIPAL_HEADER = "X-Demo-Principal"
/**
 * `PUBLIC_BIND_FAILURE`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val PUBLIC_BIND_FAILURE = "PUBLIC_BIND_REQUIRES_PRODUCTION_AUTH"
/**
 * `MULTIPLE_PRODUCTION_AUTH_ADAPTERS`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
 *
 * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
 */
private const val MULTIPLE_PRODUCTION_AUTH_ADAPTERS = "MULTIPLE_PRODUCTION_AUTH_ADAPTERS"
/**
 * `INVALID_OPERATOR_CREDENTIAL_CONFIGURATION`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val INVALID_OPERATOR_CREDENTIAL_CONFIGURATION = "INVALID_OPERATOR_CREDENTIAL_CONFIGURATION"
/**
 * `MIN_OPERATOR_SECRET_LENGTH`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val MIN_OPERATOR_SECRET_LENGTH = 32
/**
 * `MIN_OPERATOR_GUARD_LENGTH`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val MIN_OPERATOR_GUARD_LENGTH = 8
/**
 * `MAX_OPERATOR_CREDENTIAL_LENGTH`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val MAX_OPERATOR_CREDENTIAL_LENGTH = 256
/**
 * `PRINTABLE_ASCII_START`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val PRINTABLE_ASCII_START = 0x21
/**
 * `PRINTABLE_ASCII_END`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val PRINTABLE_ASCII_END = 0x7e
/**
 * `PRINTABLE_OPERATOR_ASCII`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private val PRINTABLE_OPERATOR_ASCII = PRINTABLE_ASCII_START..PRINTABLE_ASCII_END
