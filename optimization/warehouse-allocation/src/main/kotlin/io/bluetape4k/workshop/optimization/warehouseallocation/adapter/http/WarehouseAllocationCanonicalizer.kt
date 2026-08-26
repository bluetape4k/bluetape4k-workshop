package io.bluetape4k.workshop.optimization.warehouseallocation.adapter.http

import io.bluetape4k.workshop.optimization.warehouseallocation.persistence.WarehouseAllocationCodec
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

internal class WarehouseAllocationCanonicalizer(
    private val codec: WarehouseAllocationCodec = WarehouseAllocationCodec(),
) {
    fun canonicalBytes(body: ByteArray): ByteArray = codec.canonicalBytes(body)
    fun canonical(body: ByteArray): String = canonicalBytes(body).toString(UTF_8)
    fun digest(body: ByteArray): String = codec.digestBytes(body)
    fun digest(value: Any): String = codec.digest(value)
    fun equal(left: String, right: String): Boolean = MessageDigest.isEqual(left.toByteArray(UTF_8), right.toByteArray(UTF_8))
}
