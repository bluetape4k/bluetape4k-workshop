package io.bluetape4k.workshop.lock.fenced

import io.bluetape4k.logging.KLogging
import java.util.concurrent.ConcurrentHashMap

/**
 * 리소스 id별 [FencedResource] 인스턴스를 보관하는 레지스트리입니다.
 *
 * ## 동작 계약
 * - [forResource]는 멱등적입니다. 같은 id에는 항상 같은 [FencedResource]를 반환합니다.
 * - [resetAll]은 모든 항목을 제거하며, 테스트 격리를 위한 `@BeforeEach`에서 사용합니다.
 * - 이 레지스트리는 **메모리 전용**입니다(워크숍 한계).
 *   JVM을 재시작하면 fencing token 이력이 사라지고, map 크기도 제한하지 않습니다.
 */
class FencedResources {

    companion object : KLogging()

    private val map = ConcurrentHashMap<Long, FencedResource>()

    fun forResource(id: Long): FencedResource = map.computeIfAbsent(id) { FencedResource(it) }

    fun reset(id: Long) {
        map.remove(id)
    }

    /** 모든 fencing token 상태를 지웁니다. `@BeforeEach` 테스트 격리에 사용합니다. */
    fun resetAll() {
        map.clear()
    }
}
