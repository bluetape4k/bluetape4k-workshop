package io.bluetape4k.workshop.aws.settings

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import java.net.URI
import java.net.URISyntaxException

/**
 * AppConfig Data endpoint override가 자격 증명을 임의의 호스트로 보내지 않도록
 * 소비자 예제에서 신뢰 경계를 고정합니다.
 *
 * 운영 endpoint는 현재 region의 AWS AppConfig Data HTTPS 주소만 허용하고,
 * HTTP literal loopback은 합성 credential을 사용하는 테스트 에뮬레이터에만 허용합니다.
 */
class SettingsBoundaryAppConfigEndpointGuard : EnvironmentPostProcessor, Ordered {

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        val rawEndpoint = environment.getProperty(ENDPOINT_PROPERTY) ?: return
        val region = environment.getProperty(APP_CONFIG_REGION_PROPERTY)
            ?: environment.getProperty(REGION_PROPERTY)
            ?: environment.getProperty("AWS_REGION")
            ?: DEFAULT_SAMPLE_REGION
        val endpoint = try {
            URI(rawEndpoint)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("$ENDPOINT_PROPERTY must be a valid URI.")
        } catch (_: URISyntaxException) {
            throw IllegalArgumentException("$ENDPOINT_PROPERTY must be a valid URI.")
        }
        validateAppConfigEndpoint(endpoint, region)
    }

    private companion object {
        const val ENDPOINT_PROPERTY = "bluetape4k.aws.app-config.endpoint-override"
        const val REGION_PROPERTY = "bluetape4k.aws.region"
        const val APP_CONFIG_REGION_PROPERTY = "bluetape4k.aws.app-config.region"
        const val DEFAULT_SAMPLE_REGION = "ap-northeast-2"
    }
}

internal fun validateAppConfigEndpoint(endpoint: URI, region: String?) {
    require(endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null) {
        "bluetape4k.aws.app-config.endpoint-override must not contain user info, query, or fragment."
    }
    val host = endpoint.host?.lowercase()?.trimEnd('.')
    require(!host.isNullOrBlank()) {
        "bluetape4k.aws.app-config.endpoint-override must contain a host."
    }

    val normalizedRegion = region?.trim()?.lowercase()
    val isLoopbackTestEndpoint = endpoint.scheme.equals("http", ignoreCase = true) &&
        host in LOOPBACK_HOSTS
    val isTrustedAwsEndpoint = endpoint.scheme.equals("https", ignoreCase = true) &&
        normalizedRegion != null &&
        host in trustedAwsAppConfigHosts(normalizedRegion) &&
        (endpoint.port == -1 || endpoint.port == 443) &&
        (endpoint.path.isEmpty() || endpoint.path == "/")
    require(isLoopbackTestEndpoint || isTrustedAwsEndpoint) {
        "bluetape4k.aws.app-config.endpoint-override must use a regional AWS HTTPS host; " +
            "HTTP is restricted to loopback tests."
    }
}

private fun trustedAwsAppConfigHosts(region: String): Set<String> =
    buildSet {
        add("appconfigdata.$region.amazonaws.com")
        add("appconfigdata-fips.$region.amazonaws.com")
        if (region.startsWith("cn-")) {
            add("appconfigdata.$region.amazonaws.com.cn")
            add("appconfigdata-fips.$region.amazonaws.com.cn")
        }
    }

private val LOOPBACK_HOSTS = setOf("127.0.0.1")
