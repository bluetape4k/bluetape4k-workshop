package io.bluetape4k.workshop.storage

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.aws.spring.s3.S3BoundedEncryptedReadOperations
import io.bluetape4k.aws.spring.s3.S3AesProvider
import io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionException
import io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionProviderTemplate
import io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionTransferOperations
import io.bluetape4k.aws.spring.s3.S3Properties
import io.bluetape4k.aws.spring.s3.S3RsaProvider
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.transfer.s3.S3TransferManager
import java.security.KeyPairGenerator
import java.security.SecureRandom

@SpringBootTest
@ActiveProfiles("s3-encrypted-rsa")
class EncryptedS3StorageServiceRsaTest @Autowired constructor(
    private val context: ApplicationContext,
    private val storageService: EncryptedS3StorageService,
    private val s3Client: S3Client,
    private val s3AsyncClient: S3AsyncClient,
    private val properties: S3Properties,
    private val rsaProvider: S3RsaProvider,
) {

    @Test
    fun `RSA byte round trip records provider metadata`() = runSuspendIO {
        val key = "encrypted/${Base58.randomString(8)}.bin"
        val payload = ByteArray(256) { (it % 251).toByte() }
        try {
            storageService.upload(key, payload, "application/octet-stream")

            storageService.download(key) shouldBeEqualTo payload
            storageService.getUrl(key) shouldContain "s3://$BUCKET/"

            val metadata = s3Client.headObject { it.bucket(BUCKET).key(key) }.metadata()
            metadata["bt4k-cek-provider"] shouldBeEqualTo "rsa"
            metadata["bt4k-cek-wrap-alg"] shouldBeEqualTo "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"
            metadata["bt4k-cek-key-id"] shouldBeEqualTo "workshop-rsa"
            metadata["bt4k-cek-key-version"] shouldBeEqualTo "v1"
        } finally {
            storageService.delete(key)
        }
    }

    @Test
    fun `RSA wrong key cannot decrypt existing object`() = runSuspendIO {
        val key = "encrypted/${Base58.randomString(8)}.bin"
        val payload = ByteArray(128) { (it % 233).toByte() }
        val wrongProvider = S3RsaProvider.of(
            KeyPairGenerator.getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair(),
        )
        val wrongTemplate = S3ClientSideEncryptionProviderTemplate(
            s3AsyncClient,
            properties,
            null,
            wrongProvider,
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
    fun `RSA profile owns one client transfer graph and only RSA provider`() {
        properties.enabled.shouldBeTrue()
        properties.transfer.enabled.shouldBeTrue()
        properties.clientSideEncryption.enabled.shouldBeTrue()
        val publicKey = rsaProvider.generateKeyPair().public
        (publicKey as java.security.interfaces.RSAPublicKey).modulus.bitLength() shouldBeGreaterThan 2047
        context.getBeansOfType(S3Client::class.java).size shouldBeEqualTo 1
        context.getBeansOfType(S3AsyncClient::class.java).size shouldBeEqualTo 1
        context.getBeansOfType(S3TransferManager::class.java).size shouldBeEqualTo 1
        context.getBeansOfType(S3ClientSideEncryptionProviderTemplate::class.java).size shouldBeEqualTo 1
        context.getBeansOfType(S3ClientSideEncryptionTransferOperations::class.java).size shouldBeEqualTo 1
        context.getBeansOfType(S3RsaProvider::class.java).size shouldBeEqualTo 1
        context.getBeansOfType(S3AesProvider::class.java).size shouldBeEqualTo 0
        context.getBeansOfType(S3Properties::class.java).size shouldBeEqualTo 1
    }

    @Test
    fun `RSA profile exposes encrypted storage service`() {
        context.getBeansOfType(StorageService::class.java).size shouldBeEqualTo 1
    }

    private companion object {
        const val BUCKET = "bluetape4k-workshop-bucket"
    }
}
