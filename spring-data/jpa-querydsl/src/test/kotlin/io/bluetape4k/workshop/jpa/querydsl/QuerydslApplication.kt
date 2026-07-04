package io.bluetape4k.workshop.jpa.querydsl

import io.bluetape4k.workshop.jpa.querydsl.services.InitMemberService
import jakarta.persistence.EntityManager
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.data.repository.config.BootstrapMode

@SpringBootApplication(proxyBeanMethods = false)
@EnableJpaAuditing(modifyOnCreate = true)
@EnableJpaRepositories(bootstrapMode = BootstrapMode.DEFERRED)
class QueryDslApplication {

    @Bean
    fun initMemberService(entityManager: EntityManager): InitMemberService = InitMemberService(entityManager)
}
