package io.bluetape4k.workshop.aws.settings

import aws.sdk.kotlin.services.secretsmanager.SecretsManagerClient
import aws.sdk.kotlin.services.secretsmanager.model.ResourceNotFoundException
import aws.sdk.kotlin.services.secretsmanager.model.SecretsManagerException
import aws.sdk.kotlin.services.ssm.SsmClient
import aws.sdk.kotlin.services.ssm.model.AccessDeniedException
import aws.sdk.kotlin.services.ssm.model.ParameterNotFound
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.kotlin.secretsmanager.AwsSecretValue
import io.mockk.clearMocks
import io.mockk.verify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SettingsBoundaryTest {

    private val secret = AwsSecretValue.of("top-secret-value")
    private val secretsManagerClient = mockk<SecretsManagerClient>(relaxed = true)
    private val ssmClient = mockk<SsmClient>(relaxed = true)

    @BeforeEach
    fun setUp() {
        clearMocks(secretsManagerClient, ssmClient)
    }

    @Test
    fun `secrets manager success is exposed through the provider neutral contract`() = runTest {
        val result = SecretsManagerSettingsSource(
            clientFactory = { secretsManagerClient },
            loadSecret = { _, _ -> secret },
        )
            .resolve("database/password")

        result shouldBeEqualTo SettingsResolution.Found(secret)
        verify(exactly = 1) { secretsManagerClient.close() }
    }

    @Test
    fun `secrets manager missing and denied failures are classified without leaking payload`() = runTest {
        val source = SecretsManagerSettingsSource(
            clientFactory = { secretsManagerClient },
            loadSecret = { _, key ->
                when (key) {
                    "missing" -> throw ResourceNotFoundException {}
                    else -> throw SecretsManagerException("AccessDeniedException")
                }
            },
        )

        source.resolve("missing") shouldBeEqualTo SettingsResolution.Missing
        source.resolve("denied") shouldBeEqualTo SettingsResolution.Denied
        source.resolve("denied").toString() shouldNotContain "top-secret-value"
    }

    @Test
    fun `parameter store secure value uses the same contract`() = runTest {
        val result = ParameterStoreSettingsSource(
            clientFactory = { ssmClient },
            loadParameter = { _, _ -> secret },
        )
            .resolve("database/password")

        result shouldBeEqualTo SettingsResolution.Found(secret)
        verify(exactly = 1) { ssmClient.close() }
    }

    @Test
    fun `parameter store missing and denied failures are classified`() = runTest {
        val source = ParameterStoreSettingsSource(
            clientFactory = { ssmClient },
            loadParameter = { _, key ->
                when (key) {
                    "missing" -> throw ParameterNotFound {}
                    else -> throw AccessDeniedException {}
                }
            },
        )

        source.resolve("missing") shouldBeEqualTo SettingsResolution.Missing
        source.resolve("denied") shouldBeEqualTo SettingsResolution.Denied
    }

    @Test
    fun `startup fail fast reports missing without including a secret`() = runTest {
        val source = SettingsSource { SettingsResolution.Missing }
        val resolver = SettingsResolver(source)

        val failure = assertFailsWith<SettingsUnavailableException> {
            resolver.startup(setOf("database/password"))
        }

        failure.resolution shouldBeEqualTo SettingsResolution.Missing
        failure.message.orEmpty() shouldNotContain "top-secret-value"
    }

    @Test
    fun `refresh is a full replacement and never reuses an old secret`() = runTest {
        var calls = 0
        val source = SettingsSource {
            calls += 1
            if (calls == 1) SettingsResolution.Found(secret) else SettingsResolution.Missing
        }
        val resolver = SettingsResolver(source)

        val initial = resolver.startup(setOf("database/password"))
        val refreshed = resolver.refresh(setOf("database/password"))

        initial.resolve("database/password") shouldBeEqualTo SettingsResolution.Found(secret)
        refreshed.resolve("database/password") shouldBeEqualTo SettingsResolution.Missing
        refreshed.redactedEntries()["database/password"] shouldBeEqualTo "<missing>"
        refreshed.toString() shouldNotContain "top-secret-value"
    }

    @Test
    fun `omit policy keeps classified failures while returning found values`() = runTest {
        val source = SettingsSource { key ->
            when (key) {
                "database/password" -> SettingsResolution.Found(secret)
                "missing" -> SettingsResolution.Missing
                else -> SettingsResolution.Denied
            }
        }
        val resolver = SettingsResolver(
            source = source,
            startupPolicy = SettingsFallbackPolicy.omit(),
            refreshPolicy = SettingsFallbackPolicy.omit(),
        )

        val snapshot = resolver.startup(setOf("database/password", "missing", "denied"))

        snapshot.resolve("database/password") shouldBeEqualTo SettingsResolution.Found(secret)
        snapshot.resolve("missing") shouldBeEqualTo SettingsResolution.Missing
        snapshot.resolve("denied") shouldBeEqualTo SettingsResolution.Denied
        snapshot.redactedEntries().toString() shouldNotContain "top-secret-value"
    }

    @Test
    fun `unclassified provider failure keeps its identity`() = runTest {
        val expected = IllegalStateException("provider unavailable")
        val source = SettingsSource { throw expected }

        val actual = assertFailsWith<IllegalStateException> {
            SettingsResolver(source).startup(setOf("database/password"))
        }

        actual shouldBeSameInstanceAs expected
    }

    @Test
    fun `provider cancellation keeps its identity and still closes the client`() = runTest {
        val expected = CancellationException("cancelled")
        val source = SecretsManagerSettingsSource(
            clientFactory = { secretsManagerClient },
            loadSecret = { _, _ -> throw expected },
        )

        val actual = assertFailsWith<CancellationException> {
            source.resolve("database/password")
        }

        actual shouldBeSameInstanceAs expected
        verify(exactly = 1) { secretsManagerClient.close() }
    }
}
