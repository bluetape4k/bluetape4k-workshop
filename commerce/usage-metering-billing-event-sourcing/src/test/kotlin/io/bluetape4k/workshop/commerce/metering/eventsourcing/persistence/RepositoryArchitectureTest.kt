package io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence

import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RepositoryArchitectureTest {
    @Test
    fun `every concrete event store repository implements ExposedJdbcRepository`() {
        val repositories = listOf(
            EventStoreRepository::class.java,
            CommandReceiptRepository::class.java,
            SnapshotRepository::class.java,
            ProjectionGenerationRepository::class.java,
            ProjectionCheckpointRepository::class.java,
            BillingReadModelRepository::class.java,
            ProjectionFailureRepository::class.java,
        )
        assertTrue(repositories.all(ExposedJdbcRepository::class.java::isAssignableFrom))
    }
}
