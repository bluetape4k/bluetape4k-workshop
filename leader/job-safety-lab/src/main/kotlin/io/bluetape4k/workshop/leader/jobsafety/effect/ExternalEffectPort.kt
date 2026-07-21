package io.bluetape4k.workshop.leader.jobsafety.effect

import io.bluetape4k.workshop.leader.jobsafety.domain.ExternalEffectResult
import io.bluetape4k.workshop.leader.jobsafety.domain.OperationId

/** Idempotent provider boundary addressed exclusively by a stable [OperationId]. */
interface ExternalEffectPort {
    val providerName: String

    fun execute(operationId: OperationId): ExternalEffectResult

    fun query(operationId: OperationId): ExternalEffectResult
}
