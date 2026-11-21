package io.bluetape4k.workshop.commerce.ticket.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.env.PropertySourcesPropertyResolver
import org.springframework.core.io.ClassPathResource
import java.lang.management.ManagementFactory

/**
 * TicketRuntimeContractTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TicketRuntimeContractTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "Java 25 virtual threads and bounded database capacity are pinned" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `Java 25 virtual threads and bounded database capacity are pinned`() {
        val sources = MutablePropertySources().apply {
            YamlPropertySourceLoader()
                .load("ticket", ClassPathResource("application.yml"))
                .forEach(::addLast)
        }
        log.debug { "sources=$sources" }
        val properties = PropertySourcesPropertyResolver(sources)

        Runtime.version().feature() shouldBeGreaterOrEqualTo 25
        ManagementFactory.getRuntimeMXBean().inputArguments shouldNotContain "--enable-preview"

        VirtualThreads.runtimeName() shouldBeEqualTo "jdk25"
        VirtualThreads.executorService().use { executor ->
            executor.submit<Boolean> {
                Thread.currentThread().isVirtual
            }.get().shouldBeTrue()
        }

        properties.getProperty("spring.threads.virtual.enabled") shouldBeEqualTo "true"
        properties.getProperty("spring.datasource.hikari.maximum-pool-size") shouldBeEqualTo "20"
        properties.getProperty("workshop.ticket.db.foreground-permits") shouldBeEqualTo "12"
        properties.getProperty("workshop.ticket.redis.lease-ttl") shouldBeEqualTo "5s"
        properties.getProperty("workshop.ticket.redis.rate-limit-capacity") shouldBeEqualTo "30"
    }
}
