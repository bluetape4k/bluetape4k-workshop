package io.bluetape4k.workshop.aws.kinesis

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component

/**
 * 명시적인 10초 shutdown 경계입니다.
 *
 * 순서는 app-owned job 취소 → caller-owned collector passive drain → 이후 Spring destroy
 * 단계에서 owned AWS client 정리입니다. collector 자체를 이 component가 취소하지 않습니다.
 */
@Component
class KinesisShutdownConfiguration(
    private val demoScope: KinesisDemoScope,
    private val shutdownTimeout: Duration = SHUTDOWN_TIMEOUT,
) : SmartLifecycle {

    private val timeoutState = AtomicBoolean(false)

    val timedOut: Boolean
        get() = timeoutState.get()

    override fun isAutoStartup(): Boolean = true

    override fun isRunning(): Boolean = running

    override fun start() {
        running = true
    }

    override fun stop() {
        stop(Runnable {})
    }

    override fun stop(callback: Runnable) {
        demoScope.closeAdmission()
        var drained = false
        runBlocking {
            demoScope.cancelAppJobs()
            drained = demoScope.awaitCallerCollectorsEmpty(shutdownTimeout)
            if (!drained) {
                timeoutState.set(true)
                LOGGER.warn("Kinesis shutdown timed out while waiting for caller collectors.")
            }
        }
        running = false
        if (drained) {
            callback.run()
        }
    }

    override fun getPhase(): Int = Integer.MAX_VALUE

    private var running: Boolean = false

    private companion object {
        val SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(10)
        val LOGGER = LoggerFactory.getLogger(KinesisShutdownConfiguration::class.java)
    }
}
