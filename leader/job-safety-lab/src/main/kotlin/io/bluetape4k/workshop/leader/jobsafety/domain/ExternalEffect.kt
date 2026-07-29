package io.bluetape4k.workshop.leader.jobsafety.domain

/** fencing token을 소비할 수 없는 effect를 위한 durable outbox lifecycle입니다. */
enum class EffectDeliveryState {
    PENDING,
    CLAIMED,
    RECONCILIATION_REQUIRED,
    CONFIRMED,
    DECLINED,
}

/** delivery, reconciliation, receipt가 공유하는 provider 결과 vocabulary입니다. */
enum class ExternalEffectResult { CONFIRMED, DECLINED, UNKNOWN }
