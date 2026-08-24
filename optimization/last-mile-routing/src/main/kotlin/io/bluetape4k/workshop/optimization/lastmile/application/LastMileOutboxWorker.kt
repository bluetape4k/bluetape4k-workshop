package io.bluetape4k.workshop.optimization.lastmile.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileOutboxRecord
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileOutboxRepository
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

internal enum class LastMileOutboxProcessStatus {
    COMPLETED,
    RETRYABLE,
    DEAD_LETTER,
    SKIPPED,
}

/** lease/fence를 가진 outbox replay worker입니다. raw provider payload는 재시도하지 않습니다. */
@Service
internal class LastMileOutboxWorker(
    private val outboxRepository: LastMileOutboxRepository,
    private val replanService: LastMileReplanService,
    @Qualifier("lastMileExecutor") private val executor: ExecutorService,
    private val clock: Clock,
) {
    fun processDue(batchSize: Int = DEFAULT_BATCH_SIZE): List<Future<LastMileOutboxProcessStatus>> {
        require(batchSize in 1..MAX_BATCH_SIZE) { "outbox batch size is out of bounds" }
        val claimed = transaction {
            buildList {
                repeat(batchSize) {
                    outboxRepository.claimNext(WORKER_ID, Instant.now(clock), LEASE_DURATION)?.let(::add)
                }
            }
        }
        return claimed.map { record -> executor.submit<LastMileOutboxProcessStatus> { process(record) } }
    }

    fun processNext(): LastMileOutboxProcessStatus {
        val record = transaction {
            outboxRepository.claimNext(WORKER_ID, Instant.now(clock), LEASE_DURATION)
        } ?: return LastMileOutboxProcessStatus.SKIPPED
        return process(record)
    }

    private fun process(record: LastMileOutboxRecord): LastMileOutboxProcessStatus = try {
        when (record.eventType) {
            "ROUTING_SUBMITTED" -> {
                val requestId = record.payload.substringAfter("requestId=", "").substringBefore('|')
                require(requestId.matches(REQUEST_ID_PATTERN)) { "outbox request id is invalid" }
                val submission = replanService.submission(requestId)
                    ?: throw LastMileProviderUnavailableException()
                replanService.poll(submission)
            }

            "ROUTE_COMMITTED" -> Unit
            else -> error("unsupported last-mile outbox event")
        }
        check(transaction { outboxRepository.markCompleted(record.id, WORKER_ID) }) {
            "last-mile outbox lease was lost"
        }
        LastMileOutboxProcessStatus.COMPLETED
    } catch (failure: Exception) {
        log.warn {
            "last-mile outbox replay failed; eventType=${record.eventType}, failure=${failure.javaClass.simpleName}"
        }
        val terminal = record.attempts + 1 >= MAX_ATTEMPTS
        transaction {
            outboxRepository.markFailure(
                id = record.id,
                workerId = WORKER_ID,
                now = Instant.now(clock),
                retryDelay = RETRY_DELAY,
                maxAttempts = MAX_ATTEMPTS,
            )
        }
        if (terminal) LastMileOutboxProcessStatus.DEAD_LETTER else LastMileOutboxProcessStatus.RETRYABLE
    }

    companion object : KLogging() {
        private const val WORKER_ID = "last-mile-outbox"
        private const val DEFAULT_BATCH_SIZE = 16
        private const val MAX_BATCH_SIZE = 64
        private const val MAX_ATTEMPTS = 3
        private val LEASE_DURATION = Duration.ofSeconds(30)
        private val RETRY_DELAY = Duration.ofSeconds(5)
        private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,96}")
    }
}
