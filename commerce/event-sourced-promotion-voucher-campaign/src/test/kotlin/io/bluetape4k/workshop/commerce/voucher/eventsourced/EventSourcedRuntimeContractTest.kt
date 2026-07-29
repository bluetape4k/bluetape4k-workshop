package io.bluetape4k.workshop.commerce.voucher.eventsourced

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.env.PropertySourcesPropertyResolver
import org.springframework.core.io.ClassPathResource
import java.io.DataInputStream
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicBoolean

internal class EventSourcedRuntimeContractTest {

    @Test
    fun `event sourced application runs on Java 25 without preview and with virtual threads`() {
        Runtime.version().feature() shouldBeEqualTo 25
        ManagementFactory.getRuntimeMXBean().inputArguments shouldNotContain "--enable-preview"
        EventSourcedVoucherApplication::class.java.isAnnotationPresent(SpringBootApplication::class.java).shouldBeTrue()
        classFileMajorVersion(EventSourcedVoucherApplication::class.java) shouldBeEqualTo 69

        val executedOnVirtualThread = AtomicBoolean(false)
        Thread.ofVirtual().start {
            executedOnVirtualThread.set(Thread.currentThread().isVirtual)
        }.join()
        executedOnVirtualThread.get().shouldBeTrue()
    }

    @Test
    fun `application configuration enables Spring virtual threads`() {
        val sources =
            MutablePropertySources().apply {
                YamlPropertySourceLoader()
                    .load("event-sourced-voucher", ClassPathResource("application.yml"))
                    .forEach(::addLast)
            }

        PropertySourcesPropertyResolver(sources).getProperty("spring.threads.virtual.enabled") shouldBeEqualTo "true"
    }

    @Test
    fun `database configuration bounds pool wait lock wait and statement execution`() {
        val sources =
            MutablePropertySources().apply {
                YamlPropertySourceLoader()
                    .load("event-sourced-voucher", ClassPathResource("application.yml"))
                    .forEach(::addLast)
            }
        val properties = PropertySourcesPropertyResolver(sources)

        properties.getProperty("spring.datasource.hikari.maximum-pool-size") shouldBeEqualTo "20"
        properties.getProperty("spring.datasource.hikari.connection-timeout") shouldBeEqualTo "250"
        properties.getProperty("spring.datasource.hikari.data-source-properties.options") shouldBeEqualTo
            "-c lock_timeout=500ms -c statement_timeout=3s"
    }

    private fun classFileMajorVersion(type: Class<*>): Int {
        val resourceName = "/${type.name.replace('.', '/')}.class"
        return DataInputStream(type.getResourceAsStream(resourceName).shouldNotBeNull()).use { input ->
            input.readInt() shouldBeEqualTo 0xCAFEBABE.toInt()
            input.readUnsignedShort()
            input.readUnsignedShort()
        }
    }
}
