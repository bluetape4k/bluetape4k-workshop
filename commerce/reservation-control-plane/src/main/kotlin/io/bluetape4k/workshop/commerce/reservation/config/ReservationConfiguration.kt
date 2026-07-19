package io.bluetape4k.workshop.commerce.reservation.config

import io.bluetape4k.concurrent.virtualthread.VirtualThreads
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.reservation.application.ReservationCredentialService
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** Provides the UTC clock, Exposed transaction manager, HMAC service, and Java 25 virtual-thread executor. */
@Configuration(proxyBeanMethods = false)
internal class ReservationConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean("springTransactionManager")
    fun springTransactionManager(dataSource: DataSource): PlatformTransactionManager =
        SpringTransactionManager(dataSource, DatabaseConfig {}, false)

    @Bean
    fun reservationCredentialService(
        @Value("\${reservation.security.hmac-secret}") secret: String,
    ) = ReservationCredentialService(secret)

    @Bean(destroyMethod = "")
    fun reservationExecutor(): ExecutorService =
        VirtualThreads.executorService().also {
            log.info { "reservation_executor_created virtualThreads=true" }
        }

    @Bean
    fun reservationExecutorShutdown(executor: ExecutorService) = ExecutorShutdown(executor)

    companion object : KLogging()
}

/** Gives the virtual-thread executor a bounded graceful shutdown before forcing cancellation. */
internal class ExecutorShutdown(
    private val executor: ExecutorService,
    private val timeout: Duration = Duration.ofSeconds(10),
) : AutoCloseable {
    override fun close() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn { "reservation_executor_forced_shutdown timeoutMillis=${timeout.toMillis()}" }
                executor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        log.info { "reservation_executor_shutdown" }
    }

    companion object : KLogging()
}
