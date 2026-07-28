package io.bluetape4k.workshop.application.event.aspect

import org.springframework.context.ApplicationEvent
import kotlin.reflect.KClass

/**
 * annotation 이 붙은 method 가 반환된 뒤 [ApplicationEvent] 를 publish 합니다.
 *
 * [params] 에는 `#{#root.id}` 같은 SpEL expression 을 넣을 수 있습니다.
 * 생략하면 intercept 된 method result 를 event constructor 로 전달합니다.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class AspectEventEmitter(
    val eventType: KClass<out ApplicationEvent>,
    val params: String = "",
)
