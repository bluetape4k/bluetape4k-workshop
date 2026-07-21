package io.bluetape4k.workshop.leader.jobsafety.effect

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.leader.jobsafety.domain.OperationId
import io.bluetape4k.workshop.leader.jobsafety.domain.EffectDeliveryState
import io.bluetape4k.workshop.leader.jobsafety.domain.ExternalEffectResult
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobSafetyDatabaseFixture
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobSafetyRepositories
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

internal class OutboxEffectWorkerTest {
    @Test
    fun `unknown provider response is reconciled with the original operation id`() {
        JobSafetyDatabaseFixture().use { fixture ->
            val operationId = OperationId("effect-unknown")
            fixture.seedOutbox(operationId)
            val repositories = JobSafetyRepositories(fixture.executor)
            val provider = DeterministicExternalEffectAdapter()
            val worker = OutboxEffectWorker(repositories.outbox, repositories.effectReceipt, provider)
            provider.script(operationId, DeterministicEffect.APPLIED_BUT_TIMEOUT)

            worker.deliverNext() shouldBeEqualTo EffectWorkResult.RECONCILIATION_REQUIRED
            repositories.outbox.find(operationId)?.state shouldBeEqualTo EffectDeliveryState.RECONCILIATION_REQUIRED

            worker.reconcileNext() shouldBeEqualTo EffectWorkResult.CONFIRMED
            provider.executeCount(operationId) shouldBeEqualTo 1
            provider.applicationCount(operationId) shouldBeEqualTo 1
            repositories.effectReceipt.find(PROVIDER, operationId)?.result shouldBeEqualTo ExternalEffectResult.CONFIRMED
        }
    }

    @Test
    fun `provider call occurs after the claim transaction closes`() {
        JobSafetyDatabaseFixture().use { fixture ->
            val operationId = OperationId("effect-outside-transaction")
            fixture.seedOutbox(operationId)
            val repositories = JobSafetyRepositories(fixture.executor)
            val provider =
                DeterministicExternalEffectAdapter(
                    beforeExecute = {
                        repositories.outbox.find(operationId)?.state shouldBeEqualTo EffectDeliveryState.CLAIMED
                    },
                )
            val worker = OutboxEffectWorker(repositories.outbox, repositories.effectReceipt, provider)

            worker.deliverNext() shouldBeEqualTo EffectWorkResult.CONFIRMED
        }
    }

    @Test
    fun `expired claim is queried instead of executing the provider again`() {
        JobSafetyDatabaseFixture().use { fixture ->
            val operationId = OperationId("effect-expired-claim")
            val claimedAt = Instant.parse("2026-07-22T00:00:00Z")
            fixture.seedOutbox(operationId)
            val repositories = JobSafetyRepositories(fixture.executor)
            val provider = DeterministicExternalEffectAdapter()
            provider.script(operationId, DeterministicEffect.APPLIED_BUT_TIMEOUT)

            repositories.outbox.claimNext(
                state = EffectDeliveryState.PENDING,
                now = claimedAt,
                claimTimeout = Duration.ofSeconds(5),
            )
            provider.execute(operationId)

            val restartedWorker =
                OutboxEffectWorker(
                    outbox = repositories.outbox,
                    receipts = repositories.effectReceipt,
                    provider = provider,
                    clock = { claimedAt.plusSeconds(6) },
                )

            restartedWorker.reconcileNext() shouldBeEqualTo EffectWorkResult.CONFIRMED
            provider.executeCount(operationId) shouldBeEqualTo 1
            provider.applicationCount(operationId) shouldBeEqualTo 1
            repositories.effectReceipt.count(PROVIDER, operationId) shouldBeEqualTo 1L
        }
    }

    companion object {
        private const val PROVIDER = "deterministic-provider"
    }
}
