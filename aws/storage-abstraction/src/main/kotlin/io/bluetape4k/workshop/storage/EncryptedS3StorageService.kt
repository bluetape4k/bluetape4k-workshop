package io.bluetape4k.workshop.storage

import io.bluetape4k.aws.s3.createBucket
import io.bluetape4k.aws.s3.existsBucket
import io.bluetape4k.aws.spring.s3.S3BoundedEncryptedReadOperations
import io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionProviderTemplate
import io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionTransferOperations
import io.bluetape4k.aws.spring.s3.S3EncryptedOutputStream
import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * `s3-encrypted-aes`와 `s3-encrypted-rsa` profile용 client-side encryption 저장소입니다.
 *
 * byte API는 upstream bounded read를 사용하고, file API는 ciphertext 전송 stream과
 * 인증 후 destination commit을 사용합니다. 암호화 key는 이 workshop 예제의 JVM
 * 메모리에만 존재하므로 프로세스 재시작 후 기존 object를 읽을 수 없습니다.
 */
@Service
@Profile("s3-encrypted-aes | s3-encrypted-rsa")
class EncryptedS3StorageService(
    private val s3Client: S3Client,
    private val providerTemplate: S3ClientSideEncryptionProviderTemplate,
    private val transferOperations: S3ClientSideEncryptionTransferOperations,
    @Value("\${storage.s3.bucket-name:bluetape4k-workshop-bucket}") private val bucketName: String,
    @Value("\${storage.s3.encrypted.max-ciphertext-bytes:${S3BoundedEncryptedReadOperations.MAX_CIPHERTEXT_BYTES}}")
    private val maxCiphertextBytes: Int,
) : StorageService {

    companion object {
        private const val COPY_BUFFER_SIZE = 8 * 1024
    }

    init {
        require(maxCiphertextBytes in 1..S3BoundedEncryptedReadOperations.MAX_CIPHERTEXT_BYTES) {
            "maxCiphertextBytes must be between 1 and ${S3BoundedEncryptedReadOperations.MAX_CIPHERTEXT_BYTES}"
        }
    }

    override suspend fun upload(key: String, content: ByteArray, contentType: String): String =
        withContext(Dispatchers.IO) {
            val objectKey = storageObjectKey(key)
            val bucket = storageBucketName(bucketName)
            val type = contentType.requireNotBlank("contentType").trim()
            ensureBucketExists(bucket)
            providerTemplate.uploadEncrypted(
                bucket,
                objectKey,
                content,
                type,
                emptyMap(),
                emptyMap(),
            )
            storageObjectUri(bucket, objectKey)
        }

    override suspend fun download(key: String): ByteArray =
        withContext(Dispatchers.IO) {
            val objectKey = storageObjectKey(key)
            val bucket = storageBucketName(bucketName)
            providerTemplate.downloadEncryptedBytesBounded(
                bucket,
                objectKey,
                emptyMap(),
                maxCiphertextBytes,
            )
        }

    /**
     * 평문 source를 암호화 output stream에 chunk 단위로 전달해 저장합니다.
     * source 자체를 ciphertext temporary로 복사하지 않습니다.
     */
    suspend fun uploadFile(key: String, source: Path, contentType: String? = null): String =
        withContext(Dispatchers.IO) {
            val objectKey = storageObjectKey(key)
            require(Files.isRegularFile(source)) { "source must be a regular file: $source" }
            val bucket = storageBucketName(bucketName)
            val type = contentType?.requireNotBlank("contentType")?.trim()
                ?: Files.probeContentType(source)
                ?: "application/octet-stream"
            ensureBucketExists(bucket)

            val encryptedStream = transferOperations.encryptedOutputStream(
                bucket,
                objectKey,
                type,
                emptyMap(),
                emptyMap(),
            )
            try {
                Files.newInputStream(source).use { input ->
                    copyWithCancellation(input, encryptedStream)
                }
                currentCoroutineContext().ensureActive()
                encryptedStream.complete()
                encryptedStream.close()
                storageObjectUri(bucket, objectKey)
            } catch (error: Throwable) {
                cleanupFailedUpload(encryptedStream, bucket, objectKey)
                throw error
            }
        }

    /**
     * ciphertext를 임시 경로에서 인증/복호화한 뒤 upstream bounded destination commit을
     * 사용합니다. 인증 실패 시 기존 destination은 upstream rollback 계약으로 보존됩니다.
     */
    suspend fun downloadFile(key: String, destination: Path) =
        withContext(Dispatchers.IO) {
            val objectKey = storageObjectKey(key)
            val bucket = storageBucketName(bucketName)
            transferOperations.downloadEncryptedFile(
                bucket,
                objectKey,
                destination,
                emptyMap(),
            )
        }

    override suspend fun getUrl(key: String): String =
        storageObjectUri(bucketName, key)

    override suspend fun delete(key: String) {
        withContext(Dispatchers.IO) {
            val objectKey = storageObjectKey(key)
            val bucket = storageBucketName(bucketName)
            try {
                s3Client.deleteObject { request -> request.bucket(bucket).key(objectKey) }
            } catch (_: NoSuchKeyException) {
                // S3 delete is idempotent for a missing key.
            }
        }
    }

    private suspend fun copyWithCancellation(input: InputStream, output: S3EncryptedOutputStream) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) return
            if (read > 0) output.write(buffer, 0, read)
        }
    }

    private suspend fun cleanupFailedUpload(
        encryptedStream: S3EncryptedOutputStream,
        bucket: String,
        objectKey: String,
    ) {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                encryptedStream.close()
            } catch (_: Throwable) {
                // Preserve the original upload/cancellation failure.
            }
            try {
                s3Client.deleteObject { request -> request.bucket(bucket).key(objectKey) }
            } catch (_: Throwable) {
                // Preserve the original upload/cancellation failure.
            }
        }
    }

    private fun ensureBucketExists(bucket: String) {
        if (!s3Client.existsBucket(bucket).getOrThrow()) {
            s3Client.createBucket(bucket)
        }
    }
}
