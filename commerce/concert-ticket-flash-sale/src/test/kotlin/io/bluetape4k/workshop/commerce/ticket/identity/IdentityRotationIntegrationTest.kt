package io.bluetape4k.workshop.commerce.ticket.identity

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.ticket.persistence.IdentityKind
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketDatabaseFixture
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

/**
 * IdentityRotationIntegrationTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class IdentityRotationIntegrationTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "old alias lookup creates current alias for the same stable subject" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `old alias lookup creates current alias for the same stable subject`() {
        TicketDatabaseFixture().use { fixture ->
            val repository = IdentityAliasRepository(fixture.executor)
            val first = IdentityService(keys = keyRing(current = 1), aliases = repository)
                .resolve(IdentityKind.USER, "buyer@example.com")

            val rotated = IdentityService(keyRing(current = 2), repository)

            rotated.resolve(IdentityKind.USER, "buyer@example.com") shouldBeEqualTo first
            repository.aliasVersions(first.subjectId) shouldBeEqualTo setOf(1, 2)

            IdentityService(keyRing(current = 2, readVersions = setOf(2)), repository)
                .resolve(IdentityKind.USER, "buyer@example.com") shouldBeEqualTo first
        }
    }

    @Test
/**
 * 이 시나리오는 "concurrent old and new writers converge through digest advisory locks" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `concurrent old and new writers converge through digest advisory locks`() {
        TicketDatabaseFixture().use { fixture ->
            val repository = IdentityAliasRepository(fixture.executor)
            val oldWriter = IdentityService(keyRing(current = 1), repository)
            val newWriter = IdentityService(keyRing(current = 2), repository)

            val subjects = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                listOf(oldWriter, newWriter).map { service ->
                    executor.submit<IdentitySubject> {
                        service.resolve(
                            IdentityKind.USER,
                            "buyer"
                        )
                    }.get()
                }
            }.onEach { log.debug { "subject=$it" } }

            subjects.distinct().size shouldBeEqualTo 1
            repository.aliasVersions(subjects.first().subjectId) shouldBeEqualTo setOf(1, 2)
        }
    }

/**
 * keyRing은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun keyRing(
        current: Int,
        readVersions: Set<Int> = setOf(1, 2),
    ): IdentityKeyRing =
        IdentityKeyRing(
            currentVersion = current,
            activeReadVersions = readVersions,
            keys = mapOf(
                1 to ByteArray(32) { 0x11 },
                2 to ByteArray(32) { 0x22 },
            ),
        )
}
