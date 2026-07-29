package io.bluetape4k.workshop.optimization.planning.config

import io.bluetape4k.workshop.optimization.planning.persistence.PlanningAggregateTable
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningAuditTable
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningCallbackInboxTable
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningOutboxTable
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningRequestTable
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
internal class PlanningDatabaseInitializer: ApplicationRunner {

    @Transactional
    override fun run(args: ApplicationArguments) {
        SchemaUtils.create(
            PlanningAggregateTable,
            PlanningRequestTable,
            PlanningOutboxTable,
            PlanningCallbackInboxTable,
            PlanningAuditTable,
        )
    }
}
