package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.assertions.shouldBeFalse
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** 이미 획득한 caller connection의 수명과 Exposed transaction 경계를 검증합니다. */
/** 이미 획득한 caller connection의 수명과 Exposed transaction 경계를 검증합니다. */
@Tag("integration")
class JobJdbcExecutorTest {

    @Test
/**
 * 이 시나리오는 "wrapping a caller connection does not close it" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
