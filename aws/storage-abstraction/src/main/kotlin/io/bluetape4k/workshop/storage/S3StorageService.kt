package io.bluetape4k.workshop.storage

import io.bluetape4k.aws.s3.createBucket
import io.bluetape4k.aws.s3.existsBucket
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.NoSuchKeyException

/**
 * AWS S3-backed [StorageService] implementation for the `s3` Spring profile.
 *
 * ## Behavior / Contract
 * - All objects are stored in [bucketName] on S3 (or LocalStack in tests).
 * - Object keys are validated as relative forward-slash keys before S3 access.
 * - `upload` puts bytes to S3 and returns an endpoint-neutral `s3://{bucket}/{key}` URI.
 * - `download` retrieves bytes from S3; throws [NoSuchKeyException] if the key does not exist.
 * - `getUrl` returns the same endpoint-neutral S3 object URI as `upload`.
 * - `delete` removes the object; silently ignores missing keys.
 * - All blocking `S3Client` calls are wrapped in `withContext(Dispatchers.IO)`.
 * - Active when Spring profile `s3` is set.
 *
 * ```kotlin
 * // With profile "s3":
 * val url = storageService.upload("docs/readme.txt", bytes, "text/plain")
 * val data = storageService.download("docs/readme.txt")
 * storageService.delete("docs/readme.txt")
 * ```
 */
@Service
@Profile("s3")
class S3StorageService(
    private val s3Client: S3Client,
    @Value("\${storage.s3.bucket-name:bluetape4k-workshop-bucket}") private val bucketName: String,
) : StorageService {

    companion object : KLogging()

    override suspend fun upload(key: String, content: ByteArray, contentType: String): String =
        withContext(Dispatchers.IO) {
            val objectKey = storageObjectKey(key)
            val bucket = storageBucketName(bucketName)
            val type = contentType.requireNotBlank("contentType").trim()
            ensureBucketExists(bucket)
            s3Client.putObject(
                { req -> req.bucket(bucket).key(objectKey).contentType(type).contentLength(content.size.toLong()) },
                RequestBody.fromBytes(content)
            )
            log.debug { "Uploaded [$objectKey] to s3://$bucket ($type, ${content.size} bytes)" }
            storageObjectUri(bucket, objectKey)
        }

    override suspend fun download(key: String): ByteArray =
        withContext(Dispatchers.IO) {
            val objectKey = storageObjectKey(key)
            val bucket = storageBucketName(bucketName)
            log.debug { "Downloading [$objectKey] from s3://$bucket" }
            s3Client.getObject(
                { req -> req.bucket(bucket).key(objectKey) },
                ResponseTransformer.toBytes()
            ).asByteArray()
        }

    override suspend fun getUrl(key: String): String =
        storageObjectUri(bucketName, key)

    override suspend fun delete(key: String) {
        withContext(Dispatchers.IO) {
            val objectKey = storageObjectKey(key)
            val bucket = storageBucketName(bucketName)
            try {
                s3Client.deleteObject { req -> req.bucket(bucket).key(objectKey) }
                log.debug { "Deleted [$objectKey] from s3://$bucket" }
            } catch (e: NoSuchKeyException) {
                log.debug { "Delete [$objectKey]: key not found, ignoring" }
            }
        }
    }

    private fun ensureBucketExists(bucket: String) {
        if (!s3Client.existsBucket(bucket).getOrThrow()) {
            s3Client.createBucket(bucket)
            log.debug { "Created bucket: $bucket" }
        }
    }
}
