package io.bluetape4k.workshop.application.event.aspect

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Pointcut
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ApplicationEventPublisherAware
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.stereotype.Component

/**
 * [AspectEventEmitter] 로 선언된 event 를 [ApplicationEventPublisher] 로 publish 합니다.
 */
@Component
@Aspect
class AspectEventPublisherAspect : ApplicationEventPublisherAware {

    companion object : KLogging() {

        private val spelRegex = "^#\\{(.*)}$".toRegex()

        @JvmStatic
        private fun String.isSpel(): Boolean {
            return spelRegex.matches(this)
        }
    }

    private val expressionParser = SpelExpressionParser()

    private lateinit var publisher: ApplicationEventPublisher

    override fun setApplicationEventPublisher(applicationEventPublisher: ApplicationEventPublisher) {
        publisher = applicationEventPublisher
    }

    // named pointcut 으로 @Around 가 annotation argument 를 binding 할 수 있게 합니다.
    @Pointcut("@annotation(aspectEventEmitter)")
    fun pointcut(aspectEventEmitter: AspectEventEmitter) {
        // 아무 작업도 하지 않습니다.
    }

    @Around(
        value = "pointcut(aspectEventEmitter)",
        argNames = "aspectEventEmitter"
    )
    fun around(joinPoint: ProceedingJoinPoint, aspectEventEmitter: AspectEventEmitter): Any? {
        log.debug { "joinPoint=$joinPoint, aspectEventEmitter=$aspectEventEmitter" }

        val result = joinPoint.proceed()
        try {
            doPublishEvent(joinPoint, aspectEventEmitter, result)
        } catch (e: Throwable) {
            log.error(e) { "Fail to aspect around for @AspectEventEmitter ... joinPoint=$joinPoint" }
        }
        return result
    }

    private fun doPublishEvent(
        joinPoint: ProceedingJoinPoint,
        aspectEventEmitter: AspectEventEmitter,
        result: Any?,
    ) {
        val event = when {
            aspectEventEmitter.params.isSpel() -> {
                // params 를 parse 하고 평가된 값을 event constructor argument 로 제공합니다.
                val spel = aspectEventEmitter.params.replace(spelRegex, "$1")
                log.debug { "spel=$spel" }
                val arg = expressionParser.parseExpression(spel).getValue(result)
                log.debug { "build event[${aspectEventEmitter.eventType.simpleName}] with arg=$arg, result=$result" }
                aspectEventEmitter.eventType.constructors.first().call(joinPoint.target, arg)
            }

            else                               -> {
                // 기본적으로 intercept 된 method result 를 event constructor 로 전달합니다.
                log.debug { "build event[${aspectEventEmitter.eventType.simpleName}] with result=$result" }
                aspectEventEmitter.eventType.constructors.first().call(joinPoint.target, result)
            }
        }
        log.debug { "Publish event. event=$event" }
        publisher.publishEvent(event)
    }
}
