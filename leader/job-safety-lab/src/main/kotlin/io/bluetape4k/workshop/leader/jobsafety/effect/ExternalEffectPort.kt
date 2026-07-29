package io.bluetape4k.workshop.leader.jobsafety.effect

import io.bluetape4k.workshop.leader.jobsafety.domain.ExternalEffectResult
import io.bluetape4k.workshop.leader.jobsafety.domain.OperationId

/** 안정적인 [OperationId]만으로 접근하는 멱등 provider boundary입니다. */
interface ExternalEffectPort {
    val providerName: String

    fun execute(operationId: OperationId): ExternalEffectResult

    fun query(operationId: OperationId): ExternalEffectResult
}
