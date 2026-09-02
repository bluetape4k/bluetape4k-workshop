package io.bluetape4k.workshop.storage

import io.bluetape4k.aws.auth.staticCredentialsProviderOf
import io.bluetape4k.aws.spring.s3.S3AesProvider
import io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionProviderTemplate
import io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionTransferOperations
import io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionTransferTemplate
import io.bluetape4k.aws.spring.s3.S3Properties
import io.bluetape4k.aws.spring.s3.S3RsaProvider
import io.bluetape4k.aws.spring.s3.S3TransferTemplate
import io.bluetape4k.testcontainers.aws.FlociServer
import kotlinx.coroutines.Dispatchers
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.transfer.s3.S3TransferManager
import java.security.KeyPairGenerator
import java.security.SecureRandom
import javax.crypto.KeyGenerator

/**
 * `s3-encrypted-aes`와 `s3-encrypted-rsa` profile 전용 S3/CSE bean graph입니다.
 *
 * 암호화 provider와 transfer lifecycle은 upstream template에 위임하고, 이 예제는
 * Floci endpoint와 JVM memory key만 사용합니다. 기존 `S3Config`의 unencrypted
 * profile bean과 섞이지 않도록 profile을 별도로 유지합니다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("s3-encrypted-aes | s3-encrypted-rsa")
@EnableConfigurationProperties(S3Properties::class)
class EncryptedS3Config {

    private val floci: FlociServer = S3Config.floci

    @Bean(destroyMethod = "close")
    fun s3Client(): S3Client =
        S3Client.builder()
            .endpointOverride(floci.awsEndpoint)
            .region(Region.of(floci.regionName))
            .credentialsProvider(staticCredentialsProviderOf(floci.awsAccessKey, floci.awsSecretKey))
            .build()

    @Bean(destroyMethod = "close")
    fun s3AsyncClient(): S3AsyncClient =
        S3AsyncClient.builder()
            .endpointOverride(floci.awsEndpoint)
            .region(Region.of(floci.regionName))
            .credentialsProvider(staticCredentialsProviderOf(floci.awsAccessKey, floci.awsSecretKey))
            .build()

    @Bean(destroyMethod = "close")
    fun s3TransferManager(s3AsyncClient: S3AsyncClient): S3TransferManager =
        S3TransferManager.builder()
            .s3Client(s3AsyncClient)
            .build()

    @Bean
    fun s3TransferTemplate(
        s3TransferManager: S3TransferManager,
        properties: S3Properties,
    ): S3TransferTemplate =
        S3TransferTemplate(s3TransferManager, properties)

    @Bean(destroyMethod = "close")
    fun s3ClientSideEncryptionProviderTemplate(
        s3AsyncClient: S3AsyncClient,
        properties: S3Properties,
        aesProvider: ObjectProvider<S3AesProvider>,
        rsaProvider: ObjectProvider<S3RsaProvider>,
    ): S3ClientSideEncryptionProviderTemplate =
        S3ClientSideEncryptionProviderTemplate(
            s3AsyncClient,
            properties,
            aesProvider.getIfUnique(),
            rsaProvider.getIfUnique(),
            SecureRandom(),
        )

    @Bean
    fun s3ClientSideEncryptionTransferTemplate(
        s3AsyncClient: S3AsyncClient,
        providerTemplate: S3ClientSideEncryptionProviderTemplate,
        s3TransferTemplate: S3TransferTemplate,
    ): S3ClientSideEncryptionTransferOperations =
        S3ClientSideEncryptionTransferTemplate(
            s3AsyncClient,
            providerTemplate,
            s3TransferTemplate,
            s3TransferTemplate,
            Dispatchers.IO,
        )

    @Bean
    @Profile("s3-encrypted-aes")
    fun aesProvider(): S3AesProvider =
        S3AesProvider.of(
            KeyGenerator.getInstance("AES")
                .apply { init(256) }
                .generateKey(),
        )

    @Bean
    @Profile("s3-encrypted-rsa")
    fun rsaProvider(): S3RsaProvider =
        S3RsaProvider.of(
            KeyPairGenerator.getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair(),
        )
}
