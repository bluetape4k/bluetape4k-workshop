package io.bluetape4k.workshop.optimization.planning.application

import io.bluetape4k.workshop.optimization.planning.domain.AggregateId
import io.bluetape4k.workshop.optimization.planning.domain.AggregateVersion
import io.bluetape4k.workshop.optimization.planning.domain.DatasetId
import io.bluetape4k.workshop.optimization.planning.domain.PlanningEngine
import io.bluetape4k.workshop.optimization.planning.domain.PlanningRevision
import io.bluetape4k.workshop.optimization.planning.domain.PlanningSubmission
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.optimization.planning.observability.PlanningObservations
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningOutboxRecord
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningOutboxRepository
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningOutboxStatus
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningRequestRecord
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningRequestRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

@Service
internal class PlanningOutboxWorker(
    private val outboxRepository: PlanningOutboxRepository,
    private val requestRepository: PlanningRequestRepository,
    private val engine: PlanningEngine,
    private val transactionTemplate: TransactionTemplate,
    @Qualifier("planningExecutor") private val executor: ExecutorService,
    private val clock: Clock,
    private val observations: PlanningObservations,
) {

    fun processDue(batchSize: Int = DEFAULT_BATCH_SIZE): List<Future<PlanningOutboxStatus>> {
        val now = Instant.now(clock)
        val claimed = transactionTemplate.execute {
            outboxRepository.claimNextBatch(WORKER_ID, batchSize, now, LEASE_DURATION)
        }.orEmpty()
        return claimed.map { outbox -> executor.submit<PlanningOutboxStatus> { process(outbox) } }
    }

    private fun process(outbox: PlanningOutboxRecord): PlanningOutboxStatus {
        return try {
            val request = transactionTemplate.execute {
                requestRepository.findById(outbox.planningRequestId)
            }
            val submission = observations.observeProviderSubmission(request.provider) {
                engine.submit(request.toSubmission())
            }
            transactionTemplate.execute {
                check(requestRepository.markSubmitted(request.id, submission.providerRequestId.value)) {
                    "planning request submission state was not updated"
                }
                check(outboxRepository.markCompleted(outbox.planningRequestId, WORKER_ID, Instant.now(clock))) {
                    "planning outbox lease was lost"
                }
            }
            PlanningOutboxStatus.COMPLETED.also(observations::recordOutbox)
        } catch (failure: Exception) {
            log.warn {
                "Planning submission failed; requestId=${outbox.planningRequestId}, failureType=${failure.javaClass.simpleName}"
            }
            transactionTemplate.execute {
                outboxRepository.markFailure(
                    planningRequestId = outbox.planningRequestId,
                    workerId = WORKER_ID,
                    now = Instant.now(clock),
                    retryDelay = RETRY_DELAY,
                    maxRetries = MAX_RETRIES,
                    errorCode = failure.javaClass.simpleName,
                    errorSummary = failure.message ?: "provider submission failed",
                )
            }.also(observations::recordOutbox)
        }
    }

    private fun PlanningRequestRecord.toSubmission() = PlanningSubmission(
        requestId = id,
        datasetId = DatasetId(datasetId),
        aggregate = AggregateVersion(AggregateId(aggregateId), aggregateVersion),
        parentRevision = parentRevision?.let(::PlanningRevision),
    )

    companion object: KLogging() {
        private const val WORKER_ID = "planning-worker"
        private const val DEFAULT_BATCH_SIZE = 16
        private const val MAX_RETRIES = 3
        private val LEASE_DURATION = Duration.ofSeconds(30)
        private val RETRY_DELAY = Duration.ofSeconds(5)
    }
}
