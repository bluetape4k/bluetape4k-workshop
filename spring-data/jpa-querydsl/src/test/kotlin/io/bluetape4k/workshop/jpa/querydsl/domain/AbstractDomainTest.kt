package io.bluetape4k.workshop.jpa.querydsl.domain

import io.bluetape4k.workshop.jpa.querydsl.AbstractQuerydslTest
import io.bluetape4k.workshop.jpa.querydsl.services.InitMemberService
import org.junit.jupiter.api.BeforeAll
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager

abstract class AbstractDomainTest(
    private val initMemberService: InitMemberService,
    tem: TestEntityManager,
) : AbstractQuerydslTest(tem) {

    @BeforeAll
    fun beforeAll() {
        initMemberService.init()
    }
}
