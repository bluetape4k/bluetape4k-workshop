package io.bluetape4k.workshop.commerce.usagebilling.query.application

import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryRecoveryJournal
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryRecoverySnapshot
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryRedriveResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class QueryRecoveryService(
    private val journal: QueryRecoveryJournal,
) {
    @Transactional(readOnly = true)
    fun snapshot(): QueryRecoverySnapshot = journal.snapshot()

    @Transactional
    fun redrive(eventId: UUID, actor: String, correlationId: String): QueryRedriveResult =
        QueryRedriveResult(journal.requestRedrive(eventId, actor, correlationId))
}
