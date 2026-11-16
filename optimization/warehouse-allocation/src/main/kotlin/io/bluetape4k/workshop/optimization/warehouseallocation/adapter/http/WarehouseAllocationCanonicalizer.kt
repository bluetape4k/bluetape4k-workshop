package io.bluetape4k.workshop.optimization.warehouseallocation.adapter.http

import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.support.toUtf8String
import io.bluetape4k.workshop.optimization.warehouseallocation.persistence.WarehouseAllocationCodec
import java.security.MessageDigest

internal class WarehouseAllocationCanonicalizer(
    private val codec: WarehouseAllocationCodec = WarehouseAllocationCodec(),
) {
    fun canonicalBytes(body: ByteArray): ByteArray = codec.canonicalBytes(body)

    fun canonical(body: ByteArray): String = canonicalBytes(body).toUtf8String()

    fun digest(body: ByteArray): String = codec.digestBytes(body)

    fun digest(value: Any): String = codec.digest(value)

    fun equal(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toUtf8Bytes(), right.toUtf8Bytes())
}
