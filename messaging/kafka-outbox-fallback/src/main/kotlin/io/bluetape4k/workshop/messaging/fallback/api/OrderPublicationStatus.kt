package io.bluetape4k.workshop.messaging.fallback.api

/**
 * order placement request 에 대해 caller 에게 노출하는 publication outcome 입니다.
 *
 * REST API 가 internal relay status 를 create-order outcome 으로 노출하지 않도록 fallback table lifecycle state 와 의도적으로 분리했습니다.
 */
enum class OrderPublicationStatus {
    PUBLISHED_DIRECT,
    FALLBACK_STORED,
    FALLBACK_STORE_FAILED,
    UNKNOWN,
}
