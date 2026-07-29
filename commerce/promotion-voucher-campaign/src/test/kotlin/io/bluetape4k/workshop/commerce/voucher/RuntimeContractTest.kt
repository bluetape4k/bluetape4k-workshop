package io.bluetape4k.workshop.commerce.voucher

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.virtualthread.VirtualThreads
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.env.PropertySourcesPropertyResolver
import org.springframework.core.io.ClassPathResource

internal class RuntimeContractTest {
    @Test
    fun `Java 25 virtual threads and bounded JDBC settings are pinned`() {
        val sources =
            MutablePropertySources().apply {
                YamlPropertySourceLoader()
                    .load("voucher", ClassPathResource("application.yml"))
                    .forEach(::addLast)
            }
        val properties = PropertySourcesPropertyResolver(sources)

        (Runtime.version().feature() >= 25).shouldBeTrue()
        VirtualThreads.runtimeName() shouldBeEqualTo "jdk25"
        VirtualThreads.executorService().use { executor ->
            executor.submit<Boolean> { Thread.currentThread().isVirtual }.get().shouldBeTrue()
        }
        properties.getProperty("spring.threads.virtual.enabled") shouldBeEqualTo "true"
        properties.getProperty("spring.datasource.hikari.maximum-pool-size") shouldBeEqualTo "16"
        properties.getProperty("spring.transaction.default-timeout") shouldBeEqualTo "60s"
        properties.getProperty("server.tomcat.threads.max") shouldBeEqualTo "8000"
        properties.getProperty("server.tomcat.max-connections") shouldBeEqualTo "8000"
    }
}
