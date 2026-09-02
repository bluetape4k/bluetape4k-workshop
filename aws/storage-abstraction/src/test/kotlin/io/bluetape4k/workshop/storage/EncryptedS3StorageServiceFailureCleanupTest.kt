package io.bluetape4k.workshop.storage

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionProviderTemplate
import io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionTransferOperations
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.CopyObjectResponse
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.HeadBucketResponse
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

class EncryptedS3StorageServiceFailureCleanupTest {

    @Test
    fun `upload cancellation preserves cancellation and cleans incomplete object`(
        @TempDir
        temporaryDirectory: Path,
    ) = runSuspendIO {
        val s3Client = mockk<S3Client>()
        val providerTemplate = mockk<S3ClientSideEncryptionProviderTemplate>(relaxed = true)
        val transferOperations = mockk<S3ClientSideEncryptionTransferOperations>()
        val encryptedStream = mockk<io.bluetape4k.aws.spring.s3.S3EncryptedOutputStream>(relaxed = true)
        val cancellation = CancellationException("cancelled by test")
        val source = temporaryDirectory.resolve("source.bin")
        val deletedKeys = mutableListOf<String>()
        Files.write(source, byteArrayOf(1, 2, 3))

        every {
            s3Client.headBucket(any<Consumer<HeadBucketRequest.Builder>>())
        } returns HeadBucketResponse.builder().build()
        every {
            transferOperations.encryptedOutputStream(any(), any(), any(), any(), any())
        } returns encryptedStream
        every { encryptedStream.write(any<ByteArray>(), any(), any()) } answers { throw cancellation }
        every {
            s3Client.deleteObject(any<Consumer<DeleteObjectRequest.Builder>>())
        } answers {
            val consumer = firstArg<Consumer<DeleteObjectRequest.Builder>>()
            val builder = DeleteObjectRequest.builder()
            consumer.accept(builder)
            deletedKeys += requireNotNull(builder.build().key())
            DeleteObjectResponse.builder().build()
        }

        val service = EncryptedS3StorageService(
            s3Client = s3Client,
            providerTemplate = providerTemplate,
            transferOperations = transferOperations,
            bucketName = "bucket",
            maxCiphertextBytes = 64,
        )

        val failure = assertFailsWith<CancellationException> {
            service.uploadFile("cancelled.bin", source, "application/octet-stream")
        }

        failure.message shouldBeEqualTo "cancelled by test"
        verify(exactly = 1) {
            s3Client.deleteObject(any<Consumer<DeleteObjectRequest.Builder>>())
        }
        deletedKeys.single().startsWith(".bluetape4k-cse-staging/").shouldBeTrue()
        deletedKeys.single().endsWith("/cancelled.bin").shouldBeTrue()
        deletedKeys.single().equals("cancelled.bin").shouldBeFalse()
        verify(exactly = 1) { encryptedStream.close() }
    }

    @Test
    fun `upload failure preserves primary error and reports cleanup failure`(
        @TempDir
        temporaryDirectory: Path,
    ) = runSuspendIO {
        val s3Client = mockk<S3Client>()
        val providerTemplate = mockk<S3ClientSideEncryptionProviderTemplate>(relaxed = true)
        val transferOperations = mockk<S3ClientSideEncryptionTransferOperations>()
        val encryptedStream = mockk<io.bluetape4k.aws.spring.s3.S3EncryptedOutputStream>(relaxed = true)
        val primaryError = IOException("upload failed")
        val cleanupError = IllegalStateException("cleanup failed")
        val deleteFailed = AtomicBoolean(false)
        val source = temporaryDirectory.resolve("source.bin")
        val deletedKeys = mutableListOf<String>()
        Files.write(source, byteArrayOf(1, 2, 3))

        every {
            s3Client.headBucket(any<Consumer<HeadBucketRequest.Builder>>())
        } returns HeadBucketResponse.builder().build()
        every {
            transferOperations.encryptedOutputStream(any(), any(), any(), any(), any())
        } returns encryptedStream
        every { encryptedStream.write(any<ByteArray>(), any(), any()) } answers { throw primaryError }
        every {
            s3Client.deleteObject(any<Consumer<DeleteObjectRequest.Builder>>())
        } answers {
            val consumer = firstArg<Consumer<DeleteObjectRequest.Builder>>()
            val builder = DeleteObjectRequest.builder()
            consumer.accept(builder)
            deletedKeys += requireNotNull(builder.build().key())
            deleteFailed.set(true)
            throw cleanupError
        }

        val service = EncryptedS3StorageService(
            s3Client = s3Client,
            providerTemplate = providerTemplate,
            transferOperations = transferOperations,
            bucketName = "bucket",
            maxCiphertextBytes = 64,
        )

        val failure = assertFailsWith<IOException> {
            service.uploadFile("failed.bin", source, "application/octet-stream")
        }

        (failure === primaryError || failure.cause === primaryError).shouldBeTrue()
        failure.message shouldBeEqualTo "upload failed"
        deleteFailed.get().shouldBeTrue()
        verify(exactly = 1) {
            s3Client.deleteObject(any<Consumer<DeleteObjectRequest.Builder>>())
        }
        deletedKeys.single().startsWith(".bluetape4k-cse-staging/").shouldBeTrue()
        deletedKeys.single().endsWith("/failed.bin").shouldBeTrue()
        deletedKeys.single().equals("failed.bin").shouldBeFalse()
        failure.suppressed.map { it::class.java.name + ":" + it.message } shouldBeEqualTo
            listOf(cleanupError::class.java.name + ":" + cleanupError.message)
        verify(exactly = 1) { encryptedStream.close() }
    }

