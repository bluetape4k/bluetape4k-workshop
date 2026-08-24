package io.bluetape4k.workshop.optimization.warehouseallocation.adapter.http

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
internal class WarehouseAllocationSignatureVerifier(
    @Value("\${warehouse-allocation.hmac.enabled:false}") private val enabled: Boolean,
    @Value("\${warehouse-allocation.hmac.secret:}") private val secret: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun verifyFake(signature: String?): Boolean = signature == "fake"

    fun verify(
        method: String,
        path: String,
        body: String,
        timestamp: String,
        provider: String,
        requestId: String,
        planId: String,
        generation: Long,
        signature: String?,
    ): Boolean {
        if (!enabled || secret.isBlank() || signature.isNullOrBlank()) return false
        val parsed = timestamp.toLongOrNull() ?: return false
        if (kotlin.math.abs(clock.instant().epochSecond - parsed) > 300) return false
        val message = listOf(method, path, "warehouse-canonical-v1", body, timestamp, provider, requestId, planId, generation).joinToString("\n")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(UTF_8), "HmacSHA256"))
        val expected = mac.doFinal(message.toByteArray(UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return MessageDigest.isEqual(expected.toByteArray(UTF_8), signature.toByteArray(UTF_8))
    }
}
