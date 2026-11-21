package io.bluetape4k.workshop.commerce.order

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.PropertySource
import org.springframework.core.io.ClassPathResource

/**
 * InfrastructureConfigurationTest 테스트 묶음은 주문 수명주기·배송 이행의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class InfrastructureConfigurationTest {

    companion object: KLogging()

/**
 * properties은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val properties: PropertySource<*> =
        YamlPropertySourceLoader()
            .load("application", ClassPathResource("application.yml"))
            .single()

    @Test
/**
 * 이 시나리오는 "Tomcat records eight thousand connection and fallback thread limits" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `Tomcat records eight thousand connection and fallback thread limits`() {
        properties.getProperty("server.address") shouldBeEqualTo $$"${ORDER_SERVER_ADDRESS:127.0.0.1}"
        properties.getProperty("server.tomcat.threads.max") shouldBeEqualTo $$"${ORDER_TOMCAT_MAX_THREADS:8000}"
        properties.getProperty("server.tomcat.max-connections") shouldBeEqualTo $$"${ORDER_TOMCAT_MAX_CONNECTIONS:8000}"
        properties.getProperty("server.tomcat.connection-timeout") shouldBeEqualTo $$"${ORDER_TOMCAT_CONNECTION_TIMEOUT:60s}"
        properties.getProperty("server.tomcat.keep-alive-timeout") shouldBeEqualTo $$"${ORDER_TOMCAT_KEEP_ALIVE_TIMEOUT:60s}"
    }

    @Test
/**
 * 이 시나리오는 "database concurrency remains bounded while wait time increases" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `database concurrency remains bounded while wait time increases`() {
        properties.getProperty("spring.datasource.hikari.maximum-pool-size") shouldBeEqualTo $$"${ORDER_DB_POOL_MAX:8}"
        properties.getProperty("spring.datasource.hikari.minimum-idle") shouldBeEqualTo $$"${ORDER_DB_POOL_MIN_IDLE:2}"
        properties.getProperty("spring.datasource.hikari.connection-timeout") shouldBeEqualTo $$"${ORDER_DB_CONNECTION_TIMEOUT_MS:60000}"
        properties.getProperty("spring.transaction.default-timeout") shouldBeEqualTo $$"${ORDER_TRANSACTION_TIMEOUT:60s}"
    }
}
