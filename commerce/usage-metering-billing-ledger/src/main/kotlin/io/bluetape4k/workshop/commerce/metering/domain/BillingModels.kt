package io.bluetape4k.workshop.commerce.metering.domain

/** HTTP command가 중복/재시작을 견디는 durable receipt의 상태입니다. */
/** HTTP command가 중복/재시작을 견디는 durable receipt의 상태입니다. */
enum class CommandReceiptStatus {
    /** lease를 가진 worker가 처리 중이며 아직 결과를 공개하지 않았습니다. */
    /** lease를 가진 worker가 처리 중이며 아직 결과를 공개하지 않았습니다. */
    IN_PROGRESS,
    /** 원래 HTTP 결과와 업무 effect가 commit되었습니다. */
    /** 원래 HTTP 결과와 업무 effect가 commit되었습니다. */
    SUCCEEDED,
    /** 재시도해도 같은 실패를 replay해야 하는 terminal 결과입니다. */
    /** 재시도해도 같은 실패를 replay해야 하는 terminal 결과입니다. */
    FAILED,
}

/** 하나의 billing cutoff가 속한 period의 lifecycle입니다. */
/** 하나의 billing cutoff가 속한 period의 lifecycle입니다. */
enum class BillingPeriodState {
    /** usage를 받고 close를 시작할 수 있습니다. */
    /** usage를 받고 close를 시작할 수 있습니다. */
    OPEN,
    /** cutoff가 고정되었고 close worker가 ledger를 생성합니다. */
    /** cutoff가 고정되었고 close worker가 ledger를 생성합니다. */
    CLOSING,
    /** invoice가 확정되어 period를 다시 열 수 없습니다. */
    /** invoice가 확정되어 period를 다시 열 수 없습니다. */
    FINALIZED,
}

/** restartable close worker 실행의 상태입니다. */
/** restartable close worker 실행의 상태입니다. */
enum class CloseRunState {
    /** checkpoint부터 다음 usage batch를 처리합니다. */
    /** checkpoint부터 다음 usage batch를 처리합니다. */
    RUNNING,
    /** 가격이 없거나 규칙 위반이 있어 operator repair가 필요합니다. */
    /** 가격이 없거나 규칙 위반이 있어 operator repair가 필요합니다. */
    FAILED_VALIDATION,
    /** 모든 eligible usage가 처리되어 invoice finalize를 기다립니다. */
    /** 모든 eligible usage가 처리되어 invoice finalize를 기다립니다. */
    READY_TO_FINALIZE,
    /** ledger/invoice provenance가 확정되었습니다. */
    /** ledger/invoice provenance가 확정되었습니다. */
    FINALIZED,
}

/** immutable financial ledger에 기록되는 금액의 의미입니다. */
/** immutable financial ledger에 기록되는 금액의 의미입니다. */
enum class LedgerEntryType {
    /** 정상 usage를 가격에 따라 산정한 원장 항목입니다. */
    /** 정상 usage를 가격에 따라 산정한 원장 항목입니다. */
    CHARGE,
    /** 이미 게시된 금액을 추가로 청구하는 append-only 보정입니다. */
    /** 이미 게시된 금액을 추가로 청구하는 append-only 보정입니다. */
    DEBIT_ADJUSTMENT,
    /** 이미 게시된 금액을 돌려주는 append-only 보정입니다. */
    /** 이미 게시된 금액을 돌려주는 append-only 보정입니다. */
    CREDIT_ADJUSTMENT,
}

/** 독립 fact 비교에서 발견한 설명 가능한 불일치 유형입니다. */
/** 독립 fact 비교에서 발견한 설명 가능한 불일치 유형입니다. */
enum class ReconciliationFindingType {
    /** usage fact가 ledger에 연결되지 않았습니다. */
    /** usage fact가 ledger에 연결되지 않았습니다. */
    UNLEDGERED_USAGE,
    /** cutoff 이후 도착한 usage가 별도 adjustment로 처리되지 않았습니다. */
    /** cutoff 이후 도착한 usage가 별도 adjustment로 처리되지 않았습니다. */
    UNLEDGERED_USAGE_AFTER_CUTOFF,
    /** ledger가 선택한 가격 version이 usage evidence와 다릅니다. */
    /** ledger가 선택한 가격 version이 usage evidence와 다릅니다. */
    LEDGER_PRICE_MISMATCH,
    /** invoice line과 ledger entry 연결이 다릅니다. */
    /** invoice line과 ledger entry 연결이 다릅니다. */
    INVOICE_LINE_MISMATCH,
    /** invoice 합계가 연결된 line 합계와 다릅니다. */
    /** invoice 합계가 연결된 line 합계와 다릅니다. */
    INVOICE_TOTAL_MISMATCH,
    /** tenant 또는 currency 경계가 fact 사이에서 일치하지 않습니다. */
    /** tenant 또는 currency 경계가 fact 사이에서 일치하지 않습니다. */
    TENANT_OR_CURRENCY_MISMATCH,
}
