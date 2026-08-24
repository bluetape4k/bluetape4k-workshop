package io.bluetape4k.workshop.optimization.shiftcoverage.config

import io.bluetape4k.concurrent.virtualthread.VirtualThreads
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.Clock
import java.util.concurrent.ExecutorService

@Configuration(proxyBeanMethods = false)
@Profile("demo")
internal class ShiftCoverageConfiguration {

    @Bean(destroyMethod = "")
    fun shiftCoverageVirtualThreadExecutor(): ExecutorService = VirtualThreads.executorService()

    @Bean
    fun shiftCoverageClock(): Clock = Clock.systemUTC()

    @Bean
    fun shiftCoverageExecutorShutdown(
        executor: ExecutorService,
        properties: ShiftCoverageProperties,
    ): ShiftCoverageExecutorShutdown = ShiftCoverageExecutorShutdown(executor, properties.shutdownDrain)
}
