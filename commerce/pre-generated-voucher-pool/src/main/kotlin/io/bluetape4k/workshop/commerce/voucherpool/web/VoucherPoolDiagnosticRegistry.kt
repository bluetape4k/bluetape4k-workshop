package io.bluetape4k.workshop.commerce.voucherpool.web

import org.springframework.stereotype.Component
import java.io.Serializable
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

internal data class VoucherPoolDiagnosticRecord(
    val targetRequestId: String,
    val tenantId: String,
    val method: String,
    val path: String,
    val status: Int,
    val elapsedMillis: Long,
    val observedAt: Instant,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

private data class DiagnosticKey(
    val tenantId: String,
    val requestId: String,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

/** short-lived operator diagnostics를 위해 bounded, code-free request metadata만 보존합니다. */
@Component
internal class VoucherPoolDiagnosticRegistry internal constructor(
    private val now: () -> Instant,
    private val retention: Duration,
) {
    constructor() : this(Instant::now, DEFAULT_RETENTION)

    private val records = ConcurrentHashMap<DiagnosticKey, VoucherPoolDiagnosticRecord>()
    private val insertionOrder = ConcurrentLinkedQueue<DiagnosticKey>()

    // bounded registry는 명시적인 transport metadata를 audit observation 하나로 검증합니다.
    @Suppress("LongParameterList")
    fun record(
        requestId: String,
        tenantCandidate: String?,
        method: String,
        path: String,
        status: Int,
        elapsedMillis: Long,
    ) {
        val tenantId = tenantCandidate?.takeIf { SAFE_TENANT.matches(it) } ?: return
        if (!SAFE_REQUEST_ID.matches(requestId) || method !in SAFE_METHODS || !SAFE_PATH.matches(path)) return
        val key = DiagnosticKey(tenantId, requestId)
        val record = VoucherPoolDiagnosticRecord(
            requestId,
            tenantId,
            method,
            path,
            status.coerceIn(MIN_HTTP_STATUS, MAX_HTTP_STATUS),
            elapsedMillis.coerceAtLeast(0),
            now(),
        )
        if (records.put(key, record) == null) insertionOrder.add(key)
        evictOverflow()
    }

    fun find(tenantId: String, requestId: String): VoucherPoolDiagnosticRecord? {
        val validKey = SAFE_TENANT.matches(tenantId) && SAFE_REQUEST_ID.matches(requestId)
        val key = DiagnosticKey(tenantId, requestId)
        val record = key.takeIf { validKey }?.let(records::get)
        val expired = record?.observedAt?.plus(retention)?.let { it <= now() } == true
        if (expired && records.remove(key, record)) insertionOrder.remove(key)
        return record?.takeUnless { expired }
    }

    internal fun queuedKeyCount(): Int = insertionOrder.size

    private fun evictOverflow() {
        while (records.size > MAX_RECORDS) {
            insertionOrder.poll()?.let(records::remove) ?: return
        }
    }

    private companion object {
        const val MAX_RECORDS = 1_024
        const val MIN_HTTP_STATUS = 100
        const val MAX_HTTP_STATUS = 599
        val DEFAULT_RETENTION: Duration = Duration.ofMinutes(15)
        val SAFE_TENANT = Regex("[A-Za-z0-9._:-]{1,64}")
        val SAFE_REQUEST_ID = Regex("[!-~]{1,64}")
        val SAFE_PATH = Regex("/[A-Za-z0-9._~!$&'()*+,;=:@%/-]{0,511}")
        val SAFE_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
    }
}
