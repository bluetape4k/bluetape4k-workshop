package io.bluetape4k.workshop.aws.kinesis

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/** `run-demo`가 켜진 경우에만 local/real-aws lifecycle을 한 번 실행합니다. */
@Component
class KinesisDemoRunner(
    private val properties: KinesisWorkshopProperties,
    private val service: KinesisStreamService,
    private val demoScope: KinesisDemoScope,
    private val healthIndicator: KinesisWorkshopHealthIndicator,
    private val metrics: KinesisWorkshopMetrics,
) : ApplicationRunner {

    private val lastResult = AtomicReference<KinesisDemoResult?>()

    val result: KinesisDemoResult?
        get() = lastResult.get()

    override fun run(args: ApplicationArguments) {
        if (!properties.runDemo) return

        val job = demoScope.launchDemo {
            val demoResult = runDemo()
            lastResult.set(demoResult)
            log.info {
                "Kinesis demo completed: publishedCount=${demoResult.publishedCount}, " +
                    "consumedCount=${demoResult.consumedCount}, " +
                    "sequenceCount=${demoResult.sequenceNumbers.size}"
            }
        }
        runBlocking { job.await() }
    }

    /** 테스트와 실행기가 공유하는 create → publish → consume 순서입니다. */
    suspend fun runDemo(): KinesisDemoResult {
        val readiness = service.ensureStream()
        if (readiness.status != KinesisStreamStatus.ACTIVE) {
            healthIndicator.markFailure()
            metrics.incrementFailure(properties.profile, KinesisWorkshopMetrics.OPERATION_PUBLISH)
            error("Kinesis stream did not become active.")
        }
        healthIndicator.markReady()

        val events = (1..DEMO_RECORD_COUNT).map { ordinal ->
            KinesisEvent(
                eventId = "demo-event-$ordinal",
                partitionKey = properties.partitionKey,
                ordinal = ordinal,
                payload = "demo-payload-$ordinal",
            )
        }
        val reports = events.map { event ->
            try {
                service.publish(event).also {
                    metrics.incrementPublish(properties.profile, KinesisWorkshopMetrics.OUTCOME_SUCCESS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                    metrics.incrementFailure(properties.profile, KinesisWorkshopMetrics.OPERATION_PUBLISH)
                    throw e
            }
        }
        val consumed = service.consume().take(DEMO_RECORD_COUNT).toList()
        metrics.incrementConsume(properties.profile, KinesisWorkshopMetrics.OUTCOME_SUCCESS)
        return KinesisDemoResult(
            publishedCount = reports.size,
            consumedCount = consumed.size,
            sequenceNumbers = reports.map { it.sequenceNumber },
        )
    }

    companion object : KLoggingChannel() {
        const val DEMO_RECORD_COUNT: Int = 3
    }
}
