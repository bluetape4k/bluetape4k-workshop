package io.bluetape4k.workshop.storage

import io.bluetape4k.aws.s3.createBucket
import io.bluetape4k.aws.s3.existsBucket
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
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
 * - Object keys are validated as relative forward-slash keys before S3 access.
 * - `upload` puts bytes to S3 and returns an endpoint-neutral `s3://{bucket}/{key}` URI.
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
            val objectKey = storageObjectKey(key)
            val bucket = storageBucketName(bucketName)
            val type = contentType.requireNotBlank("contentType").trim()
            ensureBucketExists(bucket)
            s3Client.putObject(
                { req -> req.bucket(bucket).key(objectKey).contentType(type).contentLength(content.size.toLong()) },
                RequestBody.fromBytes(content)
            )
            log.debug { "Uploaded [$objectKey] to s3://$bucket (presigned, $type, ${content.size} bytes)" }
            storageObjectUri(bucket, objectKey)
        }

    override suspend fun download(key: String): ByteArray =
        withContext(Dispatchers.IO) {
            val objectKey = storageObjectKey(key)
            val bucket = storageBucketName(bucketName)
            log.debug { "Downloading [$objectKey] from s3://$bucket (presigned)" }
            s3Client.getObject(
                { req -> req.bucket(bucket).key(objectKey) },
                ResponseTransformer.toBytes()
            ).asByteArray()
        }

    override suspend fun getUrl(key: String): String =
        withContext(Dispatchers.IO) {
            val objectKey = storageObjectKey(key)
            val bucket = storageBucketName(bucketName)
            val durationMinutes = presignDurationMinutes.requirePositiveNumber("presignDurationMinutes")
            val presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(durationMinutes))
                .getObjectRequest { req -> req.bucket(bucket).key(objectKey) }
                .build()

            val presignedUrl = s3Presigner.presignGetObject(presignRequest).url().toString()
            log.debug { "Generated presigned URL for [$objectKey] (expires in ${durationMinutes}m)" }
            presignedUrl
        }

    override suspend fun delete(key: String) {
        withContext(Dispatchers.IO) {
            val objectKey = storageObjectKey(key)
            val bucket = storageBucketName(bucketName)
            try {
                s3Client.deleteObject { req -> req.bucket(bucket).key(objectKey) }
                log.debug { "Deleted [$objectKey] from s3://$bucket (presigned)" }
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
