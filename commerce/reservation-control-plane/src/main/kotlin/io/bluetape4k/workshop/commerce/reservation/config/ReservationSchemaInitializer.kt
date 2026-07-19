package io.bluetape4k.workshop.commerce.reservation.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.commerce.reservation.persistence.CapacityResourceRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.reservationTables
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** Creates the application-owned Exposed schema and seeds one deterministic demonstration resource. */
@Component
internal class ReservationSchemaInitializer(
    private val resources: CapacityResourceRepository,
) : ApplicationRunner {
    @Transactional
    override fun run(args: ApplicationArguments) {
        SchemaUtils.create(*reservationTables)
        if (resources.snapshots().isEmpty()) {
            resources.create("demo-room-utc", capacity = 1, policyVersion = 1)
        }
        log.info { "reservation_schema_initialized tableCount=${reservationTables.size}" }
    }

    companion object : KLogging()
}
