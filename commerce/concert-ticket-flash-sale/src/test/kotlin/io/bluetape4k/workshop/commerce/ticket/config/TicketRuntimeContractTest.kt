package io.bluetape4k.workshop.commerce.ticket.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.virtualthread.VirtualThreads
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.env.PropertySourcesPropertyResolver
import org.springframework.core.io.ClassPathResource
import java.lang.management.ManagementFactory

internal class TicketRuntimeContractTest {
    @Test
    fun `Java 25 virtual threads and bounded database capacity are pinned`() {
        val sources =
            MutablePropertySources().apply {
                YamlPropertySourceLoader()
                    .load("ticket", ClassPathResource("application.yml"))
                    .forEach(::addLast)
            }
        val properties = PropertySourcesPropertyResolver(sources)

        (Runtime.version().feature() >= 25).shouldBeTrue()
        ManagementFactory.getRuntimeMXBean().inputArguments.contains("--enable-preview").shouldBeFalse()
        VirtualThreads.runtimeName() shouldBeEqualTo "jdk25"
        VirtualThreads.executorService().use { executor ->
            executor.submit<Boolean> { Thread.currentThread().isVirtual }.get().shouldBeTrue()
        }
        properties.getProperty("spring.threads.virtual.enabled") shouldBeEqualTo "true"
        properties.getProperty("spring.datasource.hikari.maximum-pool-size") shouldBeEqualTo "20"
        properties.getProperty("workshop.ticket.db.foreground-permits") shouldBeEqualTo "12"
        properties.getProperty("workshop.ticket.redis.lease-ttl") shouldBeEqualTo "5s"
        properties.getProperty("workshop.ticket.redis.rate-limit-capacity") shouldBeEqualTo "30"
    }
}
