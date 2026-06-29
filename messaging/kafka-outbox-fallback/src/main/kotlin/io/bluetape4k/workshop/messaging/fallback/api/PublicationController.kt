package io.bluetape4k.workshop.messaging.fallback.api

import io.bluetape4k.workshop.messaging.fallback.config.FallbackOutboxProperties
import io.bluetape4k.workshop.messaging.fallback.publication.EventPublicationRelay
import io.bluetape4k.workshop.messaging.fallback.publication.PublicationReconciler
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Demo publication inspection and opt-in admin actions.
 */
@RestController
@RequestMapping("/api/publications")
class PublicationController(
    private val publicationQueryService: PublicationQueryService,
    private val eventPublicationRelay: EventPublicationRelay,
    private val publicationReconciler: PublicationReconciler,
    private val properties: FallbackOutboxProperties,
) {

    @GetMapping
    fun listPublications(): List<PublicationResponse> =
        publicationQueryService.findAll()

    @PostMapping("/relay")
    fun relay(): ResponseEntity<AdminActionResponse> {
        if (!properties.demoAdminEndpointsEnabled) {
            return ResponseEntity.notFound().build()
        }

        val result = eventPublicationRelay.relayOnce()
        return ResponseEntity.ok(
            AdminActionResponse(
                action = "relay",
                claimed = result.claimed,
                published = result.published,
                failed = result.failed,
                deadLettered = result.deadLettered,
            ),
        )
    }

    @PostMapping("/reconcile")
    fun reconcile(): ResponseEntity<AdminActionResponse> {
        if (!properties.demoAdminEndpointsEnabled) {
            return ResponseEntity.notFound().build()
        }

        val result = publicationReconciler.reconcileOnce()
        return ResponseEntity.ok(
            AdminActionResponse(
                action = "reconcile",
                scanned = result.scanned,
                reconstructed = result.reconstructed,
            ),
        )
    }
}
