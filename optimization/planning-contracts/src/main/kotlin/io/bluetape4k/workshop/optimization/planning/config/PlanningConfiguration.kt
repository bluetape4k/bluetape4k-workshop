package io.bluetape4k.workshop.optimization.planning.config

import io.bluetape4k.concurrent.virtualthread.VirtualThreads
import io.bluetape4k.workshop.optimization.planning.adapter.fake.DeterministicPlanningEngine
import io.bluetape4k.workshop.optimization.planning.adapter.http.CallbackSignatureVerifier
import io.bluetape4k.workshop.optimization.planning.adapter.http.FakeCallbackSignatureVerifier
import io.bluetape4k.workshop.optimization.planning.domain.PlanningEngine
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@Configuration(proxyBeanMethods = false)
internal class PlanningConfiguration {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean(destroyMethod = "")
    fun planningExecutor(): ExecutorService = VirtualThreads.executorService()

    @Bean
    fun planningExecutorShutdown(
        @Qualifier("planningExecutor") executor: ExecutorService,
    ): PlanningExecutorShutdown = PlanningExecutorShutdown(executor)

    @Bean
    @Profile("!timefold & !custom-solver")
    fun planningEngine(): PlanningEngine = DeterministicPlanningEngine()

    @Bean
    @Profile("!timefold & !custom-solver")
    fun callbackSignatureVerifier(): CallbackSignatureVerifier = FakeCallbackSignatureVerifier()
}

internal class PlanningExecutorShutdown(
    private val executor: ExecutorService,
    private val timeout: Duration = Duration.ofSeconds(30),
): AutoCloseable {

    override fun close() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow()
            }
        } catch (interrupted: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
