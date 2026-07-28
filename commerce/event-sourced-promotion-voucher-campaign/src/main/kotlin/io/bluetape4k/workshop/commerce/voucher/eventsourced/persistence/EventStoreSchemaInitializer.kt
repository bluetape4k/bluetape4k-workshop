package io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence

import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/**
 * append-only event authority를 위한 검토된 PostgreSQL DDL입니다.
 * repository read/write는 Exposed JDBC operation으로 유지합니다. 이 allowlist는
 * schema ownership과 database-enforced mutation guard로 의도적으로 제한합니다.
 */
internal object EventStoreSchemaInitializer {
    private val statements =
        listOf(
            "CREATE FUNCTION voucher_reject_event_log_mutation() RETURNS trigger " +
                "LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''voucher event log is append-only''; END;'",
            "CREATE TRIGGER voucher_event_log_immutable BEFORE UPDATE OR DELETE ON voucher_event_log " +
                "FOR EACH ROW EXECUTE FUNCTION voucher_reject_event_log_mutation()",
            "REVOKE UPDATE, DELETE, TRUNCATE ON voucher_event_log FROM PUBLIC",
            "GRANT SELECT, INSERT ON voucher_event_log TO voucher_application",
        )

    fun sqlStatements(): List<String> = statements.toList()

    fun applyInCurrentTransaction() {
        val transaction = TransactionManager.current()
        statements.forEach(transaction::exec)
    }
}
