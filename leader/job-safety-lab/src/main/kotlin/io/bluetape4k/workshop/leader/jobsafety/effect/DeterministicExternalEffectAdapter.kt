package io.bluetape4k.workshop.leader.jobsafety.effect

import io.bluetape4k.workshop.leader.jobsafety.domain.ExternalEffectResult
import io.bluetape4k.workshop.leader.jobsafety.domain.OperationId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class DeterministicEffect { CONFIRMED, DECLINED, APPLIED_BUT_TIMEOUT }

/** 멱등 provider 실행과 애매한 응답을 모델링하는 thread-safe fake입니다. */
class DeterministicExternalEffectAdapter(
    private val beforeExecute: () -> Unit = {},
) : ExternalEffectPort {
    override val providerName: String = "deterministic-provider"
    private val scripts = ConcurrentHashMap<OperationId, DeterministicEffect>()
    private val executeCounts = ConcurrentHashMap<OperationId, AtomicInteger>()
    private val applied = ConcurrentHashMap.newKeySet<OperationId>()

    fun script(operationId: OperationId, effect: DeterministicEffect) {
        scripts[operationId] = effect
    }

    override fun execute(operationId: OperationId): ExternalEffectResult {
        beforeExecute()
        executeCounts.computeIfAbsent(operationId) { AtomicInteger() }.incrementAndGet()
        if (operationId in applied) return ExternalEffectResult.CONFIRMED

        return when (scripts[operationId] ?: DeterministicEffect.CONFIRMED) {
            DeterministicEffect.CONFIRMED -> {
                applied += operationId
                ExternalEffectResult.CONFIRMED
            }

            DeterministicEffect.DECLINED -> ExternalEffectResult.DECLINED
            DeterministicEffect.APPLIED_BUT_TIMEOUT -> {
                applied += operationId
                ExternalEffectResult.UNKNOWN
            }
        }
    }

    override fun query(operationId: OperationId): ExternalEffectResult =
        if (operationId in applied) ExternalEffectResult.CONFIRMED else ExternalEffectResult.UNKNOWN

    fun executeCount(operationId: OperationId): Int = executeCounts[operationId]?.get() ?: 0

    fun applicationCount(operationId: OperationId): Int = if (operationId in applied) 1 else 0
}
