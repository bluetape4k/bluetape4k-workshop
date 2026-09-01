package io.bluetape4k.workshop.aws.settings

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.aws.spring.AwsClientCustomizationContext
import io.bluetape4k.aws.spring.AwsSyncClientCustomizer
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.WebApplicationType
import org.springframework.boot.SpringApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.bootstrap.BootstrapRegistry
import org.springframework.boot.bootstrap.BootstrapRegistryInitializer
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClient
import software.amazon.awssdk.services.appconfigdata.model.StartConfigurationSessionRequest
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Timeout(30)
@ExtendWith(OutputCaptureExtension::class)
class AppConfigDataSpringIntegrationTest {

    @Test
    fun `ConfigData import loads initial values and runtime reload updates Environment only`() {
        val server = FakeAppConfigDataServer(
            payloads = listOf(
                "feature=initial\nversion=v1\n",
                "feature=updated\nversion=v2\n",
            ),
        ).start()
        var context: ConfigurableApplicationContext? = null
        try {
            val application = SpringApplicationBuilder(
                SettingsBoundarySpringApplication::class.java,
                AppConfigProbeConfiguration::class.java,
                AppConfigCredentialsConfiguration::class.java,
            )
                .web(WebApplicationType.NONE)
                .properties(
                    "spring.config.import=aws-app-config:application#profile#environment?format=properties&prefix=appconfig",
                    "bluetape4k.aws.enabled=true",
                    "bluetape4k.aws.region=us-east-1",
                    "bluetape4k.aws.app-config.enabled=true",
                    "bluetape4k.aws.app-config.region=us-east-1",
                    "bluetape4k.aws.app-config.endpoint-override=${server.endpoint}",
                    "bluetape4k.aws.app-config.refresh-interval=15s",
                    "spring.main.banner-mode=off",
                )
                .build()
            application.addBootstrapRegistryInitializer(
                BootstrapRegistryInitializer { registry ->
                    registry.register(
                        AwsSyncClientCustomizer::class.java,
                        BootstrapRegistry.InstanceSupplier.of(testCustomizer()),
                    )
                },
            )

            context = application.run("--bluetape4k.aws.app-config.enabled=true")

            context.environment.getProperty("appconfig.feature") shouldBeEqualTo "initial"
            context.environment.getProperty("appconfig.version") shouldBeEqualTo "v1"
            context.getBean(AppConfigProbeProperties::class.java).feature shouldBeEqualTo "initial"
            context.getBean(AppConfigValueProbe::class.java).feature shouldBeEqualTo "initial"
            await atMost Duration.ofSeconds(20) untilAsserted {
                context.environment.getProperty("appconfig.feature") shouldBeEqualTo "updated"
            }
            context.environment.getProperty("appconfig.version") shouldBeEqualTo "v2"
            context.getBean(AppConfigProbeProperties::class.java).feature shouldBeEqualTo "initial"
            context.getBean(AppConfigValueProbe::class.java).feature shouldBeEqualTo "initial"
            server.applicationIdentifiers shouldBeEqualTo listOf("application")
            server.profileIdentifiers shouldBeEqualTo listOf("profile")
            server.environmentIdentifiers shouldBeEqualTo listOf("environment")
            server.tokenOrdinals shouldBeEqualTo listOf(1, 2)
            server.authorizationMarkers shouldBeEqualTo listOf(true, true, true)
            server.maxConcurrentRequests.get() shouldBeEqualTo 1
        } finally {
            val requestCountBeforeClose = server.requestCount.get()
            context?.close()
            context?.close()
            server.close()
            await atMost Duration.ofSeconds(6) untilAsserted {
                server.activeRequests.get() shouldBeEqualTo 0
                server.executorTerminated() shouldBeEqualTo true
            }
            Thread.sleep(1_000)
            server.requestCount.get() shouldBeEqualTo requestCountBeforeClose
        }
    }

