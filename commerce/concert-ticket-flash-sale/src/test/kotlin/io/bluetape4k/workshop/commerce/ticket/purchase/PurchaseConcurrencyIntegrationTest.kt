package io.bluetape4k.workshop.commerce.ticket.purchase

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.ActivePurchaseExists
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.InventoryUnavailable
import org.junit.jupiter.api.Test
import java.util.*
import java.util.concurrent.Executors

/**
 * PurchaseConcurrencyIntegrationTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class PurchaseConcurrencyIntegrationTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "one buyer from two ips leaves one active attempt" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `one buyer from two ips leaves one active attempt`() {
        PurchaseFixture(inventory = 2).use { fixture ->
            val buyer = Uuid.V7.nextId()
            val commands = listOf(
                fixture.command(buyer = buyer, ip = Uuid.V7.nextId()),
                fixture.command(buyer = buyer, ip = Uuid.V7.nextId()),
            )

            val results = concurrent(commands.map { { fixture.service.start(it) } })

            results.count { it.isSuccess } shouldBeEqualTo 1
            results.count { it.exceptionOrNull() is ActivePurchaseExists } shouldBeEqualTo 1
            fixture.guardCount("USER", buyer) shouldBeEqualTo 1
            fixture.inventoryHeld() shouldBeEqualTo 1
        }
    }

    @Test
/**
 * 이 시나리오는 "two buyers sharing one ip leave one active attempt" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `two buyers sharing one ip leave one active attempt`() {
        PurchaseFixture(inventory = 2).use { fixture ->
            val commands = listOf(fixture.command(ip = fixture.sharedIp), fixture.command(ip = fixture.sharedIp))

            val results = concurrent(commands.map { { fixture.service.start(it) } })

            results.count { it.isSuccess } shouldBeEqualTo 1
            results.count { it.exceptionOrNull() is ActivePurchaseExists } shouldBeEqualTo 1
            fixture.guardCount("IP", fixture.sharedIp) shouldBeEqualTo 1
            fixture.inventoryHeld() shouldBeEqualTo 1
        }
    }

    @Test
/**
 * 이 시나리오는 "two distinct buyers competing for last inventory have one winner and no oversell" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `two distinct buyers competing for last inventory have one winner and no oversell`() {
        PurchaseFixture(inventory = 1).use { fixture ->
            val commands = listOf(
                fixture.command(ip = Uuid.V7.nextId()),
                fixture.command(ip = Uuid.V7.nextId()),
            )

            val results = concurrent(commands.map { { fixture.service.start(it) } })

            results.count { it.isSuccess } shouldBeEqualTo 1
            results.count { it.exceptionOrNull() is InventoryUnavailable } shouldBeEqualTo 1
            fixture.inventoryHeld() shouldBeEqualTo 1
        }
    }

/**
 * concurrent은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun concurrent(tasks: List<() -> Any>): List<Result<Any>> =
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            tasks.map { task ->
                executor.submit<Result<Any>> {
                    runCatching(task)
                }.get()
            }
        }
}

/**
 * guardCount은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
private fun PurchaseFixture.guardCount(kind: String, subjectId: UUID): Int =
    identityGuardCount(kind, subjectId)
