package io.bluetape4k.workshop.application.event.aspect

import org.springframework.context.ApplicationEvent
import kotlin.reflect.KClass

/**
 * Publishes an [ApplicationEvent] after the annotated method returns.
 *
 * [params] may contain a SpEL expression such as `#{#root.id}`. When omitted,
 * the intercepted method result is passed to the event constructor.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class AspectEventEmitter(
    val eventType: KClass<out ApplicationEvent>,
    val params: String = "",
)
