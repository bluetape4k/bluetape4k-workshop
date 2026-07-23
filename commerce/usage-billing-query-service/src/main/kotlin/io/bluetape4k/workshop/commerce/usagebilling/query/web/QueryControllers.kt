package io.bluetape4k.workshop.commerce.usagebilling.query.web

import io.bluetape4k.workshop.commerce.usagebilling.query.application.QueryRecoveryService
import io.bluetape4k.workshop.commerce.usagebilling.query.config.QueryMetrics
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryProjectionJournal
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.Serializable
import java.util.UUID

@Component
class QueryTenantAuthorizer {
    fun requireAccess(authentication: Authentication, tenantId: String) {
        val expectedAuthority = "TENANT_$tenantId"
        if (authentication.authorities.none { it.authority == expectedAuthority }) {
            throw AccessDeniedException("tenant_access_denied")
        }
    }
}

data class QueryReadModelSummary(
    val appliedEventCount: Int,
    val checkpoint: Long,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/query")
class QueryReadController(
    private val projections: QueryProjectionJournal,
    private val authorizer: QueryTenantAuthorizer,
) {
    @GetMapping("/summary")
    fun summary(@PathVariable tenantId: String, authentication: Authentication): QueryReadModelSummary {
        authorizer.requireAccess(authentication, tenantId)
        return QueryReadModelSummary(projections.readModelEventIds.size, projections.checkpoint)
    }
}

@RestController
@RequestMapping("/api/v1/operator/query-recovery")
class OperatorRecoveryController(
    private val recovery: QueryRecoveryService,
    private val metrics: QueryMetrics,
) {
    @GetMapping
    fun snapshot() = recovery.snapshot()

    @PostMapping("/quarantine/{eventId}/redrive")
    fun redrive(
        @PathVariable eventId: UUID,
        @RequestHeader("X-Correlation-Id") correlationId: String,
        authentication: Authentication,
    ) = recovery.redrive(eventId, authentication.name, correlationId).also {
        metrics.redrive(if (it.requested) "REQUESTED" else "NOT_FOUND")
    }
}
