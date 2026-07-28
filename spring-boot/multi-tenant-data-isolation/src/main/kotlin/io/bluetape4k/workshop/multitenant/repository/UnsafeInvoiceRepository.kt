package io.bluetape4k.workshop.multitenant.repository

import io.bluetape4k.workshop.multitenant.domain.InvoiceRecord
import io.bluetape4k.workshop.multitenant.domain.InvoiceTable
import io.bluetape4k.workshop.multitenant.domain.toInvoiceRecord
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository

/**
 * tenant predicate 를 의도적으로 생략하는 baseline repository 입니다.
 *
 * 이 class 는 leakage risk 를 테스트에서 실행 가능한 형태로 보여주기 위해서만 존재합니다.
 */
@Repository
class UnsafeInvoiceRepository {

    /**
     * invoice ID 만으로 조회하여 다른 tenant 의 caller 가 row 를 읽을 수 있게 합니다.
     */
    fun findByIdWithoutTenant(invoiceId: Long): InvoiceRecord? =
        InvoiceTable
            .selectAll()
            .where { InvoiceTable.id eq invoiceId }
            .firstOrNull()
            ?.toInvoiceRecord()
}
