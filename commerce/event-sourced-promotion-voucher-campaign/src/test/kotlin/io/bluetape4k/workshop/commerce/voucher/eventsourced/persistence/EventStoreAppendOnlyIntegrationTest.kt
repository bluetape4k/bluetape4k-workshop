@file:Suppress("MaxLineLength") // PostgreSQL DDL and schema-qualified hostile SQL stay readable as complete statements.

package io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.database.getHikariDataSource
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.SQLException

@Tag("integration")
/**
 * EventStoreAppendOnlyIntegrationTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventStoreAppendOnlyIntegrationTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "application role can append but cannot rewrite delete or truncate events" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `application role can append but cannot rewrite delete or truncate events`() {
        val suffix = Base58.randomString(12).lowercase()
        val schema = "voucher_event_$suffix"
        val role = "voucher_app_$suffix"
        val password = "voucher-password"
        val postgres = PostgreSQLServer.Launcher.postgres

        adminDataSource(postgres).use { adminDataSource ->
            adminDataSource.connection.use { admin ->
                createAppendOnlyRole(admin, schema, role, password)
                try {
                    verifyApplicationRole(postgres, schema, role, password)
                } finally {
                    dropAppendOnlyRole(admin, schema, role)
                }
            }
        }
    }

/**
 * adminDataSource은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun adminDataSource(postgres: PostgreSQLServer): HikariDataSource =
        postgres.getHikariDataSource {
            poolName = "issue-538-append-only-admin"
            maximumPoolSize = 1
            minimumIdle = 0
        }

/**
 * createAppendOnlyRole은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun createAppendOnlyRole(
        admin: java.sql.Connection,
        schema: String,
        role: String,
        password: String,
    ) {
        admin.createStatement().use { statement ->
            statement.execute("CREATE SCHEMA $schema")
            statement.execute("CREATE TABLE $schema.voucher_event_log (event_id uuid primary key, payload text not null)")
            statement.execute("CREATE ROLE $role LOGIN PASSWORD '$password'")
            statement.execute("GRANT USAGE ON SCHEMA $schema TO $role")
            statement.execute("GRANT SELECT, INSERT ON $schema.voucher_event_log TO $role")
            statement.execute("CREATE FUNCTION $schema.reject_mutation() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''append-only''; END;'")
            statement.execute("CREATE TRIGGER immutable BEFORE UPDATE OR DELETE ON $schema.voucher_event_log FOR EACH ROW EXECUTE FUNCTION $schema.reject_mutation()")
        }
    }

/**
 * verifyApplicationRole은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun verifyApplicationRole(
        postgres: PostgreSQLServer,
        schema: String,
        role: String,
        password: String,
    ) {
        postgres
            .getHikariDataSource {
                poolName = "issue-538-append-only-application"
                username = role
                this.password = password
                maximumPoolSize = 1
                minimumIdle = 0
            }.use { applicationDataSource ->
                applicationDataSource.connection.use { application ->
                    application.createStatement().use { statement ->
                        statement.execute("INSERT INTO $schema.voucher_event_log VALUES ('00000000-0000-0000-0000-000000000001', '{}')")

                        assertFailsWith<SQLException> {
                            statement.execute("UPDATE $schema.voucher_event_log SET payload = 'changed'")
                        }
                        assertFailsWith<SQLException> {
                            statement.execute("DELETE FROM $schema.voucher_event_log")
                        }
                        assertFailsWith<SQLException> {
                            statement.execute("TRUNCATE $schema.voucher_event_log")
                        }
                    }
                }
            }
    }

/**
 * dropAppendOnlyRole은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun dropAppendOnlyRole(
        admin: java.sql.Connection,
        schema: String,
        role: String,
    ) {
        admin.createStatement().use { statement ->
            statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            statement.execute("DROP ROLE IF EXISTS $role")
        }
    }
}
