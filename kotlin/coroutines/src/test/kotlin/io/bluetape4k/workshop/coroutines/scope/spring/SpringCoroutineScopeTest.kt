package io.bluetape4k.workshop.coroutines.scope.spring

import io.bluetape4k.junit5.output.OutputCapture
import io.bluetape4k.junit5.output.OutputCapturer
import io.bluetape4k.support.uninitialized
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.getBean
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension


@OutputCapture
@ContextConfiguration(
    classes = [SpringCoroutineScopeTest.TestConfig::class]
)
@ExtendWith(SpringExtension::class)
class SpringCoroutineScopeTest {

    @Configuration
    class TestConfig {

        @Bean(destroyMethod = "destroy")
        fun myBean(): MyBean {
            return MyBean()
        }
    }

    class MyBean: SpringCoroutineScope by SpringCoroutineScope() {
        suspend fun run(input: Int) =
            withContext(coroutineContext) {
                delay(1000)
                input
            }

        override fun destroy() {
            println("destroy MyBean")
        }
    }

    @Autowired
    private val applicationContext: ApplicationContext = uninitialized()

    @Autowired
    private val myBean: MyBean = uninitialized()

    @Test
    fun `context loading`() {
        myBean.shouldNotBeNull()
    }

    @Test
    fun `destroy bean`(capturer: OutputCapturer) {
        val myBean2 = applicationContext.getBean<MyBean>()
        myBean2.shouldNotBeNull()
        myBean2.destroy()

        Thread.sleep(10)

        val capture = capturer.capture()
        capture shouldContain "destroy MyBean"
    }

}
