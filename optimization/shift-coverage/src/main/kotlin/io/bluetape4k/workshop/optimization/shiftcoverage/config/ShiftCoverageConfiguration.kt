package io.bluetape4k.workshop.optimization.shiftcoverage.config

import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageExecutorLifecycle
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageIdempotencyPort
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageIdempotencyStore
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageDeliveryQueue
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageOutboxStore
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageOutboxWorker
import io.bluetape4k.workshop.optimization.shiftcoverage.persistence.ShiftCoverageAssignmentStore
import io.bluetape4k.workshop.optimization.shiftcoverage.persistence.ShiftCoverageRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.Clock
import java.util.concurrent.ExecutorService

@Configuration(proxyBeanMethods = false)
internal class ShiftCoverageConfiguration {

    @Bean(destroyMethod = "")
    @Profile("demo")
    fun shiftCoverageVirtualThreadExecutor(): ExecutorService = VirtualThreads.executorService()

    @Bean
    fun shiftCoverageClock(): Clock = Clock.systemUTC()

    @Bean
    fun shiftCoveragePlannerLifecycle(properties: ShiftCoverageProperties): ShiftCoverageExecutorLifecycle =
        ShiftCoverageExecutorLifecycle(
            plannerWorkers = properties.plannerWorkers,
            plannerQueue = properties.plannerQueue,
            drain = properties.shutdownDrain,
        )

    @Bean
    @Profile("demo")
    fun shiftCoverageAssignmentStore(): ShiftCoverageAssignmentStore = ShiftCoverageRepository()

    @Bean
    @Profile("demo")
    fun shiftCoverageIdempotencyStore(): ShiftCoverageIdempotencyPort = ShiftCoverageIdempotencyStore()

    @Bean
    @Profile("demo")
    fun shiftCoverageDeliveryQueue(properties: ShiftCoverageProperties): ShiftCoverageDeliveryQueue =
        ShiftCoverageDeliveryQueue(properties.plannerQueue)

    @Bean
    @Profile("demo")
    fun shiftCoverageOutboxWorker(
        store: ShiftCoverageOutboxStore,
        queue: ShiftCoverageDeliveryQueue,
        properties: ShiftCoverageProperties,
    ): ShiftCoverageOutboxWorker = ShiftCoverageOutboxWorker(store, queue, properties.outboxBatchSize)

    @Bean
    @Profile("demo")
    fun shiftCoverageExecutorShutdown(
        executor: ExecutorService,
        properties: ShiftCoverageProperties,
    ): ShiftCoverageExecutorShutdown = ShiftCoverageExecutorShutdown(executor, properties.shutdownDrain)
}
