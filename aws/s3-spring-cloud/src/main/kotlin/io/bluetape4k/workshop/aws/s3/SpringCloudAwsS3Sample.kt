package io.bluetape4k.workshop.aws.s3

import io.awspring.cloud.s3.S3Template
import io.awspring.cloud.s3.ObjectMetadata
import io.bluetape4k.aws.auth.staticCredentialsProviderOf
import io.bluetape4k.aws.s3.createBucket
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.testcontainers.aws.FlociServer
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.core.io.Resource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.support.ResourcePatternResolver
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.S3Exception

fun main(vararg args: String) {
    runApplication<SpringCloudAwsS3Sample>(*args)
}

/**
 * Floci S3 호환 엔드포인트를 사용하는 로컬 우선 Spring Cloud AWS S3 예제입니다.
 */
@SpringBootApplication(proxyBeanMethods = false)
class SpringCloudAwsS3Sample {

    companion object : KLogging() {
        private const val TEST_FILE_URL = "s3://spring-cloud-aws-sample-bucket1/test-file.txt"
        private const val CONFIG_PATTERN = "s3://spring-cloud-aws-sample-bucket1/config/**/*.yml"

        private val s3Server = FlociServer.Launcher.floci.withServices("s3")
    }

//    @Value(TEST_FILE_URL)
//    private lateinit var file: Resource

    @Bean
    fun s3Client(): S3Client {
        return S3Client.builder()
            .endpointOverride(s3Server.awsEndpoint)
            .region(Region.of(s3Server.regionName))
            .credentialsProvider(staticCredentialsProviderOf(s3Server.awsAccessKey, s3Server.awsSecretKey))
            .build()
    }

    @Bean
    fun applicationRunner(
        s3Client: S3Client,
        s3Template: S3Template,
        @Qualifier("s3ResourcePatternResolver")
        resourcePatternResolver: ResourcePatternResolver,
    ): ApplicationRunner {
        return ApplicationRunner {
            s3Client.ensureBucketExists("spring-cloud-aws-sample-bucket1")
            s3Client.ensureBucketExists("spring-cloud-aws-sample-bucket2")
            s3Template.uploadText("spring-cloud-aws-sample-bucket1", "test-file.txt", "test file content")
            s3Template.uploadText("spring-cloud-aws-sample-bucket1", "my-file.txt", "my file content")
            s3Template.uploadText("spring-cloud-aws-sample-bucket1", "config/application.yml", "name: sample\n")
            s3Template.uploadText("spring-cloud-aws-sample-bucket1", "config/nested/application.yml", "name: nested\n")
            s3Template.uploadText("spring-cloud-aws-sample-bucket1", "config/z.yml", "name: z\n")
            s3Template.uploadText("spring-cloud-aws-sample-bucket1", "config/readme.txt", "not yaml\n")
            s3Template.uploadText("spring-cloud-aws-sample-bucket1", "other/application.yml", "outside prefix\n")

            // 자동 설정된 교차 리전 클라이언트를 사용합니다.
            s3Client
                .listObjects { it.bucket("spring-cloud-aws-sample-bucket1") }.contents()
                .forEach {
                    log.info { "Object in bucket: ${it.key()}" }
                }

            // 고정 qualifier의 ResourcePatternResolver로 exact 리소스를 로드합니다.
            val resource = resourcePatternResolver.getResource(TEST_FILE_URL)
            log.info { "File content: ${resource.readContent()}" }

            resourcePatternResolver.getResources(CONFIG_PATTERN).forEach { configResource ->
                configResource.inputStream.use { input ->
                    log.info {
                        "Config match: ${configResource.filename}, content=${input.bufferedReader().readText()}"
                    }
                }
            }
        }
    }
}


internal fun Resource.readContent(): String {
    return inputStream.bufferedReader().use { it.readText() }
}

private fun S3Client.ensureBucketExists(bucketName: String) {
    try {
        headBucket { it.bucket(bucketName) }
    } catch (e: S3Exception) {
        if (e.statusCode() != 404) {
            throw e
        }
        createBucket(bucketName) {}
    }
}

private fun S3Template.uploadText(bucketName: String, key: String, content: String) {
    val bytes = content.toByteArray(Charsets.UTF_8)
    upload(
        bucketName,
        key,
        bytes.inputStream(),
        ObjectMetadata.builder()
            .contentLength(bytes.size.toLong())
            .contentType("text/plain")
            .build(),
    )
}
