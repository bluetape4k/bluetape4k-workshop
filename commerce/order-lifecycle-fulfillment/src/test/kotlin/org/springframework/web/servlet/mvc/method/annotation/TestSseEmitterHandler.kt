package org.springframework.web.servlet.mvc.method.annotation

import org.springframework.http.MediaType
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.function.Consumer

/** servlet container 없이 실제 emitter callback을 실행하는 test-only bridge입니다. */
/** servlet container 없이 실제 emitter callback을 실행하는 test-only bridge입니다. */
internal class TestSseEmitterHandler: ResponseBodyEmitter.Handler {
/**
 * sent은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val sent = ConcurrentLinkedQueue<String>()
/**
 * timeoutCallback은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var timeoutCallback: Runnable? = null
/**
 * errorCallback은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var errorCallback: Consumer<Throwable>? = null
/**
 * completionCallback은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var completionCallback: Runnable? = null

/**
 * send은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun send(
        data: Any,
        mediaType: MediaType?,
    ) {
        sent += data.toString()
    }

/**
 * send은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun send(data: Set<ResponseBodyEmitter.DataWithMediaType>) {
        sent += data.joinToString(separator = "") { it.data.toString() }
    }

/**
 * complete은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun complete() {
        completionCallback?.run()
    }

/**
 * completeWithError은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun completeWithError(failure: Throwable) {
        errorCallback?.accept(failure)
        completionCallback?.run()
    }

/**
 * onTimeout은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun onTimeout(callback: Runnable) {
        timeoutCallback = callback
    }

/**
 * onError은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun onError(callback: Consumer<Throwable>) {
        errorCallback = callback
    }

/**
 * onCompletion은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun onCompletion(callback: Runnable) {
        completionCallback = callback
    }

/**
 * triggerTimeout은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun triggerTimeout() = timeoutCallback?.run()

/**
 * triggerDisconnect은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun triggerDisconnect() = completionCallback?.run()
}

/**
 * attachTestHandler은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
internal fun SseEmitter.attachTestHandler(handler: TestSseEmitterHandler) = initialize(handler)