    @Test
    fun `successful file upload promotes staging object and deletes only staging object`(
        @TempDir
        temporaryDirectory: Path,
    ) = runSuspendIO {
        val s3Client = mockk<S3Client>()
        val providerTemplate = mockk<S3ClientSideEncryptionProviderTemplate>(relaxed = true)
        val transferOperations = mockk<S3ClientSideEncryptionTransferOperations>()
        val encryptedStream = mockk<io.bluetape4k.aws.spring.s3.S3EncryptedOutputStream>(relaxed = true)
        val uploadedKeys = mutableListOf<String>()
        val uploadedKey = slot<String>()
        val copiedRequests = mutableListOf<CopyObjectRequest>()
        val deletedKeys = mutableListOf<String>()
        val source = temporaryDirectory.resolve("source.bin")
        Files.write(source, byteArrayOf(1, 2, 3))

        every {
            s3Client.headBucket(any<Consumer<HeadBucketRequest.Builder>>())
        } returns HeadBucketResponse.builder().build()
        every {
            transferOperations.encryptedOutputStream(any(), capture(uploadedKey), any(), any(), any())
        } answers {
            uploadedKeys += uploadedKey.captured
            encryptedStream
        }
        every { encryptedStream.write(any<ByteArray>(), any(), any()) } answers { }
        every {
            s3Client.copyObject(any<Consumer<CopyObjectRequest.Builder>>())
        } answers {
            val consumer = firstArg<Consumer<CopyObjectRequest.Builder>>()
            val builder = CopyObjectRequest.builder()
            consumer.accept(builder)
            copiedRequests += builder.build()
            CopyObjectResponse.builder().build()
        }
        every {
            s3Client.deleteObject(any<Consumer<DeleteObjectRequest.Builder>>())
        } answers {
            val consumer = firstArg<Consumer<DeleteObjectRequest.Builder>>()
            val builder = DeleteObjectRequest.builder()
            consumer.accept(builder)
            deletedKeys += requireNotNull(builder.build().key())
            DeleteObjectResponse.builder().build()
        }

        val service = EncryptedS3StorageService(
            s3Client = s3Client,
            providerTemplate = providerTemplate,
            transferOperations = transferOperations,
            bucketName = "bucket",
            maxCiphertextBytes = 64,
        )

        service.uploadFile("promoted.bin", source, "application/octet-stream") shouldBeEqualTo
            "s3://bucket/promoted.bin"

        val stagingKey = uploadedKeys.single()
        stagingKey.startsWith(".bluetape4k-cse-staging/").shouldBeTrue()
        stagingKey.endsWith("/promoted.bin").shouldBeTrue()
        copiedRequests.single().sourceBucket() shouldBeEqualTo "bucket"
        copiedRequests.single().sourceKey() shouldBeEqualTo stagingKey
        copiedRequests.single().destinationBucket() shouldBeEqualTo "bucket"
        copiedRequests.single().destinationKey() shouldBeEqualTo "promoted.bin"
        deletedKeys shouldBeEqualTo listOf(stagingKey)
        verify(exactly = 1) { s3Client.copyObject(any<Consumer<CopyObjectRequest.Builder>>()) }
        verify(exactly = 1) { encryptedStream.close() }
    }

    @Test
    fun `successful promotion keeps canonical result when staging cleanup fails`(
        @TempDir
        temporaryDirectory: Path,
    ) = runSuspendIO {
        val s3Client = mockk<S3Client>()
        val providerTemplate = mockk<S3ClientSideEncryptionProviderTemplate>(relaxed = true)
        val transferOperations = mockk<S3ClientSideEncryptionTransferOperations>()
        val encryptedStream = mockk<io.bluetape4k.aws.spring.s3.S3EncryptedOutputStream>(relaxed = true)
        val source = temporaryDirectory.resolve("source.bin")
        val cleanupError = IllegalStateException("staging cleanup failed")
        Files.write(source, byteArrayOf(1, 2, 3))

        every {
            s3Client.headBucket(any<Consumer<HeadBucketRequest.Builder>>())
        } returns HeadBucketResponse.builder().build()
        every {
            transferOperations.encryptedOutputStream(any(), any(), any(), any(), any())
        } returns encryptedStream
        every { encryptedStream.write(any<ByteArray>(), any(), any()) } answers { }
        every {
            s3Client.copyObject(any<Consumer<CopyObjectRequest.Builder>>())
        } returns CopyObjectResponse.builder().build()
        every {
            s3Client.deleteObject(any<Consumer<DeleteObjectRequest.Builder>>())
        } throws cleanupError

        val service = EncryptedS3StorageService(
            s3Client = s3Client,
            providerTemplate = providerTemplate,
            transferOperations = transferOperations,
            bucketName = "bucket",
            maxCiphertextBytes = 64,
        )

        service.uploadFile("committed.bin", source, "application/octet-stream") shouldBeEqualTo
            "s3://bucket/committed.bin"

        verify(exactly = 1) { s3Client.copyObject(any<Consumer<CopyObjectRequest.Builder>>()) }
        verify(exactly = 1) { s3Client.deleteObject(any<Consumer<DeleteObjectRequest.Builder>>()) }
        verify(exactly = 1) { encryptedStream.close() }
    }
}
