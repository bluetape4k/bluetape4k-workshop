package io.bluetape4k.workshop.storage

import io.bluetape4k.support.requireNotBlank
import java.nio.file.Path

internal fun storageBucketName(bucketName: String): String =
    bucketName.requireNotBlank("bucketName").trim()

internal fun storageObjectKey(key: String): String {
    val normalizedKey = key.requireNotBlank("key").trim()
    val path = Path.of(normalizedKey)
    require(!path.isAbsolute) { "key must be a relative object key: $key" }
    require('\\' !in normalizedKey) { "key must use forward slash separators: $key" }
    require(normalizedKey.split('/').none { it == "." || it == ".." }) {
        "key must not contain path traversal segments: $key"
    }
    return normalizedKey
}

internal fun storageObjectUri(bucketName: String, key: String): String =
    "s3://${storageBucketName(bucketName)}/${storageObjectKey(key)}"
