package io.bluetape4k.workshop.commerce.voucher.eventsourced.security

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventSourcedExposedDatabaseRegistration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Instant
import java.util.Base64

@ConfigurationProperties("voucher.security.hmac")
internal data class EventSourcedHmacProperties(
    val activeVersion: Int = 1,
    val activeKeyBase64: String = "",
    val retired: List<RetiredHmacKeyProperties> = emptyList(),
)

internal data class RetiredHmacKeyProperties(
    val version: Int,
    val keyBase64: String,
    val retainUntil: Instant,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EventSourcedHmacProperties::class)
internal class EventSourcedIdentitySecurityConfiguration {
    @Bean
    fun eventSourcedHmacKeyRing(
        properties: EventSourcedHmacProperties,
        clock: Clock,
    ): EventSourcedHmacKeyRing =
        EventSourcedHmacKeyRing(
            active =
                EventSourcedHmacKey(
                    version = properties.activeVersion.requirePositiveNumber("voucher.security.hmac.active-version"),
                    material = decodeKey(properties.activeKeyBase64, "voucher.security.hmac.active-key-base64"),
                ),
            retired =
                properties.retired
                    .filter { retired -> retired.retainUntil.isAfter(clock.instant()) }
                    .map { retired ->
                        EventSourcedHmacKey(
                            version = retired.version.requirePositiveNumber("voucher.security.hmac.retired.version"),
                            material = decodeKey(retired.keyBase64, "voucher.security.hmac.retired.key-base64"),
                        )
                    },
        )

    @Bean
    fun subjectIdentityRepository(
        keyRing: EventSourcedHmacKeyRing,
        clock: Clock,
    ): SubjectIdentityRepository = SubjectIdentityRepository(keyRing, clock)

    @Bean
    fun subjectIdentityService(
        registration: EventSourcedExposedDatabaseRegistration,
        permits: EventSourcedDatabasePermitGate,
        repository: SubjectIdentityRepository,
    ): SubjectIdentityService =
        ExposedSubjectIdentityService(
            database = registration.database,
            permits = permits,
            repository = repository,
        )

    private fun decodeKey(
        encoded: String,
        propertyName: String,
    ): ByteArray =
        Base64
            .getDecoder()
            .decode(encoded.requireNotBlank(propertyName))
}