    @Test
    fun `context close remains bounded while a runtime poll is in flight`() {
        val server = FakeAppConfigDataServer(
            payloads = listOf(
                "feature=initial\n",
                "feature=updated\n",
            ),
            delayPayloadIndex = 1,
            delayMillis = 8_000,
        ).start()
        var context: ConfigurableApplicationContext? = null
        var requestCountBeforeClose = -1
        try {
            context = newApplication(
                server = server,
                importLocation = "aws-app-config:application#profile#environment?format=properties&prefix=appconfig",
                properties = listOf("bluetape4k.aws.app-config.refresh-interval=15s"),
            ).run("--bluetape4k.aws.app-config.enabled=true")

            assertTrue(
                server.awaitDelayedLatest(20, TimeUnit.SECONDS),
                "delayed latest request did not start: count=${server.requestCount.get()}, " +
                    "tokens=${server.tokenOrdinals}, active=${server.activeRequests.get()}",
            )
            assertTrue(server.activeRequests.get() > 0)
            requestCountBeforeClose = server.requestCount.get()
            val startedAt = Instant.now()
            context.close()
            assertTrue(Duration.between(startedAt, Instant.now()).toMillis() <= 6_000)
            context.close()
        } finally {
            context?.close()
            server.close()
            await atMost Duration.ofSeconds(6) untilAsserted {
                server.activeRequests.get() shouldBeEqualTo 0
                server.executorTerminated() shouldBeEqualTo true
            }
            Thread.sleep(1_000)
            if (requestCountBeforeClose >= 0) {
                server.requestCount.get() shouldBeEqualTo requestCountBeforeClose
            }
        }
    }

    @Test
    fun `default profile does not create an AppConfig client or property source`() {
        val context = SpringApplicationBuilder(SettingsBoundarySpringApplication::class.java)
            .web(WebApplicationType.NONE)
            .properties(
                "bluetape4k.aws.enabled=true",
                "spring.main.banner-mode=off",
            )
            .build()
            .run()
        try {
            context.getBeansOfType(AppConfigDataClient::class.java).keys shouldBeEqualTo emptySet()
            context.environment.propertySources
                .none { source -> source.name.contains("aws-app-config", ignoreCase = true) } shouldBeEqualTo true
        } finally {
            context.close()
        }
    }

    @Test
    fun `JSON format and prefix keep remote operational keys below the prefix`() {
        val server = FakeAppConfigDataServer(
            payloads = listOf(
                """{"feature":"json","nested":{"enabled":true},"spring.application.name":"attacker","management.endpoints.web.exposure.include":"env"}""",
            ),
        ).start()
        var context: ConfigurableApplicationContext? = null
        try {
            context = newApplication(
                server = server,
                importLocation = "aws-app-config:application#profile#environment?format=json&prefix=appconfig",
            ).run("--bluetape4k.aws.app-config.enabled=true")

            context.environment.getProperty("appconfig.feature") shouldBeEqualTo "json"
            context.environment.getProperty("appconfig.nested.enabled") shouldBeEqualTo "true"
            context.environment.getProperty("appconfig.spring.application.name") shouldBeEqualTo "attacker"
            context.environment.getProperty("appconfig.management.endpoints.web.exposure.include") shouldBeEqualTo "env"
            context.environment.getProperty("spring.application.name") shouldBeEqualTo "aws-settings-boundary"
        } finally {
            context?.close()
            server.close()
        }
    }

