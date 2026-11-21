package io.bluetape4k.workshop.commerce.usagebilling.query.config

import io.bluetape4k.logging.KLogging
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.ContainerProperties
import java.io.Serializable

@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableConfigurationProperties(QueryKafkaProperties::class)
/**
 * QueryKafkaMessagingConfiguration은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class QueryKafkaMessagingConfiguration {

    companion object: KLogging()

    /**
     * queryConsumerFactory은 사용량 계량·청구의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로
     * 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param properties 업무 처리에 필요한 properties 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun queryConsumerFactory(properties: QueryKafkaProperties): ConsumerFactory<String, String> =
        DefaultKafkaConsumerFactory(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to properties.bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to properties.consumerGroup,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ),
        )

    /**
     * queryKafkaListenerContainerFactory은 사용량 계량·청구 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에
     * 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param queryConsumerFactory 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     */
    @Bean
    fun queryKafkaListenerContainerFactory(
        queryConsumerFactory: ConsumerFactory<String, String>,
    ): ConcurrentKafkaListenerContainerFactory<String, String> =
        ConcurrentKafkaListenerContainerFactory<String, String>()
            .also { factory ->
                factory.setConsumerFactory(queryConsumerFactory)
                factory.containerProperties.ackMode = ContainerProperties.AckMode.RECORD
            }
}
/**
 * QueryKafkaProperties은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property bootstrapServers 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property consumerGroup 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


@ConfigurationProperties("usage-billing.query.kafka")
data class QueryKafkaProperties(
    /**
     * `bootstrapServers`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val bootstrapServers: String = "localhost:9092",
    /**
     * `consumerGroup`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val consumerGroup: String = "usage-billing-query-service",
): Serializable {
    private companion object {
        /**
         * `serialVersionUID`은 사용량 과금·정산에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
