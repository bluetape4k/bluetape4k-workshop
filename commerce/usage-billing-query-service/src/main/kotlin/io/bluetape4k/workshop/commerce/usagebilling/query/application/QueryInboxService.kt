package io.bluetape4k.workshop.commerce.usagebilling.query.application

import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryApplyResult
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryProjectionJournal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryInboxService(
    private val journal: QueryProjectionJournal,
) {
    @Transactional
    fun apply(event: QueryInboxEvent): QueryApplyResult {
        if (journal.hasEvent(event.eventId)) return QueryApplyResult(applied = false)
        journal.apply(event)
        return QueryApplyResult(applied = true)
    }
}
