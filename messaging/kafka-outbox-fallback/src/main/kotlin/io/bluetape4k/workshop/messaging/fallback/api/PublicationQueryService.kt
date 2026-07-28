package io.bluetape4k.workshop.messaging.fallback.api

import io.bluetape4k.workshop.messaging.fallback.publication.EventPublicationRepository
import org.springframework.stereotype.Service

/**
 * demo inspection 을 위해 payload 를 제외한 publication state 를 제공합니다.
 */
@Service
class PublicationQueryService(
    private val eventPublicationRepository: EventPublicationRepository,
) {

    fun findAll(): List<PublicationResponse> =
        eventPublicationRepository.findAll().map(PublicationResponse::from)
}
