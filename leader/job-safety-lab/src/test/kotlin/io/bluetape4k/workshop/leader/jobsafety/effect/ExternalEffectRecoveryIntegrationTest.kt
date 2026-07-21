package io.bluetape4k.workshop.leader.jobsafety.effect

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.leader.jobsafety.domain.OperationId
import io.bluetape4k.workshop.leader.jobsafety.domain.EffectDeliveryState
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobSafetyDatabaseFixture
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobSafetyRepositories
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("integration")
internal class ExternalEffectRecoveryIntegrationTest {
    @Test
    fun `restart recovery and duplicate delivery preserve one provider application and receipt`() {
        JobSafetyDatabaseFixture().use { fixture ->
            val operationId = OperationId("effect-restart")
            fixture.seedOutbox(operationId)
            val repositories = JobSafetyRepositories(fixture.executor)
            val provider = DeterministicExternalEffectAdapter()
            provider.script(operationId, DeterministicEffect.APPLIED_BUT_TIMEOUT)

            OutboxEffectWorker(repositories.outbox, repositories.effectReceipt, provider).deliverNext()
            OutboxEffectWorker(repositories.outbox, repositories.effectReceipt, provider).reconcileNext()
            fixture.requeueOutbox(operationId)
            OutboxEffectWorker(repositories.outbox, repositories.effectReceipt, provider).deliverNext()

            provider.applicationCount(operationId) shouldBeEqualTo 1
            repositories.effectReceipt.count(PROVIDER, operationId) shouldBeEqualTo 1L
            repositories.outbox.find(operationId)?.state shouldBeEqualTo EffectDeliveryState.CONFIRMED
        }
    }

    companion object {
        private const val PROVIDER = "deterministic-provider"
    }
}
