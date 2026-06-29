package io.bluetape4k.workshop.messaging.fallback.api

import io.bluetape4k.workshop.messaging.fallback.publication.EventPublicationRepository
import org.springframework.stereotype.Service

/**
 * Provides payload-free publication state for demo inspection.
 */
@Service
class PublicationQueryService(
    private val eventPublicationRepository: EventPublicationRepository,
) {

    fun findAll(): List<PublicationResponse> =
        eventPublicationRepository.findAll().map(PublicationResponse::from)
}
