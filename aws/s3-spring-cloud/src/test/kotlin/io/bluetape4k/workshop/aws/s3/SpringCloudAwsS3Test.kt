package io.bluetape4k.workshop.aws.s3

import io.awspring.cloud.s3.ObjectMetadata
import io.awspring.cloud.s3.S3Template
import io.bluetape4k.aws.spring.s3.S3Resource
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.aws.FlociServer
import org.springframework.beans.factory.annotation.Qualifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ResourceLoader
import org.springframework.core.io.support.ResourcePatternResolver
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.ObjectIdentifier

@SpringBootTest(classes = [SpringCloudAwsS3Sample::class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpringCloudAwsS3Test @Autowired constructor(
    private val s3Client: S3Client,
    private val s3Template: S3Template,
    private val resourceLoader: ResourceLoader,
    @Qualifier("s3ResourcePatternResolver")
    private val resourcePatternResolver: ResourcePatternResolver,
) : AbstractSpringCloudAwsS3SampleTest() {

    companion object : KLogging() {
        private const val SAMPLE_BUCKET = "spring-cloud-aws-sample-bucket1"
        private const val CONFIG_PATTERN = "s3://$SAMPLE_BUCKET/config/**/*.yml"
        private const val PAGINATION_FIXTURE_COUNT = 1_001

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

    @Test
    fun `auto configured resolver reads an exact S3 resource`() {
        val resource = resourcePatternResolver.getResource(
            "s3://spring-cloud-aws-sample-bucket1/test-file.txt",
        )

        resource.exists().shouldBeTrue()
        (resource is S3Resource).shouldBeTrue()
        resource.readContent() shouldBeEqualTo "test file content"
    }

    @Test
    fun `lists matching config resources in deterministic order`() {
        val keys = resourcePatternResolver.getResources(CONFIG_PATTERN)
            .map { (it as S3Resource).location.key }

        keys shouldBeEqualTo listOf(
            "config/application.yml",
            "config/nested/application.yml",
            "config/z.yml",
        )
    }

    @Test
    fun `consumes Floci pagination sorts matches and closes returned streams`() {
        val bucket = "issue-871-${Base58.randomString(10).lowercase()}"
        val generatedKeys = List(PAGINATION_FIXTURE_COUNT) { index ->
            "config/generated/object-${index.toString().padStart(4, '0')}.yml"
        }
        val fixedKeys = listOf(
            "config/application.yml",
            "config/nested/application.yml",
            "config/z.yml",
            "config/readme.txt",
            "other/application.yml",
        )
        val allKeys = generatedKeys + fixedKeys
        val exactBody = "key=config/application.yml\n"

        try {
            s3Client.createBucket { it.bucket(bucket) }
            allKeys.forEach { key ->
                s3Client.putObject(
                    { request ->
                        request.bucket(bucket)
                        request.key(key)
                        request.contentType("text/yaml")
                    },
                    RequestBody.fromString("key=$key\n"),
                )
            }

            val exact = resourcePatternResolver
                .getResources("s3://$bucket/config/application.yml")
                .single()
            exact.exists().shouldBeTrue()
            exact.contentLength() shouldBeEqualTo exactBody.toByteArray().size.toLong()
            (exact.lastModified() > 0).shouldBeTrue()
            (exact is S3Resource).shouldBeTrue()
            exact.inputStream.use { it.bufferedReader().readText() } shouldBeEqualTo exactBody

            val matches = resourcePatternResolver.getResources("s3://$bucket/config/**/*.yml")
            val matchKeys = matches.map { (it as S3Resource).location.key }
            matchKeys shouldBeEqualTo matchKeys.sorted()
            matchKeys.size shouldBeEqualTo PAGINATION_FIXTURE_COUNT + 3
            matchKeys shouldContain "config/application.yml"
            matchKeys shouldContain "config/nested/application.yml"
            matchKeys shouldContain "config/z.yml"
            matchKeys.none { it.endsWith("readme.txt") || it.startsWith("other/") }.shouldBeTrue()

            resourcePatternResolver.getResources("s3://$bucket/config/**/*.json")
                .size shouldBeEqualTo 0
        } finally {
            runCatching {
                allKeys.chunked(1_000).forEach { chunk ->
                    s3Client.deleteObjects { request ->
                        request.bucket(bucket)
                        request.delete(
                            Delete.builder()
                                .objects(chunk.map { key ->
                                    ObjectIdentifier.builder().key(key).build()
                                })
                                .build(),
                        )
                    }
                }
                s3Client.deleteBucket { it.bucket(bucket) }
            }
        }
    }

    @Test
    fun `rejects unsupported S3 pattern boundaries before listing`() {
        listOf(
            "s3://bucket-*/config/*.yml",
            "s3://bucket/*.yml",
            "s3://bucket-a/config/*.yml,s3://bucket-b/config/*.yml",
        ).forEach { locationPattern ->
            assertFailsWith<IllegalArgumentException> {
                resourcePatternResolver.getResources(locationPattern)
            }
        }
    }

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
