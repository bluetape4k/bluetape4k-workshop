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
 * `s3` Spring profile용 AWS S3 기반 [StorageService] 구현입니다.
 *
 * ## 동작 / 계약
 * - 모든 객체는 S3의 [bucketName]에 저장합니다(테스트에서는 LocalStack).
 * - S3 접근 전에 객체 키가 상대 슬래시 키인지 검증합니다.
 * - `upload`는 바이트를 S3에 넣고 엔드포인트와 무관한 `s3://{bucket}/{key}` URI를 반환합니다.
 * - `download`는 S3에서 바이트를 가져오며, 키가 없으면 [NoSuchKeyException]을 던집니다.
 * - `getUrl`은 `upload`와 같은 엔드포인트 중립 S3 객체 URI를 반환합니다.
 * - `delete`는 객체를 삭제하며, 없는 키는 조용히 무시합니다.
 * - 모든 블로킹 `S3Client` 호출은 `withContext(Dispatchers.IO)`로 감쌉니다.
 * - Spring profile `s3`가 설정되면 활성화됩니다.
 *
 * ```kotlin
 * // profile "s3" 사용 시:
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
