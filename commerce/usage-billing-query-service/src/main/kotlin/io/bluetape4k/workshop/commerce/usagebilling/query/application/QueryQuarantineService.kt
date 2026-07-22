package io.bluetape4k.workshop.commerce.usagebilling.query.application

import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryQuarantineEvent
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryRecoveryJournal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryQuarantineService(
    private val journal: QueryRecoveryJournal,
) {
    @Transactional
    fun record(event: QueryQuarantineEvent) {
        journal.quarantine(event)
    }
}
