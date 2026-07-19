package io.bluetape4k.workshop.commerce.voucher.admission

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.redis.lettuce.filter.LettuceBloomFilter
import io.bluetape4k.workshop.commerce.voucher.application.RiskSignal
import io.bluetape4k.workshop.commerce.voucher.config.VoucherDegradationState
import io.bluetape4k.workshop.commerce.voucher.config.VoucherDegradedComponent
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class VoucherAdmissionKeyFactory(
    private val version: Int,
    rateKey: ByteArray,
    riskKey: ByteArray,
) {
    private val rateKey = rateKey.copyOf()
    private val riskKey = riskKey.copyOf()

    init {
        require(version > 0) { "version must be positive" }
        require(this.rateKey.size >= MINIMUM_KEY_BYTES) { "rateKey must contain at least 32 bytes" }
        require(this.riskKey.size >= MINIMUM_KEY_BYTES) { "riskKey must contain at least 32 bytes" }
    }

    fun rateKey(
        tenantId: String,
        principalRef: String,
        operation: String,
    ): String = "v$version:${digest(rateKey, RATE_DOMAIN, tenantId, principalRef, operation)}"

    fun riskKey(
        tenantId: String,
        subjectRef: String,
    ): String = "v$version:${digest(riskKey, RISK_DOMAIN, tenantId, subjectRef)}"

    private fun digest(
        key: ByteArray,
        domain: String,
        vararg values: String,
    ): String {
        val mac = Mac.getInstance(HMAC_SHA_256)
        mac.init(SecretKeySpec(key, HMAC_SHA_256))
        mac.update(domain.toByteArray(UTF_8))
        values.forEach { value ->
            mac.update(SEPARATOR)
            mac.update(value.toByteArray(UTF_8))
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal())
    }

    private companion object {
        const val MINIMUM_KEY_BYTES = 32
        const val HMAC_SHA_256 = "HmacSHA256"
        const val RATE_DOMAIN = "voucher-rate-key-v1"
        const val RISK_DOMAIN = "voucher-risk-key-v1"
        val SEPARATOR = byteArrayOf(0)
    }
}

internal interface VoucherRiskBackend {
    fun add(digest: String)

    fun mightContain(digest: String): Boolean
}

internal class LettuceBloomRiskBackend(
    private val bloomFilter: LettuceBloomFilter,
) : VoucherRiskBackend {
    override fun add(digest: String) = bloomFilter.add(digest)

    override fun mightContain(digest: String): Boolean = bloomFilter.contains(digest)
}

/** Treats Bloom positives as advisory review signals and every backend error as UNKNOWN. */
internal class RiskSignalService(
    private val keys: VoucherAdmissionKeyFactory,
    private val backend: VoucherRiskBackend?,
    private val degradation: VoucherDegradationState? = null,
) {
    fun assess(
        tenantId: String,
        subjectRef: String,
    ): RiskSignal {
        val digest = keys.riskKey(tenantId, subjectRef)
        val riskBackend = backend ?: return RiskSignal.UNKNOWN
        return try {
            (if (riskBackend.mightContain(digest)) RiskSignal.REVIEW else RiskSignal.CLEAR).also {
                degradation?.recover(VoucherDegradedComponent.REDIS)
            }
        } catch (failure: Exception) {
            degradation?.degrade(VoucherDegradedComponent.REDIS)
            log.warn { "voucher_risk_unknown backend=REDIS failure=${failure.javaClass.simpleName}" }
            RiskSignal.UNKNOWN
        }
    }

    fun remember(
        tenantId: String,
        subjectRef: String,
    ): Boolean {
        val riskBackend = backend ?: return false
        return try {
            riskBackend.add(keys.riskKey(tenantId, subjectRef)).let {
                degradation?.recover(VoucherDegradedComponent.REDIS)
                true
            }
        } catch (failure: Exception) {
            degradation?.degrade(VoucherDegradedComponent.REDIS)
            log.warn { "voucher_risk_remember_failed backend=REDIS failure=${failure.javaClass.simpleName}" }
            false
        }.also { remembered ->
            log.debug { "voucher_risk_remembered success=$remembered" }
        }
    }

    companion object : KLogging()
}
