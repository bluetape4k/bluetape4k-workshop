package io.bluetape4k.workshop.aws.s3

import io.awspring.cloud.s3.S3Template
import io.bluetape4k.aws.auth.staticCredentialsProviderOf
import io.bluetape4k.aws.s3.createBucket
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.testcontainers.aws.LocalStackServer
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader
import org.springframework.core.io.WritableResource
import org.testcontainers.containers.localstack.LocalStackContainer
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.util.*

@SpringBootApplication
class SpringCloudAwsS3Sample {

    companion object: KLogging() {
        private const val TEST_FILE_URL = "s3://spring-cloud-aws-sample-bucket1/test-file.txt"

        private val s3Server = LocalStackServer.Launcher.localStack
            .withServices(LocalStackContainer.Service.S3)
    }

//    @Value(TEST_FILE_URL)
//    private lateinit var file: Resource

    @Bean
    fun s3Client(): S3Client {
        return S3Client.builder()
            .endpointOverride(s3Server.endpoint)
            .region(Region.of(s3Server.region))
            .credentialsProvider(staticCredentialsProviderOf(s3Server.accessKey, s3Server.secretKey))
            .build()
    }

    @Bean
    fun applicationRunner(
        s3Client: S3Client,
        resourceLoader: ResourceLoader,
        s3Template: S3Template,
    ): ApplicationRunner {
        return ApplicationRunner {
            s3Client.createBucket("spring-cloud-aws-sample-bucket1") {}
            s3Client.createBucket("spring-cloud-aws-sample-bucket2") {}
            s3Template.store("spring-cloud-aws-sample-bucket1", "test-file.txt", "test file content")
            s3Template.store("spring-cloud-aws-sample-bucket1", "my-file.txt", "my file content")

            // use auto-configured cross-region client
            s3Client
                .listObjects { it.bucket("spring-cloud-aws-sample-bucket1") }.contents()
                .forEach {
                    log.info { "Object in bucket: ${it.key()}" }
                }

            // Load resource using ResourceLoader
            val resource = resourceLoader.getResource(TEST_FILE_URL) as WritableResource
            log.info { "File content: ${resource.readContent()}" }
        }
    }
}

fun main(vararg args: String) {
    runApplication<SpringCloudAwsS3Sample>(*args)
}

fun Resource.readContent(): String {
    val scanner = Scanner(this.inputStream).useDelimiter("\\A")
    return if (scanner.hasNext()) scanner.next() else ""
}
