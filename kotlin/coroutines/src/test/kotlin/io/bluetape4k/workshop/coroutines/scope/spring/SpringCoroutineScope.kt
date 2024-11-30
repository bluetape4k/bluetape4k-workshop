package io.bluetape4k.workshop.coroutines.scope.spring

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.springframework.beans.factory.DisposableBean
import kotlin.coroutines.CoroutineContext

/**
 * Spring Bean을 [CoroutineScope] 에서 사용하기 위한 인터페이스.
 *
 * [SpringCoroutineScope]가 [DisposableBean] 을 구현하여 Bean이 소멸될 때, [Job] 을 취소할 수 있도록 한다.
 *
 * // 사용 예: 일반적인 [Job]을 사용할 때
 * ```
 * class MyBean(dispatcher: CoroutineDispatcher)
 *   : SpringCoroutineScope by SpringCoroutineScope(dispatcher) {
 *      // ...
 * }
 * ```
 *
 * // 사용 예: [kotlinx.coroutines.SupervisorJob]을 사용할 때
 * ```
 * class MySuperBean(dispatcher: CoroutineDispatcher)
 *   : SpringCoroutineScope by SpringCoroutineScope(dispatcher + SupervisorJob()) {
 *      // ...
 * }
 * ```
 */
interface SpringCoroutineScope: CoroutineScope, DisposableBean {
    val job: Job
}

@Suppress("TestFunctionName")
fun SpringCoroutineScope(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    job: Job = Job(),
): SpringCoroutineScope = SpringCoroutineScope(dispatcher + job)

@Suppress("TestFunctionName")
fun SpringCoroutineScope(coroutineContext: CoroutineContext): SpringCoroutineScope {
    return object: SpringCoroutineScope, CoroutineScope by CoroutineScope(coroutineContext), DisposableBean {
        override val job: Job
            get() = coroutineContext[Job]!!

        override fun destroy() {
            job.cancel()
        }
    }
}


abstract class AbstractSpringCoroutineScope(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    job: Job = Job(),
): SpringCoroutineScope, CoroutineScope by CoroutineScope(dispatcher + job), DisposableBean {
    override val job: Job
        get() = coroutineContext[Job]!!

    override fun destroy() {
        job.cancel()
    }
}
