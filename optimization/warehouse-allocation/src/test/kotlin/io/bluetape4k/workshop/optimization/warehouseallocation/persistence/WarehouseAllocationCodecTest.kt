package io.bluetape4k.workshop.optimization.warehouseallocation.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.support.toUtf8String
import org.junit.jupiter.api.Test

class WarehouseAllocationCodecTest {

    companion object: KLogging()

    private val codec = WarehouseAllocationCodec()

    @Test
    fun `canonical bytes preserve NFC golden representation and digest`() {
        val body = "{\"b\":1.00,\"a\":[true,null,\"e\\u0301\"],\"z\":-0.0}".toUtf8Bytes()

        codec.canonicalBytes(body).toUtf8String() shouldBeEqualTo "{\"a\":[true, null, \"é\"], \"b\":1, \"z\":0}"

        codec.digestBytes(body) shouldBeEqualTo "e309eacc795d011e0c919e93906394dee9f7a412106a8c74f3a47d0b3293ca8b"
    }

    @Test
    fun `canonical bytes reject duplicate keys and trailing tokens`() {
        assertFailsWith<Exception> {
            codec.canonicalBytes("{\"x\":1,\"x\":2}".toUtf8Bytes())
        }
        assertFailsWith<Exception> {
            codec.canonicalBytes("{} {}".toUtf8Bytes())
        }
    }
}
