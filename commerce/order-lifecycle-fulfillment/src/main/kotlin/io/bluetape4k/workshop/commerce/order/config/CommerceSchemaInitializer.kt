package io.bluetape4k.workshop.commerce.order.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.commerce.order.persistence.commerceTables
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
/**
 * CommerceSchemaInitializer은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class CommerceSchemaInitializer: ApplicationRunner {

    companion object: KLogging()

    /**
     * run은 주문 라이프사이클과 fulfillment 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param args 업무 처리에 필요한 args 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Transactional
    override fun run(args: ApplicationArguments) {
        SchemaUtils.create(*commerceTables)
        log.info { "commerce_schema_initialized tableCount=${commerceTables.size}" }
    }
}
