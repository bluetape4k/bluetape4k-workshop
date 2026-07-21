package io.bluetape4k.workshop.commerce.ticket.identity

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.ticket.persistence.IdentityKind
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketDatabaseFixture
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

internal class IdentityRotationIntegrationTest {
    @Test
    fun `old alias lookup creates current alias for the same stable subject`() {
        TicketDatabaseFixture().use { fixture ->
            val repository = IdentityAliasRepository(fixture.executor)
            val first = IdentityService(keyRing(current = 1), repository).resolve(IdentityKind.USER, "buyer@example.com")
            val rotated = IdentityService(keyRing(current = 2), repository)

            rotated.resolve(IdentityKind.USER, "buyer@example.com") shouldBeEqualTo first
            repository.aliasVersions(first.subjectId) shouldBeEqualTo setOf(1, 2)
            IdentityService(keyRing(current = 2, readVersions = setOf(2)), repository)
                .resolve(IdentityKind.USER, "buyer@example.com") shouldBeEqualTo first
        }
    }

    @Test
    fun `concurrent old and new writers converge through digest advisory locks`() {
        TicketDatabaseFixture().use { fixture ->
            val repository = IdentityAliasRepository(fixture.executor)
            val oldWriter = IdentityService(keyRing(current = 1), repository)
            val newWriter = IdentityService(keyRing(current = 2), repository)

            val subjects =
                Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                    listOf(oldWriter, newWriter)
                        .map { service -> executor.submit<IdentitySubject> { service.resolve(IdentityKind.USER, "buyer") } }
                        .map { it.get() }
                }

            subjects.distinct().size shouldBeEqualTo 1
            repository.aliasVersions(subjects.first().subjectId) shouldBeEqualTo setOf(1, 2)
        }
    }

    private fun keyRing(
        current: Int,
        readVersions: Set<Int> = setOf(1, 2),
    ): IdentityKeyRing =
        IdentityKeyRing(
            currentVersion = current,
            activeReadVersions = readVersions,
            keys =
                mapOf(
                    1 to ByteArray(32) { 0x11 },
                    2 to ByteArray(32) { 0x22 },
                ),
        )
}
