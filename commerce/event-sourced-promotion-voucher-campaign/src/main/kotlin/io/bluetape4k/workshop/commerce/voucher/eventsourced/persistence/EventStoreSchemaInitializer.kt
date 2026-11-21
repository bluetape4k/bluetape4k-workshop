package io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence

import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/**
 * append-only event authority를 위한 검토된 PostgreSQL DDL입니다.
 * repository read/write는 Exposed JDBC operation으로 유지합니다. 이 allowlist는
 * schema ownership과 database-enforced mutation guard로 의도적으로 제한합니다.
 */
internal object EventStoreSchemaInitializer {
    /**
     * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    private val statements = listOf(
        "CREATE FUNCTION voucher_reject_event_log_mutation() RETURNS trigger " +
                "LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''voucher event log is append-only''; END;'",
        "CREATE TRIGGER voucher_event_log_immutable BEFORE UPDATE OR DELETE ON voucher_event_log " +
                "FOR EACH ROW EXECUTE FUNCTION voucher_reject_event_log_mutation()",
        "REVOKE UPDATE, DELETE, TRUNCATE ON voucher_event_log FROM PUBLIC",
        "GRANT SELECT, INSERT ON voucher_event_log TO voucher_application",
    )

    /**
     * sqlStatements은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun sqlStatements(): List<String> = statements.toList()

    /**
     * applyInCurrentTransaction은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을
     * 가리키도록 처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun applyInCurrentTransaction() {
        val transaction = TransactionManager.current()
        statements.forEach(transaction::exec)
    }
}
