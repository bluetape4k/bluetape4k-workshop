package io.bluetape4k.workshop.commerce.metering.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.commerce.metering.domain.MeterCode
import io.bluetape4k.workshop.commerce.metering.domain.TenantId
import io.bluetape4k.workshop.commerce.metering.persistence.MeterRepository
import io.bluetape4k.workshop.commerce.metering.persistence.MeterInsert
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

data class MeterView(
    val id: UUID,
    val tenantId: TenantId,
    val code: MeterCode,
    val unit: String,
    val description: String?,
)

private const val UNIT_MAX_LENGTH = 32
private const val DESCRIPTION_MAX_LENGTH = 256

@Service
class MeterService(
    private val repository: MeterRepository,
    private val clock: Clock,
) {
    @Transactional
    fun register(tenantId: TenantId, code: MeterCode, unit: String, description: String?): MeterView {
        unit.requireNotBlank("unit")
        unit.length.requireInRange(1, UNIT_MAX_LENGTH, "unit.length")
        description?.length?.requireInRange(0, DESCRIPTION_MAX_LENGTH, "description.length")
        repository.createIfAbsent(
            MeterInsert(Uuid.V7.nextId(), tenantId.value, code.value, unit, description, clock.instant()),
        )
        val meter = requireNotNull(repository.find(tenantId.value, code.value))
        require(meter.unit == unit) { "meter_unit_conflict" }
        return MeterView(meter.id.value, tenantId, code, meter.unit, meter.description)
    }
}
