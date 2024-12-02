package io.bluetape4k.workshop.spring.modulith.events.util

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.springframework.aop.Advisor
import org.springframework.aop.aspectj.AspectJExpressionPointcut
import org.springframework.aop.framework.Advised
import org.springframework.aop.interceptor.CustomizableTraceInterceptor
import org.springframework.aop.support.DefaultPointcutAdvisor
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

@Configuration
class LoggingConfiguration {

    companion object: KLogging()

    @Bean
    fun interceptor(): CustomizableTraceInterceptor {
        return CustomizableTraceInterceptor().apply {
            setEnterMessage("Entering $[methodName]($[arguments])")
            setExitMessage("Leaving $[methodName](..) with return value $[returnValue], took $[invocationTime]ms.")
            setExceptionMessage("Exception in $[methodName] $[exception]")
            setLogExceptionStackTrace(false)
        }
    }

    @Bean
    fun traceAdvisor(interceptor: CustomizableTraceInterceptor): Advisor {
        val pointcut = AspectJExpressionPointcut().apply {
            expression = "bean(orderManagement)"
        }

        return DefaultPointcutAdvisor(pointcut, interceptor).apply {
            order = Ordered.LOWEST_PRECEDENCE
        }
    }

    @Bean
    fun foo(): Foo = Foo()

    open class Foo: BeanPostProcessor {

        companion object: KLogging()

        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any? {
            if (beanName != "orderManagement") {
                return bean
            }

            log.debug { "Reordering advisors for bean $beanName" }

            val orders = Advised::class.java.cast(bean)
            val advisors = orders.advisors

            val foo = advisors[advisors.size - 1]
            val bar = advisors[advisors.size - 2]

            advisors[advisors.size - 2] = foo
            advisors[advisors.size - 1] = bar

            return orders
        }
    }
}
