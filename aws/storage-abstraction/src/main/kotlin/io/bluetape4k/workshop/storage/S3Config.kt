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
 * profile `s3`와 `s3-presigned`용 S3 bean 설정입니다.
 *
 * ## 동작 / 계약
 * - 로컬/테스트 환경에서는 [FlociServer.Launcher] 싱글턴을 통해 [FlociServer]를 사용합니다.
 *   Floci는 LocalStack Community edition을 대체하는 오픈소스입니다.
 * - [S3Client]는 동기식이므로 호출자는 호출을 `withContext(Dispatchers.IO)`로 감쌉니다.
 * - [S3Presigner]는 `s3-presigned` profile에서만 제공합니다.
 *
 * ```kotlin
 * // bean은 S3StorageService / S3PresignedStorageService에 자동 주입됩니다.
 * ```
 */
@Configuration(proxyBeanMethods = false)
@Profile("s3 | s3-presigned")
class S3Config {

    companion object : KLogging() {
        /** 같은 JVM의 모든 테스트 실행에서 공유하는 Floci AWS 에뮬레이터 인스턴스입니다. */
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
