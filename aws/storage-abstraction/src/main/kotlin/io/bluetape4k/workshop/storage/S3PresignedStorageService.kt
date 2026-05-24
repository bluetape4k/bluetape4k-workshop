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
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.time.Duration

/**
 * AWS S3-backed [StorageService] with pre-signed URL support for the `s3-presigned` Spring profile.
 *
 * ## Behavior / Contract
 * - All objects are stored in [bucketName] on S3 (or LocalStack in tests).
 * - `upload` puts bytes to S3 and returns the same public S3 URL.
 * - `download` retrieves bytes from S3; throws [NoSuchKeyException] if the key does not exist.
 * - `getUrl` generates a pre-signed GET URL valid for [presignDurationMinutes] minutes (default 15).
 *   The URL contains `X-Amz-Expires` query parameter reflecting the duration in seconds.
 * - `delete` removes the object; silently ignores missing keys.
 * - All blocking `S3Client` / `S3Presigner` calls are wrapped in `withContext(Dispatchers.IO)`.
 * - Active when Spring profile `s3-presigned` is set.
 *
 * ```kotlin
 * // With profile "s3-presigned":
 * val url = storageService.upload("docs/readme.txt", bytes, "text/plain")
 * val presignedUrl = storageService.getUrl("docs/readme.txt")
 * // presignedUrl contains "X-Amz-Expires=900"
 * ```
 */
@Service
@Profile("s3-presigned")
class S3PresignedStorageService(
    private val s3Client: S3Client,
    private val s3Presigner: S3Presigner,
    @Value("\${storage.s3.bucket-name:bluetape4k-workshop-bucket}") private val bucketName: String,
    @Value("\${storage.s3.presign-duration-minutes:15}") private val presignDurationMinutes: Long,
) : StorageService {

    companion object : KLogging()

    override suspend fun upload(key: String, content: ByteArray, contentType: String): String =
        withContext(Dispatchers.IO) {
            ensureBucketExists()
            s3Client.putObject(
                { req -> req.bucket(bucketName).key(key).contentType(contentType).contentLength(content.size.toLong()) },
                RequestBody.fromBytes(content)
            )
            log.debug { "Uploaded [$key] to s3://$bucketName (presigned, $contentType, ${content.size} bytes)" }
            "https://s3.amazonaws.com/$bucketName/$key"
        }

    override suspend fun download(key: String): ByteArray =
        withContext(Dispatchers.IO) {
            log.debug { "Downloading [$key] from s3://$bucketName (presigned)" }
            s3Client.getObject(
                { req -> req.bucket(bucketName).key(key) },
                ResponseTransformer.toBytes()
            ).asByteArray()
        }

    override suspend fun getUrl(key: String): String =
        withContext(Dispatchers.IO) {
            val presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(presignDurationMinutes))
                .getObjectRequest { req -> req.bucket(bucketName).key(key) }
                .build()

            val presignedUrl = s3Presigner.presignGetObject(presignRequest).url().toString()
            log.debug { "Generated presigned URL for [$key] (expires in ${presignDurationMinutes}m)" }
            presignedUrl
        }

    override suspend fun delete(key: String) {
        withContext(Dispatchers.IO) {
            try {
                s3Client.deleteObject { req -> req.bucket(bucketName).key(key) }
                log.debug { "Deleted [$key] from s3://$bucketName (presigned)" }
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
