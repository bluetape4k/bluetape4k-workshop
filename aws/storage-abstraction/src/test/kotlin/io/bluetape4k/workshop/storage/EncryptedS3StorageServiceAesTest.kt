package io.bluetape4k.workshop.storage

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.aws.spring.s3.S3AesProvider
import io.bluetape4k.aws.spring.s3.S3BoundedEncryptedReadOperations
import io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionException
import io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionProviderTemplate
import io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionTransferOperations
import io.bluetape4k.aws.spring.s3.ClientSideEncryptionProvider
import io.bluetape4k.aws.spring.sqs.SqsExtendedPayloadReadException
import io.bluetape4k.aws.spring.s3.S3Properties.ClientSideEncryption
import io.bluetape4k.aws.spring.s3.S3Properties
import io.bluetape4k.aws.spring.s3.S3RsaProvider
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.transfer.s3.S3TransferManager
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import javax.crypto.KeyGenerator

@SpringBootTest
@ActiveProfiles("s3-encrypted-aes")
class EncryptedS3StorageServiceAesTest @Autowired constructor(
    private val context: ApplicationContext,
    private val storageService: EncryptedS3StorageService,
    private val s3Client: S3Client,
    private val s3AsyncClient: S3AsyncClient,
    private val transferOperations: S3ClientSideEncryptionTransferOperations,
    private val aesProvider: S3AesProvider,
    private val properties: S3Properties,
) {

    @Test
    fun `AES byte round trip records provider metadata`() = runSuspendIO {
        val key = "encrypted/${Base58.randomString(8)}.bin"
        val payload = ByteArray(256) { (it % 251).toByte() }
        try {
            storageService.upload(key, payload, "application/octet-stream")

            storageService.download(key) shouldBeEqualTo payload
            storageService.getUrl(key) shouldContain "s3://$BUCKET/"

            val metadata = s3Client.headObject { it.bucket(BUCKET).key(key) }.metadata()
            metadata["bt4k-cek-provider"] shouldBeEqualTo "aes"
            metadata["bt4k-cek-alg"] shouldBeEqualTo "AES/GCM/NoPadding"
            metadata["bt4k-cek-key-id"] shouldBeEqualTo "workshop-aes"
            metadata["bt4k-cek-key-version"] shouldBeEqualTo "v1"
        } finally {
            storageService.delete(key)
        }
    }

    @Test
    fun `AES file round trip keeps plaintext out of the S3 object`(
        @TempDir
        tempDirectory: Path,
    ) = runSuspendIO {
        val key = "encrypted/${Base58.randomString(8)}.bin"
        val plaintext = ByteArray(8 * 1024) { (it % 239).toByte() }
        val source = tempDirectory.resolve("source.bin")
        val destination = tempDirectory.resolve("destination.bin")
        Files.write(source, plaintext)
        try {
            storageService.uploadFile(key, source, "application/octet-stream") shouldContain "s3://$BUCKET/"
            storageService.downloadFile(key, destination)

            Files.readAllBytes(destination) shouldBeEqualTo plaintext
            val stored = s3Client.getObjectAsBytes { it.bucket(BUCKET).key(key) }
            stored.asByteArray().contentEquals(plaintext).shouldBeFalse()
            stored.response().metadata()["bt4k-cek-provider"] shouldBeEqualTo "aes"
        } finally {
            storageService.delete(key)
        }
    }

    @Test
    fun `AES bounded byte download rejects oversized ciphertext before returning plaintext`() = runSuspendIO {
        val key = "encrypted/${Base58.randomString(8)}.bin"
        val payload = ByteArray(256) { (it % 251).toByte() }
        try {
            storageService.upload(key, payload, "application/octet-stream")
            val boundedService = EncryptedS3StorageService(
                s3Client = s3Client,
                providerTemplate = context.getBean(S3ClientSideEncryptionProviderTemplate::class.java),
                transferOperations = transferOperations,
                bucketName = BUCKET,
                maxCiphertextBytes = 64,
            )

            assertFailsWith<SqsExtendedPayloadReadException> {
                boundedService.download(key)
            }
        } finally {
            storageService.delete(key)
        }
    }

    @Test
    fun `AES authentication failure preserves existing destination`(
        @TempDir
        tempDirectory: Path,
    ) = runSuspendIO {
        val key = "encrypted/${Base58.randomString(8)}.bin"
        val payload = ByteArray(512) { (it % 241).toByte() }
        val destination = tempDirectory.resolve("destination.bin")
        val sentinel = "keep-me".encodeToByteArray()
        Files.write(destination, sentinel)
        try {
            storageService.upload(key, payload, "application/octet-stream")
            val stored = s3Client.getObjectAsBytes { it.bucket(BUCKET).key(key) }
            val tampered = stored.asByteArray().copyOf().also { ciphertext ->
                ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
            }
            try {
                val metadata = stored.response().metadata().toMutableMap()
                s3Client.putObject(
                    { request ->
                        request.bucket(BUCKET).key(key).metadata(metadata)
                    },
                    software.amazon.awssdk.core.sync.RequestBody.fromBytes(tampered),
                )

                assertFailsWith<S3ClientSideEncryptionException> {
                    storageService.downloadFile(key, destination)
                }
                Files.readAllBytes(destination) shouldBeEqualTo sentinel
            } finally {
                tampered.fill(0)
            }
        } finally {
            storageService.delete(key)
        }
    }

    @Test
    fun `AES authentication failure does not create a new destination`(
        @TempDir
        tempDirectory: Path,
    ) = runSuspendIO {
        val key = "encrypted/${Base58.randomString(8)}.bin"
        val payload = ByteArray(512) { (it % 241).toByte() }
        val destination = tempDirectory.resolve("new-destination.bin")
        try {
            storageService.upload(key, payload, "application/octet-stream")
            val stored = s3Client.getObjectAsBytes { it.bucket(BUCKET).key(key) }
            val tampered = stored.asByteArray().copyOf().also { ciphertext ->
                ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
            }
            try {
                s3Client.putObject(
                    { request -> request.bucket(BUCKET).key(key).metadata(stored.response().metadata()) },
                    software.amazon.awssdk.core.sync.RequestBody.fromBytes(tampered),
                )

                assertFailsWith<S3ClientSideEncryptionException> {
                    storageService.downloadFile(key, destination)
                }
                Files.exists(destination).shouldBeFalse()
            } finally {
                tampered.fill(0)
            }
        } finally {
            storageService.delete(key)
        }
    }

    @Test
    fun `AES reserved algorithm metadata mismatch is rejected`() = runSuspendIO {
        val key = "encrypted/${Base58.randomString(8)}.bin"
        val payload = ByteArray(128) { (it % 227).toByte() }
        try {
            storageService.upload(key, payload, "application/octet-stream")
            val stored = s3Client.getObjectAsBytes { it.bucket(BUCKET).key(key) }
            val metadata = stored.response().metadata().toMutableMap().apply {
                this["bt4k-cek-alg"] = "AES/CBC/PKCS5Padding"
            }
            s3Client.putObject(
                { request -> request.bucket(BUCKET).key(key).metadata(metadata) },
                software.amazon.awssdk.core.sync.RequestBody.fromBytes(stored.asByteArray()),
            )

            assertFailsWith<IllegalArgumentException> {
                storageService.download(key)
            }
        } finally {
            storageService.delete(key)
        }
    }

    @Test
    fun `AES invalid envelope metadata is rejected before decrypt`() = runSuspendIO {
        val key = "encrypted/${Base58.randomString(8)}.bin"
        val payload = ByteArray(128) { (it % 223).toByte() }
        try {
            storageService.upload(key, payload, "application/octet-stream")
            val stored = s3Client.getObjectAsBytes { it.bucket(BUCKET).key(key) }
            val metadata = stored.response().metadata().toMutableMap().apply {
                this["bt4k-cek-nonce"] = "AQ"
            }
            s3Client.putObject(
                { request -> request.bucket(BUCKET).key(key).metadata(metadata) },
                software.amazon.awssdk.core.sync.RequestBody.fromBytes(stored.asByteArray()),
            )

            assertFailsWith<IllegalArgumentException> {
                storageService.download(key)
            }
        } finally {
            storageService.delete(key)
        }
    }

    @Test
    fun `AES invalid base64 envelope metadata is rejected before decrypt`() = runSuspendIO {
        val key = "encrypted/${Base58.randomString(8)}.bin"
        val payload = ByteArray(128) { (it % 223).toByte() }
        try {
            storageService.upload(key, payload, "application/octet-stream")
            val stored = s3Client.getObjectAsBytes { it.bucket(BUCKET).key(key) }
            val metadata = stored.response().metadata().toMutableMap().apply {
                this["bt4k-cek-nonce"] = "%%%not-base64%%%"
            }
            s3Client.putObject(
                { request -> request.bucket(BUCKET).key(key).metadata(metadata) },
                software.amazon.awssdk.core.sync.RequestBody.fromBytes(stored.asByteArray()),
            )

            assertFailsWith<IllegalArgumentException> {
                storageService.download(key)
            }
        } finally {
            storageService.delete(key)
        }
    }

    @Test
    fun `AES wrong key cannot decrypt existing object`() = runSuspendIO {
        val key = "encrypted/${Base58.randomString(8)}.bin"
        val payload = ByteArray(128) { (it % 233).toByte() }
        val wrongProvider = S3AesProvider.of(
            KeyGenerator.getInstance("AES")
                .apply { init(256) }
                .generateKey(),
        )
        val wrongTemplate = S3ClientSideEncryptionProviderTemplate(
            s3AsyncClient,
            properties,
            wrongProvider,
            null,
            SecureRandom(),
        )
        try {
            storageService.upload(key, payload, "application/octet-stream")

            assertFailsWith<S3ClientSideEncryptionException> {
                wrongTemplate.downloadEncryptedBytesBounded(
                    BUCKET,
                    key,
                    emptyMap(),
                    S3BoundedEncryptedReadOperations.MAX_CIPHERTEXT_BYTES,
                )
            }
        } finally {
            wrongTemplate.close()
            storageService.delete(key)
        }
    }

    @Test
    fun `AES metadata key version mismatch is rejected`() = runSuspendIO {
        val key = "encrypted/${Base58.randomString(8)}.bin"
        val payload = byteArrayOf(1, 2, 3, 4)
        val mismatchedProperties = properties.copy(
            clientSideEncryption = ClientSideEncryption(
                enabled = true,
                keyId = "workshop-aes",
                encryptionContext = properties.clientSideEncryption.encryptionContext,
                useDataKeyCache = false,
                provider = ClientSideEncryptionProvider.AES,
                keyVersion = "v2",
            ),
        )
        val mismatchedTemplate = S3ClientSideEncryptionProviderTemplate(
            s3AsyncClient,
            mismatchedProperties,
            aesProvider,
            null,
            SecureRandom(),
        )
        try {
            storageService.upload(key, payload, "application/octet-stream")

            assertFailsWith<IllegalStateException> {
                mismatchedTemplate.downloadEncryptedBytesBounded(
                    BUCKET,
                    key,
                    emptyMap(),
                    S3BoundedEncryptedReadOperations.MAX_CIPHERTEXT_BYTES,
                )
            }
        } finally {
            mismatchedTemplate.close()
            storageService.delete(key)
        }
    }

    @Test
    fun `AES profile owns one client transfer graph and only AES provider`() {
        properties.enabled.shouldBeTrue()
        properties.transfer.enabled.shouldBeTrue()
        properties.clientSideEncryption.enabled.shouldBeTrue()
        aesProvider.generateSecretKey().encoded.size shouldBeEqualTo 32
        context.getBeansOfType(S3Client::class.java).size shouldBeEqualTo 1
        context.getBeansOfType(S3AsyncClient::class.java).size shouldBeEqualTo 1
        context.getBeansOfType(S3TransferManager::class.java).size shouldBeEqualTo 1
        context.getBeansOfType(S3ClientSideEncryptionProviderTemplate::class.java).size shouldBeEqualTo 1
        context.getBeansOfType(S3ClientSideEncryptionTransferOperations::class.java).size shouldBeEqualTo 1
        context.getBeansOfType(S3AesProvider::class.java).size shouldBeEqualTo 1
        context.getBeansOfType(S3RsaProvider::class.java).size shouldBeEqualTo 0
        context.getBeansOfType(S3Properties::class.java).size shouldBeEqualTo 1
    }

    @Test
    fun `AES profile exposes encrypted storage service`() {
        context.getBeansOfType(StorageService::class.java).size shouldBeEqualTo 1
    }

    private companion object {
        const val BUCKET = "bluetape4k-workshop-bucket"
    }
}
