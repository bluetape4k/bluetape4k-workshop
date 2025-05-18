package io.bluetape4k.workshop.kotlin.designPatterns.singleton

/**
 * Initialize-on-demand-holder 방식은 지연 초기화된 Singleton 인스턴스를 생성하는 방법 중 하나입니다.
 *
 * 이 방식은 스레드 안전하며, `getInstance()` 메서드가 호출될 때 Singleton 인스턴스를 생성합니다.
 *
 * 내부 클래스는 getInstance() 메서드가 호출될 때 로드되며, 이 때 Singleton 인스턴스를 생성합니다.
 */
class InitializingOnDemandHolderIdiom private constructor() {

    companion object {
        fun getInstance(): InitializingOnDemandHolderIdiom = HelperHolder.INSTANCE
    }

    class HelperHolder {
        companion object {
            val INSTANCE = InitializingOnDemandHolderIdiom()
        }
    }
}
