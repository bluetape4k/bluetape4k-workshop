package io.bluetape4k.workshop.messaging.kafka.multibroker.failover

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Kafka 다중 broker 장애 조치 reference를 위한 headless Spring shell입니다.
 *
 * broker 연결과 Testcontainers 수명 주기는 실행 가능한 테스트가 소유하며,
 * 기본 애플리케이션은 외부 bootstrap 설정을 읽거나 네트워크 연결을 열지 않습니다.
 */
@SpringBootApplication(proxyBeanMethods = false)
class KafkaMultiBrokerFailoverApplication

/**
 * headless reference 애플리케이션을 시작합니다.
 */
fun main(args: Array<String>) {
    runApplication<KafkaMultiBrokerFailoverApplication>(*args)
}
