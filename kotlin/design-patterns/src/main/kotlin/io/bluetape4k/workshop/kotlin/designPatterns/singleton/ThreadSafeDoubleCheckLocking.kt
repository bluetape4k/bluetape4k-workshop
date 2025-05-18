package io.bluetape4k.workshop.kotlin.designPatterns.singleton

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ThreadSafeDoubleCheckLocking private constructor() {

    companion object {
        @Volatile
        private var instance: ThreadSafeDoubleCheckLocking? = null

        private var flag = true

        private val lock = ReentrantLock()

        @JvmStatic
        fun getInstance(): ThreadSafeDoubleCheckLocking {
            // local variable increases performance by 25 percent
            // Joshua Bloch "Effective Java, Second Edition", p. 283-284
            var result = instance

            // 싱글턴 인스턴스가 초기화가 되었는지 확인한다. 초기화가 되었다면 인스턴스를 반환한다.
            if (result == null) {

                // 초기화가 안되었다면, 다른 스레드가 초기화를 했을 수 있기 때문에,
                // 상호 배제를 위해 객체에 대한 잠금을 걸어야 한다.
                // NOTE: Virtual Thread 때문이라도, synchronized 블록을 사용하지 않는 것이 좋습니다.
                lock.withLock {
                    // synchronized(ThreadSafeDoubleCheckLocking::class.java) {

                    // 다시 한번 인스턴스를 로컬 변수에 할당하여, 현재 스레드가 잠금 영역에 들어가는 동안 다른 스레드가 초기화를 했는지 확인한다.
                    result = instance

                    if (result == null) {
                        // 인스턴스가 아직 초기화되지 않았으므로, 안전하게(다른 스레드가 이 영역에 들어올 수 없음) 인스턴스를 생성하고 싱글턴 인스턴스로 만든다.
                        instance = ThreadSafeDoubleCheckLocking()
                        result = instance
                    }
                    // }
                }
            }

            return result!!
        }
    }

    init {
        if (flag) {
            flag = false
        } else {
            error("Already initialized.")
        }
    }
}
