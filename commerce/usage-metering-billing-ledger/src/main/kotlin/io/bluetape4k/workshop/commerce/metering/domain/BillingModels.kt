package io.bluetape4k.workshop.commerce.metering.domain

enum class CommandReceiptStatus {
    IN_PROGRESS,
    SUCCEEDED,
    FAILED,
}

enum class BillingPeriodState {
    OPEN,
    CLOSING,
    FINALIZED,
}

enum class CloseRunState {
    RUNNING,
    FAILED_VALIDATION,
    READY_TO_FINALIZE,
    FINALIZED,
}

enum class LedgerEntryType {
    CHARGE,
    DEBIT_ADJUSTMENT,
    CREDIT_ADJUSTMENT,
}

enum class ReconciliationFindingType {
    UNLEDGERED_USAGE,
    UNLEDGERED_USAGE_AFTER_CUTOFF,
    LEDGER_PRICE_MISMATCH,
    INVOICE_LINE_MISMATCH,
    INVOICE_TOTAL_MISMATCH,
    TENANT_OR_CURRENCY_MISMATCH,
}
