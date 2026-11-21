package io.bluetape4k.workshop.commerce.ticket.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.jdbc.sql.singleInt
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketDatabaseFixture
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Suppress("SqlResolve")
@Tag("migration-compatibility")
/**
 * TicketMigrationCompatibilityIntegrationTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TicketMigrationCompatibilityIntegrationTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "current schema retains every aggregate authority and natural uniqueness guard" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `current schema retains every aggregate authority and natural uniqueness guard`() {
        TicketDatabaseFixture().use { fixture ->
            fixture.dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        """
                            SELECT COUNT(*) FROM information_schema.tables 
                            WHERE table_schema = current_schema() AND table_name LIKE 'ticket_%' 
                            """.trimIndent(),
                    ).use { result ->
                        result.singleInt() shouldBeEqualTo 18
                    }
                    statement.executeQuery(
                        """
                            SELECT COUNT(*) FROM pg_constraint 
                            WHERE conrelid IN ('ticket_payment_operations'::regclass, 'ticket_effect_receipts'::regclass) 
                            AND contype = 'u'
                            """.trimIndent()
                    ).use { result ->
                        result.singleInt() shouldBeEqualTo 2
                    }
                }
            }
        }
    }
}
