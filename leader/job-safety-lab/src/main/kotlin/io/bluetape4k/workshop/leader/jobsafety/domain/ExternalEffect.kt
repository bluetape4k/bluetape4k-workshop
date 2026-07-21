package io.bluetape4k.workshop.leader.jobsafety.domain

/** Durable outbox lifecycle for an effect that cannot consume fencing tokens. */
enum class EffectDeliveryState {
    PENDING,
    CLAIMED,
    RECONCILIATION_REQUIRED,
    CONFIRMED,
    DECLINED,
}

/** Provider result vocabulary shared by delivery, reconciliation, and receipts. */
enum class ExternalEffectResult { CONFIRMED, DECLINED, UNKNOWN }
