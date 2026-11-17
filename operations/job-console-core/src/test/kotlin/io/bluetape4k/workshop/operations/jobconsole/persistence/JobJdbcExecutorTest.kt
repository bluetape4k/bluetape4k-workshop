package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.assertions.shouldBeFalse
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** 이미 획득한 caller connection의 수명과 Exposed transaction 경계를 검증합니다. */
@Tag("integration")
class JobJdbcExecutorTest {

    @Test
    fun `wrapping a caller connection does not close it`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            fixture.dataSource.connection.use { connection ->
                JobJdbcExecutor(fixture.dataSource).use { executor ->
                    executor.transaction(connection) { true }
                }
                connection.isClosed.shouldBeFalse()
            }
        }
    }
}
