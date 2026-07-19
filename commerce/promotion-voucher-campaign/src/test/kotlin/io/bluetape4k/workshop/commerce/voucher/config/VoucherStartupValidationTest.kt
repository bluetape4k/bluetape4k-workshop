package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.util.stream.Stream

internal class VoucherStartupValidationTest {
    @Test
    fun `unknown properties are rejected and sanitized`() {
        val annotation = VoucherProperties::class.java.getAnnotation(ConfigurationProperties::class.java)

        annotation.ignoreUnknownFields.shouldBeFalse()
        sanitizedStartupCode(
            IllegalStateException("The elements [workshop.voucher.unexpected] were left unbound."),
        ) shouldBeEqualTo StartupFailureCode.UNKNOWN_PROPERTY
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidProductionConfigurations")
    fun `unsafe production configuration fails with a sanitized code`(case: InvalidConfiguration) {
        val validator = VoucherStartupValidator(case.referencedKeyVersions)

        val failure =
            assertFailsWith<VoucherStartupException> {
                validator.validate(case.properties, case.environment)
            }

        sanitizedStartupCode(failure) shouldBeEqualTo case.expectedCode
        failure.message shouldBeEqualTo case.expectedCode.name
    }

    internal data class InvalidConfiguration(
        val name: String,
        val properties: VoucherProperties,
        val environment: VoucherRuntimeEnvironment = productionEnvironment(),
        val referencedKeyVersions: ReferencedKeyVersionSource = ReferencedKeyVersionSource.NONE,
        val expectedCode: StartupFailureCode,
    ) {
        override fun toString(): String = name
    }

    companion object {
        private const val CURRENT_VERSION = 7

        @JvmStatic
        fun invalidProductionConfigurations(): Stream<InvalidConfiguration> {
            val valid = validProperties()
            val generationSecret = valid.keys.generation.getValue(CURRENT_VERSION)

            return Stream.of(
                InvalidConfiguration(
                    name = "invalid permit range",
                    properties = valid.copy(db = valid.db.copy(foregroundPermits = 13)),
                    expectedCode = StartupFailureCode.INVALID_RANGE,
                ),
                InvalidConfiguration(
                    name = "missing current key",
                    properties = valid.copy(keys = valid.keys.copy(generation = emptyMap())),
                    expectedCode = StartupFailureCode.MISSING_KEY,
                ),
                InvalidConfiguration(
                    name = "weak key",
                    properties =
                        valid.copy(
                            keys = valid.keys.copy(generation = mapOf(CURRENT_VERSION to "too-short")),
                        ),
                    expectedCode = StartupFailureCode.WEAK_KEY,
                ),
                InvalidConfiguration(
                    name = "known test key",
                    properties =
                        valid.copy(
                            keys =
                                valid.keys.copy(
                                    generation = mapOf(CURRENT_VERSION to "test-generation-secret-000000000000000"),
                                ),
                        ),
                    expectedCode = StartupFailureCode.TEST_KEY_FORBIDDEN,
                ),
                InvalidConfiguration(
                    name = "current and read key ring mismatch",
                    properties = valid.copy(keys = valid.keys.copy(activeReadVersions = setOf(6))),
                    expectedCode = StartupFailureCode.INVALID_KEY_RING,
                ),
                InvalidConfiguration(
                    name = "generation key reused for identity",
                    properties = valid.copy(keys = valid.keys.copy(identity = generationSecret)),
                    expectedCode = StartupFailureCode.DOMAIN_KEY_REUSE,
                ),
                InvalidConfiguration(
                    name = "persisted key version missing",
                    properties = valid,
                    referencedKeyVersions =
                        ReferencedKeyVersionSource {
                            ReferencedKeyVersions(generation = setOf(6), verification = setOf(CURRENT_VERSION))
                        },
                    expectedCode = StartupFailureCode.REFERENCED_KEY_MISSING,
                ),
                InvalidConfiguration(
                    name = "public demo bind",
                    properties = valid,
                    environment =
                        VoucherRuntimeEnvironment(
                            activeProfiles = setOf("prod", "demo"),
                            serverAddress = "0.0.0.0",
                            hikariMaximumPoolSize = 16,
                        ),
                    expectedCode = StartupFailureCode.PUBLIC_DEMO_BIND,
                ),
            )
        }

        private fun validProperties(): VoucherProperties =
            VoucherProperties(
                db =
                    VoucherDatabaseProperties(
                        foregroundPermits = 12,
                        backgroundPermits = 4,
                        permitTimeout = Duration.ofMillis(250),
                        lockTimeout = Duration.ofSeconds(5),
                    ),
                keys =
                    VoucherKeyProperties(
                        currentVersion = CURRENT_VERSION,
                        activeReadVersions = setOf(CURRENT_VERSION),
                        generation =
                            mapOf(CURRENT_VERSION to "production-generation-secret-00000000001"),
                        verification =
                            mapOf(CURRENT_VERSION to "production-verification-secret-000000001"),
                        identity = "production-identity-secret-0000000000001",
                        risk = "production-risk-secret-00000000000000001",
                        redisSlot = "production-redis-slot-secret-00000000001",
                    ),
            )

        private fun productionEnvironment(): VoucherRuntimeEnvironment =
            VoucherRuntimeEnvironment(
                activeProfiles = setOf("prod"),
                serverAddress = "127.0.0.1",
                hikariMaximumPoolSize = 16,
            )
    }
}
