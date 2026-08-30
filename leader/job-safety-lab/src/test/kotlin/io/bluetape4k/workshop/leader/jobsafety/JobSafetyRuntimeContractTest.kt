package io.bluetape4k.workshop.leader.jobsafety

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.env.PropertySourcesPropertyResolver
import org.springframework.core.io.ClassPathResource
import java.lang.management.ManagementFactory

internal class JobSafetyRuntimeContractTest {
    @Test
    fun `Java 25 virtual threads and bounded coordination defaults are pinned`() {
        val sources =
            MutablePropertySources().apply {
                YamlPropertySourceLoader()
                    .load("job-safety", ClassPathResource("application.yml"))
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
        properties.getProperty("spring.datasource.hikari.maximum-pool-size") shouldBeEqualTo "12"
        properties.getProperty("workshop.job-safety.fencing.lease-ttl") shouldBeEqualTo "5s"
        properties.getProperty("workshop.job-safety.timeline-limit") shouldBeEqualTo "128"
    }
}
