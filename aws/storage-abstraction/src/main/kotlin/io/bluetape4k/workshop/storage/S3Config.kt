package io.bluetape4k.workshop.storage

import io.bluetape4k.aws.auth.staticCredentialsProviderOf
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.aws.FlociServer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner

/**
 * S3 bean configuration for profiles `s3` and `s3-presigned`.
 *
 * ## Behavior / Contract
 * - Uses [FlociServer] via [FlociServer.Launcher] singleton for local/test environments.
 *   Floci is the open-source replacement for LocalStack Community edition.
 * - [S3Client] is synchronous; callers wrap calls in `withContext(Dispatchers.IO)`.
 * - [S3Presigner] is provided only for the `s3-presigned` profile.
 *
 * ```kotlin
 * // Beans are auto-wired into S3StorageService / S3PresignedStorageService
 * ```
 */
@Configuration(proxyBeanMethods = false)
@Profile("s3 | s3-presigned")
class S3Config {

    companion object : KLogging() {
        /** Shared Floci AWS emulator instance across all test runs in the same JVM. */
        val floci: FlociServer = FlociServer.Launcher.floci
    }

    @Bean
    fun s3Client(): S3Client =
        S3Client.builder()
            .endpointOverride(floci.awsEndpoint)
            .region(Region.of(floci.regionName))
            .credentialsProvider(staticCredentialsProviderOf(floci.awsAccessKey, floci.awsSecretKey))
            .build()

    @Bean
    @Profile("s3-presigned")
    fun s3Presigner(): S3Presigner =
        S3Presigner.builder()
            .endpointOverride(floci.awsEndpoint)
            .region(Region.of(floci.regionName))
            .credentialsProvider(staticCredentialsProviderOf(floci.awsAccessKey, floci.awsSecretKey))
            .build()
}
