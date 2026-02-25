package io.bluetape4k.workshop.bucket4j.hazelcast.config

import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableCaching
class HazelcastConfig {

    @Bean(destroyMethod = "shutdown")
    fun hazelcastInstance(): HazelcastInstance {
        // classpath에 있는 hazelcast.yaml 또는 hazelcast.xml 설정을 자동으로 로드합니다.
        // 설정 파일이 없다면 기본 설정으로 생성됩니다.
        val config = Config.load()
        config.instanceName = "bucket4j-instance"
        config.clusterName = "bucket4j-cluster"
        return Hazelcast.getOrCreateHazelcastInstance(config)
    }
}