    @Test
    fun `optional import and fail-fast false tolerate a missing AppConfig resource`() {
        val optionalServer = FakeAppConfigDataServer(
            payloads = listOf("feature=never\n"),
            sessionStatus = 404,
        ).start()
        var optionalContext: ConfigurableApplicationContext? = null
        try {
            optionalContext = newApplication(
                server = optionalServer,
                importLocation = "optional:aws-app-config:application#profile#environment?format=properties&prefix=appconfig",
            ).run("--bluetape4k.aws.app-config.enabled=true")
            optionalContext.environment.getProperty("appconfig.feature") shouldBeEqualTo null
        } finally {
            optionalContext?.close()
            optionalServer.close()
        }

        val nonFailFastServer = FakeAppConfigDataServer(
            payloads = listOf("feature=never\n"),
            sessionStatus = 500,
            errorType = "InternalServerException",
        ).start()
        var nonFailFastContext: ConfigurableApplicationContext? = null
        try {
            nonFailFastContext = newApplication(
                server = nonFailFastServer,
                importLocation = "aws-app-config:application#profile#environment?format=properties&prefix=appconfig",
                properties = listOf("bluetape4k.aws.app-config.fail-fast=false"),
            ).run("--bluetape4k.aws.app-config.enabled=true")
            nonFailFastContext.environment.getProperty("appconfig.feature") shouldBeEqualTo null
        } finally {
            nonFailFastContext?.close()
            nonFailFastServer.close()
        }
    }

