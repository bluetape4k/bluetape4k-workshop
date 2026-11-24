package io.bluetape4k.workshop.commerce.voucherpool.admission

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class VoucherPoolRedisSignalsTest {

    @Test
    fun `admission key preserves framed digest bytes`() {
        val key = VoucherPoolRedisSignalKeyFactory().admissionKey(
            AdmissionNamespace.RESERVE,
            byteArrayOf(0x01, 0x02, 0x03),
        )

        key shouldBeEqualTo "v1:7c0a94daefea195252543926ebd649f5d66da41517774f9b6c11c19aae16a81c"
    }
}
