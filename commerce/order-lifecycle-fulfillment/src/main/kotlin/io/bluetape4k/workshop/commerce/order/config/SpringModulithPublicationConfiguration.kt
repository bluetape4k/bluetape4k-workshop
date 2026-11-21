package io.bluetape4k.workshop.commerce.order.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.spring.modulith.exposed.ExposedEventPublicationRepository
import io.bluetape4k.spring.modulith.exposed.ExposedEventPublicationTable
import io.bluetape4k.spring.modulith.exposed.config.ExposedModulithProperties
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.events.core.EventSerializer
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
/**
 * SpringModulithPublicationConfiguration은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class SpringModulithPublicationConfiguration {

    companion object: KLogging()

    /**
     * springTransactionManager은 주문 라이프사이클과 fulfillment 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에
     * 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param dataSource 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    @Bean("springTransactionManager")
    fun springTransactionManager(dataSource: DataSource): PlatformTransactionManager =
        SpringTransactionManager(dataSource, DatabaseConfig {}, false)

    /**
     * eventPublicationRepository은 주문 라이프사이클과 fulfillment 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리
     * 전에 드러나도록
     * 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param eventPublicationTable 주문 라이프사이클과 fulfillment 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다.
     * 순서·revision·중복 여부를
     * 보존해야 합니다.
     * @param eventPublicationArchiveTable 주문 라이프사이클과 fulfillment 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다.
     * 순서·revision·중복 여부를 보존해야 합니다.
     * @param serializer 업무 처리에 필요한 serializer 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param properties 업무 처리에 필요한 properties 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun eventPublicationRepository(
        eventPublicationTable: ExposedEventPublicationTable,
        eventPublicationArchiveTable: ExposedEventPublicationTable,
        serializer: EventSerializer,
        properties: ExposedModulithProperties,
    ): ExposedEventPublicationRepository =
        ExposedEventPublicationRepository(
            table = eventPublicationTable,
            archiveTable = eventPublicationArchiveTable,
            serializer = serializer,
            completionMode = properties.completionMode
        ).also {
            log.info {
                "exposed_event_publication_repository_configured completionMode=${properties.completionMode}"
            }
        }
}
