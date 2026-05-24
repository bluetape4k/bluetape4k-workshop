package io.bluetape4k.workshop.storage

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
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
 * - `upload` puts bytes to S3 and returns a plain `https://s3.amazonaws.com/{bucket}/{key}` URL.
 * - `download` retrieves bytes from S3; throws [NoSuchKeyException] if the key does not exist.
 * - `getUrl` returns the same public S3 URL as `upload`.
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
            ensureBucketExists()
            s3Client.putObject(
                { req -> req.bucket(bucketName).key(key).contentType(contentType).contentLength(content.size.toLong()) },
                RequestBody.fromBytes(content)
            )
            log.debug { "Uploaded [$key] to s3://$bucketName ($contentType, ${content.size} bytes)" }
            "https://s3.amazonaws.com/$bucketName/$key"
        }

    override suspend fun download(key: String): ByteArray =
        withContext(Dispatchers.IO) {
            log.debug { "Downloading [$key] from s3://$bucketName" }
            s3Client.getObject(
                { req -> req.bucket(bucketName).key(key) },
                ResponseTransformer.toBytes()
            ).asByteArray()
        }

    override suspend fun getUrl(key: String): String =
        "https://s3.amazonaws.com/$bucketName/$key"

    override suspend fun delete(key: String) {
        withContext(Dispatchers.IO) {
            try {
                s3Client.deleteObject { req -> req.bucket(bucketName).key(key) }
                log.debug { "Deleted [$key] from s3://$bucketName" }
            } catch (e: NoSuchKeyException) {
                log.debug { "Delete [$key]: key not found, ignoring" }
            }
        }
    }

    private fun ensureBucketExists() {
        val exists = try {
            s3Client.headBucket { it.bucket(bucketName) }
            true
        } catch (e: Exception) {
            false
        }
        if (!exists) {
            s3Client.createBucket { it.bucket(bucketName) }
            log.debug { "Created bucket: $bucketName" }
        }
    }
}