    @Test
    fun `wrong method and path are rejected by the loopback contract`() {
        val server = FakeAppConfigDataServer(payloads = listOf("feature=unused\n")).start()
        try {
            val client = HttpClient.newHttpClient()
            val wrongMethod = client.send(
                HttpRequest.newBuilder(server.endpoint.resolve("/configuration"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            val wrongPath = client.send(
                HttpRequest.newBuilder(server.endpoint.resolve("/not-configuration"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            val configurationPrefixCollision = client.send(
                HttpRequest.newBuilder(server.endpoint.resolve("/configuration-other"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            val sessionPrefixCollision = client.send(
                HttpRequest.newBuilder(server.endpoint.resolve("/configurationsessions-other"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            wrongMethod.statusCode() shouldBeEqualTo 405
            wrongPath.statusCode() shouldBeEqualTo 404
            configurationPrefixCollision.statusCode() shouldBeEqualTo 404
            sessionPrefixCollision.statusCode() shouldBeEqualTo 404
        } finally {
            server.close()
        }
    }

    @Test
    fun `endpoint override accepts AWS regional HTTPS and test loopback only`() {
        validateAppConfigEndpoint(URI("https://appconfigdata.us-east-1.amazonaws.com"), "us-east-1")
        validateAppConfigEndpoint(URI("https://appconfigdata-fips.us-east-1.amazonaws.com"), "us-east-1")
        validateAppConfigEndpoint(URI("http://127.0.0.1:4566"), "us-east-1")

        listOf(
            URI("http://169.254.169.254/latest/meta-data"),
            URI("http://untrusted.example.test"),
            URI("https://untrusted.example.test"),
            URI("https://appconfigdata.us-west-2.amazonaws.com"),
            URI("https://appconfigdata.us-east-1.amazonaws.com:8443"),
            URI("https://appconfigdata.us-east-1.amazonaws.com/proxy"),
            URI("https://user:secret@appconfigdata.us-east-1.amazonaws.com"),
        ).forEach { endpoint ->
            assertFailsWith<IllegalArgumentException> {
                validateAppConfigEndpoint(endpoint, "us-east-1")
            }
        }
    }

    @Test
    fun `endpoint guard uses the AppConfig-specific region before the global region`() {
        val environment = StandardEnvironment().apply {
            propertySources.addFirst(
                MapPropertySource(
                    "appconfig-region",
                    mapOf(
                        "bluetape4k.aws.region" to "us-west-2",
                        "bluetape4k.aws.app-config.region" to "us-east-1",
                        "bluetape4k.aws.app-config.endpoint-override" to
                            "https://appconfigdata.us-east-1.amazonaws.com",
                    ),
                ),
            )
        }

        SettingsBoundaryAppConfigEndpointGuard().postProcessEnvironment(
            environment,
            SpringApplication(SettingsBoundarySpringApplication::class.java),
        )
    }

    @Test
    fun `endpoint guard rejects an untrusted override before ConfigData`() {
        val environment = StandardEnvironment().apply {
            propertySources.addFirst(
                MapPropertySource(
                    "untrusted-endpoint",
                    mapOf(
                        "bluetape4k.aws.region" to "us-east-1",
                        "bluetape4k.aws.app-config.endpoint-override" to "http://169.254.169.254",
                    ),
                ),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            SettingsBoundaryAppConfigEndpointGuard().postProcessEnvironment(
                environment,
                SpringApplication(SettingsBoundarySpringApplication::class.java),
            )
        }
    }

    @Test
    fun `endpoint guard rejects a malformed override without echoing it`() {
        val malformedEndpoint = "https://appconfigdata.us-east-1.amazonaws.com/%zz"
        val environment = StandardEnvironment().apply {
            propertySources.addFirst(
                MapPropertySource(
                    "malformed-endpoint",
                    mapOf(
                        "bluetape4k.aws.region" to "us-east-1",
                        "bluetape4k.aws.app-config.endpoint-override" to malformedEndpoint,
                    ),
                ),
            )
        }

        val failure = assertFailsWith<IllegalArgumentException> {
            SettingsBoundaryAppConfigEndpointGuard().postProcessEnvironment(
                environment,
                SpringApplication(SettingsBoundarySpringApplication::class.java),
            )
        }
        failure.message shouldBeEqualTo
            "bluetape4k.aws.app-config.endpoint-override must be a valid URI."
        assertTrue(malformedEndpoint !in failure.toString())
    }

    @Test
    fun `registered endpoint guard rejects a command line endpoint before ConfigData`() {
        var context: ConfigurableApplicationContext? = null
        val application = SpringApplicationBuilder(SettingsBoundarySpringApplication::class.java)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.config.import=optional:aws-app-config:application#profile#environment?format=properties&prefix=appconfig",
                "bluetape4k.aws.enabled=true",
                "bluetape4k.aws.region=us-east-1",
                "bluetape4k.aws.app-config.region=us-east-1",
                "bluetape4k.aws.app-config.fail-fast=false",
                "spring.main.banner-mode=off",
            )
            .build()
        try {
            val failure = assertFailsWith<Exception> {
                context = application.run(
                    "--bluetape4k.aws.app-config.enabled=true",
                    "--bluetape4k.aws.app-config.endpoint-override=http://169.254.169.254",
                )
            }
            val messages = generateSequence(failure as Throwable?) { it.cause }
                .mapNotNull { it.message }
                .joinToString(" ")
            assertTrue(messages.contains("regional AWS HTTPS host"))
        } finally {
            context?.close()
        }
    }

    @Test
    fun `fail-fast errors stay bounded and redact token credentials and payload`(output: CapturedOutput) {
        val server = FakeAppConfigDataServer(
            payloads = listOf("feature=sentinel-payload\n"),
            sessionStatus = 500,
        ).start()
        try {
            val startedAt = Instant.now()
            val failure = assertFailsWith<Exception> {
                newApplication(
                    server = server,
                    importLocation = "aws-app-config:application#profile#environment?format=properties&prefix=appconfig",
                ).run("--bluetape4k.aws.app-config.enabled=true")
            }
            val messages = generateSequence(failure as Throwable?) { it.cause }
                .mapNotNull { it.message }
                .joinToString(" ")
            listOf(
                "synthetic-token",
                "synthetic-access-key",
                "synthetic-secret-key",
                "sentinel-payload",
                "Authorization",
            ).forEach { sentinel ->
                assertTrue(sentinel !in messages, "failure leaked sentinel: $sentinel")
                assertTrue(sentinel !in output.all, "log output leaked sentinel: $sentinel")
            }
            assertTrue(Duration.between(startedAt, Instant.now()).toMillis() < 2_000)
        } finally {
            server.close()
        }
    }

    @Test
    fun `test timeout limits a delayed AppConfig request and ignores other services`() {
        val server = FakeAppConfigDataServer(
            payloads = listOf("feature=delayed\n"),
            delayMillis = 1_200,
        ).start()
        var client: AppConfigDataClient? = null
        var productionClient: AppConfigDataClient? = null
        try {
            val productionBuilder = AppConfigDataClient.builder()
                .endpointOverride(server.endpoint)
                .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                .credentialsProvider(TEST_CREDENTIALS)
            productionBuilder.overrideConfiguration { configuration ->
                configuration.putHeader("X-Synthetic-Existing", "preserve-me")
            }
            appConfigTimeoutCustomizer(
                apiCallTimeout = Duration.ofSeconds(10),
                apiCallAttemptTimeout = Duration.ofSeconds(5),
            ).customize(AwsClientCustomizationContext("appconfigdata"), productionBuilder)
            productionClient = productionBuilder.build()
            productionClient.serviceClientConfiguration().overrideConfiguration().apiCallTimeout().get() shouldBeEqualTo
                Duration.ofSeconds(10)
            productionClient.serviceClientConfiguration().overrideConfiguration().apiCallAttemptTimeout().get() shouldBeEqualTo
                Duration.ofSeconds(5)
            productionClient.serviceClientConfiguration().overrideConfiguration().headers()["X-Synthetic-Existing"] shouldBeEqualTo
                listOf("preserve-me")

            val builder = AppConfigDataClient.builder()
                .endpointOverride(server.endpoint)
                .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                .credentialsProvider(TEST_CREDENTIALS)
            testCustomizer().customize(AwsClientCustomizationContext("appconfigdata"), builder)
            client = builder.build()
            client.serviceClientConfiguration().overrideConfiguration().apiCallTimeout().get() shouldBeEqualTo Duration.ofMillis(500)
            client.serviceClientConfiguration().overrideConfiguration().apiCallAttemptTimeout().get() shouldBeEqualTo Duration.ofMillis(500)
            val request = StartConfigurationSessionRequest.builder()
                .applicationIdentifier("application")
                .configurationProfileIdentifier("profile")
                .environmentIdentifier("environment")
                .requiredMinimumPollIntervalInSeconds(15)
                .build()
            val startedAt = Instant.now()
            val configuredClient = checkNotNull(client)
            assertFailsWith<RuntimeException> { configuredClient.startConfigurationSession(request) }
            assertTrue(Duration.between(startedAt, Instant.now()).toMillis() < 2_000)

            val otherBuilder = AppConfigDataClient.builder()
                .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                .credentialsProvider(TEST_CREDENTIALS)
            appConfigTimeoutCustomizer(
                apiCallTimeout = Duration.ofSeconds(10),
                apiCallAttemptTimeout = Duration.ofSeconds(5),
            ).customize(AwsClientCustomizationContext("s3"), otherBuilder)
            val otherClient = otherBuilder.build()
            try {
                otherClient.serviceClientConfiguration().overrideConfiguration().apiCallTimeout().isEmpty shouldBeEqualTo true
            } finally {
                otherClient.close()
            }
        } finally {
            client?.close()
            productionClient?.close()
            server.close()
        }
    }

    private fun newApplication(
        server: FakeAppConfigDataServer,
        importLocation: String,
        properties: List<String> = emptyList(),
    ): org.springframework.boot.SpringApplication {
        val application = SpringApplicationBuilder(
            SettingsBoundarySpringApplication::class.java,
            AppConfigCredentialsConfiguration::class.java,
        )
            .web(WebApplicationType.NONE)
            .properties(
                *(
                    listOf(
                        "spring.config.import=$importLocation",
                        "bluetape4k.aws.enabled=true",
                        "bluetape4k.aws.region=us-east-1",
                        "bluetape4k.aws.app-config.region=us-east-1",
                        "bluetape4k.aws.app-config.endpoint-override=${server.endpoint}",
                        "spring.main.banner-mode=off",
                    ) + properties
                ).toTypedArray(),
            )
            .build()
        application.addBootstrapRegistryInitializer(
            BootstrapRegistryInitializer { registry ->
                registry.register(
                    AwsSyncClientCustomizer::class.java,
                    BootstrapRegistry.InstanceSupplier.of(testCustomizer()),
                )
            },
        )
        return application
    }

    private fun testCustomizer(): AwsSyncClientCustomizer =
        AwsSyncClientCustomizer { customization: AwsClientCustomizationContext, builder ->
            if (customization.serviceName == "appconfigdata") {
                val awsBuilder = builder as? AwsClientBuilder<*, *>
                    ?: error("test AppConfigData builder must implement AwsClientBuilder")
                awsBuilder.credentialsProvider(TEST_CREDENTIALS)
                awsBuilder.overrideConfiguration(
                    awsBuilder.overrideConfiguration()
                        .toBuilder()
                        .apiCallTimeout(Duration.ofMillis(500))
                        .apiCallAttemptTimeout(Duration.ofMillis(500))
                        .build(),
                )
            }
        }

    private companion object {
        val TEST_CREDENTIALS: AwsCredentialsProvider =
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create("synthetic-access-key", "synthetic-secret-key"),
            )
    }
}

@TestConfiguration(proxyBeanMethods = false)
internal class AppConfigProbeConfiguration {
    @Bean
    @ConfigurationProperties("appconfig")
    fun appConfigProbe(): AppConfigProbeProperties = AppConfigProbeProperties()

    @Bean
    fun appConfigValueProbe(@Value("\${appconfig.feature}") feature: String): AppConfigValueProbe =
        AppConfigValueProbe(feature)
}

@TestConfiguration(proxyBeanMethods = false)
internal class AppConfigCredentialsConfiguration {
    @Bean
    fun appConfigCredentialsProvider(): AwsCredentialsProvider =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create("synthetic-access-key", "synthetic-secret-key"),
        )
}

internal data class AppConfigProbeProperties(
    var feature: String? = null,
    var version: String? = null,
)

internal data class AppConfigValueProbe(
    val feature: String,
)

private class FakeAppConfigDataServer(
    private val payloads: List<String>,
    private val contentType: String = "text/plain",
    private val sessionStatus: Int = 200,
    private val latestStatus: Int = 200,
    private val errorType: String = "ResourceNotFoundException",
    private val delayPayloadIndex: Int? = null,
    private val delayMillis: Long = 0,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor: ExecutorService = Executors.newFixedThreadPool(2, NamedThreadFactory())
    private val payloadIndex = AtomicInteger()
    private val closed = AtomicBoolean()
    private val delayedLatestStarted = CountDownLatch(1)

    val endpoint: URI
        get() = URI("http://127.0.0.1:${server.address.port}")

    val applicationIdentifiers = CopyOnWriteArrayList<String>()
    val profileIdentifiers = CopyOnWriteArrayList<String>()
    val environmentIdentifiers = CopyOnWriteArrayList<String>()
    val tokenOrdinals = CopyOnWriteArrayList<Int>()
    val authorizationMarkers = CopyOnWriteArrayList<Boolean>()
    val requestCount = AtomicInteger()
    val activeRequests = AtomicInteger()
    val maxConcurrentRequests = AtomicInteger()

    fun start(): FakeAppConfigDataServer {
        server.executor = executor
        server.createContext("/configurationsessions") { exchange ->
            handle(exchange) {
                if (exchange.requestURI.path != "/configurationsessions") {
                    respond(exchange, 404, "", "text/plain")
                    return@handle
                }
                if (exchange.requestMethod != "POST") {
                    respond(exchange, 405, "", "text/plain")
                    return@handle
                }
                recordRequest(exchange)
                delayIfConfigured()
                if (sessionStatus == 200) {
                    respond(exchange, 200, "{\"InitialConfigurationToken\":\"synthetic-token-1\"}", "application/json")
                } else {
                    respondError(exchange, sessionStatus)
                }
            }
        }
        server.createContext("/configuration") { exchange ->
            handle(exchange) {
                if (exchange.requestURI.path != "/configuration") {
                    respond(exchange, 404, "", "text/plain")
                    return@handle
                }
                if (exchange.requestMethod != "GET") {
                    respond(exchange, 405, "", "text/plain")
                    return@handle
                }
                recordRequest(exchange)
                tokenOrdinals += tokenOrdinal(queryParameter(exchange.requestURI.rawQuery, "configuration_token"))
                val index = payloadIndex.getAndIncrement().coerceAtMost(payloads.lastIndex)
                exchange.responseHeaders.add("Next-Poll-Configuration-Token", "synthetic-token-${index + 2}")
                exchange.responseHeaders.add("Next-Poll-Interval-In-Seconds", "15")
                delayIfConfigured(index)
                if (latestStatus == 200) {
                    respond(exchange, 200, payloads[index], contentType)
                } else {
                    respondError(exchange, latestStatus)
                }
            }
        }
        server.start()
        return this
    }

    fun executorTerminated(): Boolean = executor.isTerminated

    fun awaitDelayedLatest(timeout: Long, unit: TimeUnit): Boolean = delayedLatestStarted.await(timeout, unit)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            server.stop(0)
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) {
                "fake AppConfig server executor did not terminate within 5 seconds"
            }
        }
    }

    private fun handle(exchange: HttpExchange, action: () -> Unit) {
        requestCount.incrementAndGet()
        val active = activeRequests.incrementAndGet()
        maxConcurrentRequests.updateAndGet { current -> maxOf(current, active) }
        try {
            action()
        } finally {
            activeRequests.decrementAndGet()
            exchange.close()
        }
    }

    private fun recordRequest(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        if (exchange.requestMethod == "POST") {
            jsonValue(body, "ApplicationIdentifier")?.let(applicationIdentifiers::add)
            jsonValue(body, "ConfigurationProfileIdentifier")?.let(profileIdentifiers::add)
            jsonValue(body, "EnvironmentIdentifier")?.let(environmentIdentifiers::add)
        }
        authorizationMarkers += exchange.requestHeaders.getFirst("Authorization").isNullOrBlank().not()
    }

    private fun jsonValue(body: String, name: String): String? =
        Regex("\\\"$name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(body)?.groupValues?.get(1)

    private fun delayIfConfigured(index: Int? = null) {
        if (delayMillis <= 0 || (delayPayloadIndex != null && delayPayloadIndex != index)) return
        if (index != null && delayPayloadIndex == index) delayedLatestStarted.countDown()
        Thread.sleep(delayMillis)
    }

    private fun queryParameter(query: String?, name: String): String =
        query.orEmpty()
            .split('&')
            .asSequence()
            .mapNotNull { pair ->
                val parts = pair.split('=', limit = 2)
                parts.takeIf { it.size == 2 && parts[0] == name }?.get(1)
            }
            .firstOrNull()
            .orEmpty()

    private fun tokenOrdinal(token: String): Int =
        token.substringAfterLast('-').toIntOrNull() ?: 0

    private fun respond(exchange: HttpExchange, status: Int, body: String, contentType: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun respondError(exchange: HttpExchange, status: Int) {
        exchange.responseHeaders.add("x-amzn-errortype", errorType)
        respond(exchange, status, "{\"message\":\"synthetic failure\"}", "application/json")
    }

    private class NamedThreadFactory : ThreadFactory {
        private val sequence = AtomicInteger()

        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "appconfig-test-${sequence.incrementAndGet()}").apply { isDaemon = true }
    }
}
