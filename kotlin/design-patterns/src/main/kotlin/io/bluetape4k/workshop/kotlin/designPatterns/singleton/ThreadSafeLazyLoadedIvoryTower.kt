package io.bluetape4k.workshop.kotlin.designPatterns.singleton

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * `ThreadSafeLazyLoadedIvoryTower` 는 스레드 안전한 방식으로 지연 초기화된 Singleton 인스턴스를 생성합니다.
 */
class ThreadSafeLazyLoadedIvoryTower private constructor() {

    companion object {
        private var instance: ThreadSafeLazyLoadedIvoryTower? = null

        private val lock = ReentrantLock()

        /**
         * 이 함수가 호출될 때 스레드 안전한 방식으로 Singleton 인스턴스를 생성합니다.
         */
        // @Synchronized  // Virtual Thread 때문이라도, synchronized 블록을 사용하지 않는 것이 좋습니다.
        fun getInstance(): ThreadSafeLazyLoadedIvoryTower {
            return lock.withLock {
                if (instance == null) {
                    instance = ThreadSafeLazyLoadedIvoryTower()
                }
                instance!!
            }
        }
    }

    init {
        // reflection을 통한 객체 생성을 방지합니다.
        if (instance == null) {
            instance = this
        } else {
            throw IllegalStateException("이미 객체가 생성되었습니다.")
        }
    }
}
