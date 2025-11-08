package io.bluetape4k.workshop.jmolecules.example.jpa

import io.bluetape4k.workshop.jmolecules.example.jpa.customer.CustomerService
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.TestConstructor
import org.springframework.test.context.TestConstructor.AutowireMode

@SpringBootTest
@TestConstructor(autowireMode = AutowireMode.ALL)
class ApplicationIntegrationTests(
    @param:Autowired val context: ConfigurableApplicationContext,
) {

    @PersistenceContext
    lateinit var em: EntityManager

    @Test
    fun `context loading`() {
        context.shouldNotBeNull()
        ::em.isInitialized.shouldBeTrue()
    }

    @Disabled("아직 테스트 중입니다")
    @Test
    fun `bootstraps container`() {
        assertThat(AssertableApplicationContext.get { context })
            .hasSingleBean(CustomerService::class.java)
            .satisfies({ ctx ->
                ctx.publishEvent(CustomerService.SampleEvent())

                val bean = ctx.getBean(CustomerService::class.java)
                bean.eventReceived.shouldBeTrue()
            })


//        val customerService = context[CustomerService::class]
//        context.publishEvent(CustomerService.SampleEvent())
//        customerService.eventReceived.shouldBeTrue()
    }
}
