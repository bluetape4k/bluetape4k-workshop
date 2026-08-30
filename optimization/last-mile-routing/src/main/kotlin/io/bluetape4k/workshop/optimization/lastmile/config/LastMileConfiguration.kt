package io.bluetape4k.workshop.optimization.lastmile.config

import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.workshop.optimization.lastmile.application.LastMileLifecycle
import io.bluetape4k.workshop.optimization.lastmile.provider.DeterministicRoutingProvider
import io.bluetape4k.workshop.optimization.lastmile.provider.RoutingProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.concurrent.ExecutorService

@Configuration(proxyBeanMethods = false)
internal class LastMileConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean(destroyMethod = "")
    fun lastMileExecutor(): ExecutorService = VirtualThreads.executorService()

    @Bean
    fun lastMileLifecycle(@Qualifier("lastMileExecutor") executor: ExecutorService): LastMileLifecycle =
        LastMileLifecycle(executor)

    @Bean
    @ConditionalOnMissingBean(RoutingProvider::class)
    fun routingProvider(): RoutingProvider = DeterministicRoutingProvider()
}
