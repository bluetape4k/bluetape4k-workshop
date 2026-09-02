package io.bluetape4k.workshop.storage

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.aws.spring.s3.S3AesProvider
import io.bluetape4k.aws.spring.s3.ClientSideEncryptionProvider
import io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionProviderTemplate
import io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionTransferTemplate
import io.bluetape4k.aws.spring.s3.S3OutputStream
import io.bluetape4k.aws.spring.s3.S3OutputStreamProvider
import io.bluetape4k.aws.spring.s3.S3Properties
import io.bluetape4k.aws.spring.s3.S3TransferOperations
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.transfer.s3.model.CompletedFileUpload
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.spec.SecretKeySpec

class S3EncryptedOutputStreamTest {

    @Test
    fun `threshold stream uploads ciphertext once and rejects terminal writes`() = runSuspendIO {
        val temporaryDirectory = Files.createTempDirectory("workshop-s3-cse-stream-")
        val operations = mockk<S3TransferOperations>()
        val uploadedCiphertext = AtomicReference<ByteArray>()
        coEvery { operations.uploadFile(any(), any(), any(), any()) } coAnswers {
            uploadedCiphertext.set(Files.readAllBytes(thirdArg()))
            mockk<CompletedFileUpload>(relaxed = true)
        }
        val fixture = encryptedTransferTemplate(operations, temporaryDirectory)
        val plaintext = ByteArray(4 * 1024) { (it % 241).toByte() }
        try {
            val encrypted = fixture.template.encryptedOutputStream(
                bucket = "bucket",
                key = "large.bin",
                contentType = "application/octet-stream",
            )
            encrypted.write(plaintext, 0, plaintext.size - 1)
            encrypted.write(plaintext.last().toInt())
            encrypted.complete()
            encrypted.complete()
            encrypted.close()

            coVerify(exactly = 1) { operations.uploadFile("bucket", "large.bin", any(), any()) }
            uploadedCiphertext.get().contentEquals(plaintext).shouldBeFalse()
            uploadedCiphertext.get().size shouldBeGreaterThan plaintext.size
            Files.list(temporaryDirectory).use { entries ->
                entries.noneMatch(Files::isRegularFile).shouldBeTrue()
            }
            assertFailsWith<IllegalStateException> { encrypted.write(1) }
            assertFailsWith<IllegalStateException> { encrypted.write(byteArrayOf(), 0, 0) }
        } finally {
            fixture.close()
            temporaryDirectory.deleteRecursively()
        }
    }

    @Test
    fun `cancellation preserves original failure and cleans threshold file`() = runSuspendIO {
        val temporaryDirectory = Files.createTempDirectory("workshop-s3-cse-cancel-")
        val operations = mockk<S3TransferOperations>()
        val uploadStarted = CompletableDeferred<Unit>()
        coEvery { operations.uploadFile(any(), any(), any(), any()) } coAnswers {
            uploadStarted.complete(Unit)
            awaitCancellation()
        }
        val fixture = encryptedTransferTemplate(operations, temporaryDirectory)
        try {
            val encrypted = fixture.template.encryptedOutputStream("bucket", "cancelled.bin")
            encrypted.write(ByteArray(4 * 1024) { 7 })

            coroutineScope {
                val completion = async { encrypted.complete() }
                uploadStarted.await()
                completion.cancel(CancellationException("cancelled by test"))
                val cancelled = assertFailsWith<CancellationException> { completion.await() }
                cancelled.message shouldBeEqualTo "cancelled by test"
            }
            coVerify(exactly = 1) { operations.uploadFile("bucket", "cancelled.bin", any(), any()) }
            Files.list(temporaryDirectory).use { entries ->
                entries.noneMatch(Files::isRegularFile).shouldBeTrue()
            }
        } finally {
            fixture.close()
            temporaryDirectory.deleteRecursively()
        }
    }

    @Test
    fun `upload failure is preserved and cleans threshold file`() = runSuspendIO {
        val temporaryDirectory = Files.createTempDirectory("workshop-s3-cse-failure-")
        val operations = mockk<S3TransferOperations>()
        coEvery { operations.uploadFile(any(), any(), any(), any()) } throws IOException("upload failed")
        val fixture = encryptedTransferTemplate(operations, temporaryDirectory)
        try {
            val encrypted = fixture.template.encryptedOutputStream("bucket", "failed.bin")
            encrypted.write(ByteArray(4 * 1024) { 9 })

            val failure = assertFailsWith<IOException> { encrypted.complete() }
            failure.message shouldBeEqualTo "upload failed"
            coVerify(exactly = 1) { operations.uploadFile("bucket", "failed.bin", any(), any()) }
            Files.list(temporaryDirectory).use { entries ->
                entries.noneMatch(Files::isRegularFile).shouldBeTrue()
            }
        } finally {
            fixture.close()
            temporaryDirectory.deleteRecursively()
        }
    }

    private fun encryptedTransferTemplate(
        operations: S3TransferOperations,
        temporaryDirectory: Path,
    ): TransferFixture {
        val client = mockk<S3AsyncClient>(relaxed = true)
        val providerTemplate = S3ClientSideEncryptionProviderTemplate(
            s3AsyncClient = client,
            properties = S3Properties(
                clientSideEncryption = S3Properties.ClientSideEncryption(
                    enabled = true,
                    keyId = "workshop-test",
                    provider = ClientSideEncryptionProvider.AES,
                ),
            ),
            aesProvider = S3AesProvider.of(SecretKeySpec(ByteArray(32) { 6 }, "AES")),
        )
        val outputStreamProvider = ThresholdOutputStreamProvider(operations, temporaryDirectory)
        return TransferFixture(
            template = S3ClientSideEncryptionTransferTemplate(
                client,
                providerTemplate,
                operations,
                outputStreamProvider,
            ),
            providerTemplate = providerTemplate,
        )
    }
}

private class TransferFixture(
    val template: S3ClientSideEncryptionTransferTemplate,
    private val providerTemplate: S3ClientSideEncryptionProviderTemplate,
) : AutoCloseable {
    override fun close() {
        providerTemplate.close()
    }
}

private class ThresholdOutputStreamProvider(
    private val operations: S3TransferOperations,
    private val temporaryDirectory: Path,
) : S3OutputStreamProvider {
    override fun outputStream(
        bucket: String,
        key: String,
        contentType: String?,
        metadata: Map<String, String>,
    ): S3OutputStream = S3OutputStream(
        operations = operations,
        bucket = bucket,
        key = key,
        thresholdBytes = 64,
        partSizeBytes = 1024,
        contentType = contentType,
        metadata = metadata,
        temporaryDirectory = temporaryDirectory,
    )
}

private fun Path.deleteRecursively() {
    if (Files.exists(this)) {
        Files.walk(this).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
