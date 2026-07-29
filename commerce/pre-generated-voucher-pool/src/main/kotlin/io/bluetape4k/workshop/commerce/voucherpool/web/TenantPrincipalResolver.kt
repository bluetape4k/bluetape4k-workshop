package io.bluetape4k.workshop.commerce.voucherpool.web

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

/** server-side authentication boundary에서 확립한 identity입니다. */
internal data class TenantPrincipal(
    val tenantId: String,
    val principalId: String,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

/** production authentication integration point입니다. 구현체는 두 값을 모두 trusted server state에서 도출해야 합니다. */
internal fun interface VoucherPoolProductionAuthAdapter {
    fun resolve(request: HttpServletRequest): TenantPrincipal?
}

@Component
internal class TenantPrincipalResolver(
    private val properties: VoucherPoolProperties,
    private val environment: Environment,
    private val productionAdapters: ObjectProvider<VoucherPoolProductionAuthAdapter>,
) : HandlerMethodArgumentResolver,
    WebMvcConfigurer {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.parameterType == TenantPrincipal::class.java

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

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(this)
    }

    private fun resolveLoopbackDemoPrincipal(request: HttpServletRequest): TenantPrincipal {
        val http = properties.http
        if (!http.demoAuthEnabled || !serverAddress().isLoopbackHost() || !request.remoteAddr.isLoopbackHost()) {
            throw resourceNotFound()
        }
        return boundedPrincipal(
            request.getHeader(TENANT_HEADER),
            request.getHeader(PRINCIPAL_HEADER),
        )
    }

    private fun HttpServletRequest.hasLoopbackDemoIdentity(): Boolean =
        properties.http.demoAuthEnabled &&
            serverAddress().isLoopbackHost() &&
            remoteAddr.isLoopbackHost() &&
            getHeader(TENANT_HEADER) != null &&
            getHeader(PRINCIPAL_HEADER) != null

    private fun TenantPrincipal.validated(): TenantPrincipal =
        boundedPrincipal(tenantId, principalId)

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

    private fun serverAddress(): String = environment.getProperty("server.address", "127.0.0.1")
}

/** unauthenticated workshop adapter가 public bind에서 접근 가능해지지 못하게 합니다. */
@Component
internal class VoucherPoolAuthStartupValidator(
    private val properties: VoucherPoolProperties,
    private val environment: Environment,
    private val productionAdapters: ObjectProvider<VoucherPoolProductionAuthAdapter>,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        validateOperatorCredentials()
        val adapterCount = productionAdapters.orderedStream().limit(2).count()
        if (adapterCount > 1) error(MULTIPLE_PRODUCTION_AUTH_ADAPTERS)
        val address = environment.getProperty("server.address", "127.0.0.1")
        if (!address.isLoopbackHost() && adapterCount != 1L) {
            error(PUBLIC_BIND_FAILURE)
        }
    }

    private fun validateOperatorCredentials() {
        val http = properties.http
        if (!http.operatorSecret.isBoundedPrintableAscii(MIN_OPERATOR_SECRET_LENGTH) ||
            !http.operatorGuard.isBoundedPrintableAscii(MIN_OPERATOR_GUARD_LENGTH)
        ) {
            error(INVALID_OPERATOR_CREDENTIAL_CONFIGURATION)
        }
    }
}

internal fun String.isLoopbackHost(): Boolean =
    runCatching { InetAddress.getByName(this).isLoopbackAddress }.getOrDefault(false)

private fun String.isBoundedPrintableAscii(minLength: Int): Boolean =
    length in minLength..MAX_OPERATOR_CREDENTIAL_LENGTH && all { it.code in PRINTABLE_OPERATOR_ASCII }

private const val DEMO_PRINCIPAL_HEADER = "X-Demo-Principal"
private const val PUBLIC_BIND_FAILURE = "PUBLIC_BIND_REQUIRES_PRODUCTION_AUTH"
private const val MULTIPLE_PRODUCTION_AUTH_ADAPTERS = "MULTIPLE_PRODUCTION_AUTH_ADAPTERS"
private const val INVALID_OPERATOR_CREDENTIAL_CONFIGURATION = "INVALID_OPERATOR_CREDENTIAL_CONFIGURATION"
private const val MIN_OPERATOR_SECRET_LENGTH = 32
private const val MIN_OPERATOR_GUARD_LENGTH = 8
private const val MAX_OPERATOR_CREDENTIAL_LENGTH = 256
private const val PRINTABLE_ASCII_START = 0x21
private const val PRINTABLE_ASCII_END = 0x7e
private val PRINTABLE_OPERATOR_ASCII = PRINTABLE_ASCII_START..PRINTABLE_ASCII_END
