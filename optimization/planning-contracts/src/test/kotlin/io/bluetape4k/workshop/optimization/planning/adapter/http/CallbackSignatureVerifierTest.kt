package io.bluetape4k.workshop.optimization.planning.adapter.http

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.optimization.planning.domain.PlanningProvider
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class CallbackSignatureVerifierTest {

    @Test
    fun `HMAC verifier uses constant contract compatible signatures`() {
        val body = "{\"eventId\":\"event-42\"}".toByteArray(StandardCharsets.UTF_8)
        val verifier = HmacSha256CallbackSignatureVerifier(SECRET)

        verifier.verify(PlanningProvider.TIMEFOLD_PLATFORM, body, signature(body)) shouldBeEqualTo true
        verifier.verify(PlanningProvider.TIMEFOLD_PLATFORM, body, "sha256=${"00".repeat(32)}") shouldBeEqualTo false
        verifier.verify(PlanningProvider.TIMEFOLD_PLATFORM, body, null) shouldBeEqualTo false
    }

    private fun signature(body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SECRET.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return "sha256=" + mac.doFinal(body).joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val SECRET = "workshop-secret"
    }
}
