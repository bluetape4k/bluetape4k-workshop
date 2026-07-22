package io.bluetape4k.workshop.commerce.usagebilling.query.application

import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryApplyResult
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryProjectionJournal
import io.bluetape4k.workshop.commerce.usagebilling.query.config.QueryMetrics
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryInboxService(
    private val journal: QueryProjectionJournal,
    private val metrics: QueryMetrics,
) {
    @Transactional
    fun apply(event: QueryInboxEvent): QueryApplyResult {
        if (journal.hasEvent(event.eventId)) {
            metrics.inboxOutcome("DUPLICATE", event.eventType)
            return QueryApplyResult(applied = false)
        }
        journal.apply(event)
        metrics.inboxOutcome("APPLIED", event.eventType)
        return QueryApplyResult(applied = true)
    }
}
