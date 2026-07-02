package io.bluetape4k.workshop.aws.s3

import io.awspring.cloud.s3.ObjectMetadata
import io.awspring.cloud.s3.S3Template
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.aws.FlociServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ResourceLoader
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import software.amazon.awssdk.services.s3.S3Client

@SpringBootTest(classes = [SpringCloudAwsS3Sample::class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpringCloudAwsS3Test: AbstractSpringCloudAwsS3SampleTest() {

    companion object: KLogging() {
        private val floci by lazy { FlociServer.Launcher.floci }

        @JvmStatic
        @DynamicPropertySource
        fun awsProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.cloud.aws.endpoint") { floci.awsEndpoint.toString() }
            registry.add("spring.cloud.aws.region.static") { floci.regionName }
            registry.add("spring.cloud.aws.credentials.access-key") { floci.awsAccessKey }
            registry.add("spring.cloud.aws.credentials.secret-key") { floci.awsSecretKey }
        }
    }

    @Autowired
    lateinit var s3Client: S3Client

    @Autowired
    lateinit var s3Template: S3Template

    @Autowired
    lateinit var resourceLoader: ResourceLoader

    @Test
    fun `stores lists and reads objects through Floci backed Spring Cloud AWS`() {
        val bucketName = "spring-cloud-aws-sample-bucket1"
        val key = "integration/floci-s3-template.txt"
        val body = "Floci backed Spring Cloud AWS content"
        val bodyBytes = body.toByteArray(Charsets.UTF_8)

        try {
            s3Template.upload(
                bucketName,
                key,
                bodyBytes.inputStream(),
                ObjectMetadata.builder()
                    .contentLength(bodyBytes.size.toLong())
                    .contentType("text/plain")
                    .build(),
            )

            val keys = s3Client.listObjects { it.bucket(bucketName) }
                .contents()
                .map { it.key() }

            keys shouldContain "test-file.txt"
            keys shouldContain key

            val resource = resourceLoader.getResource("s3://$bucketName/$key")
            resource.exists().shouldBeTrue()
            resource.readContent() shouldBeEqualTo body
        } finally {
            s3Client.deleteObject { it.bucket(bucketName).key(key) }
        }
    }

}
