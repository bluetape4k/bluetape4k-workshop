package io.bluetape4k.workshop.lock.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.lock.domain.DeductionResult
import io.bluetape4k.workshop.lock.domain.DeductionResult.InsufficientStock
import io.bluetape4k.workshop.lock.domain.DeductionResult.Success
import io.bluetape4k.workshop.lock.domain.InventoryStore

/**
 * 고전적인 read-modify-write 경쟁을 보여주기 위해 의도적으로 안전하지 않게 만든 재고 서비스입니다.
 *
 * ## 동작 계약
 * - **어떤 lock도 사용하지 않고** 재고를 읽고, 경쟁 창을 넓히려고 1ms 쉰 뒤, 다시 씁니다.
 * - 여러 thread가 동시에 실행되면 oversell(음수 재고 또는 예상보다 큰 성공 건수)을 관측합니다.
 *
 * **운영 환경에서 사용하지 마세요.**
 */
class UnsafeInventoryService(private val store: InventoryStore) {

    companion object : KLogging()

    fun deduct(id: Long, qty: Int): DeductionResult {
        qty.requirePositiveNumber("qty")
        val current = store.currentStock(id)
        if (current < qty) return InsufficientStock(qty, current)
        Thread.sleep(1)  // 의도적인 경쟁 창
        val remaining = store.applyChange(id, -qty)
        return Success(remaining)
    }
}
