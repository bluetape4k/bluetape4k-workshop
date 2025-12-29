package io.bluetape4k.workshop.problem.config

import io.bluetape4k.logging.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Configuration
import org.zalando.problem.jackson.ProblemModule
import tools.jackson.databind.ObjectMapper

/**
 * 예외정보를 Client 로 전달하는 [ProblemModule] 을 [ObjectMapper]에 등록합니다.
 *
 * @constructor Create empty Problem config
 */
@Configuration
@ConditionalOnClass(ProblemModule::class, ObjectMapper::class)
class ProblemConfig {

    companion object: KLogging()

    // FIXME: Problem 은 Jackson 2 를 사용합니다. 향후 Jackson 3를 사용하는 버전으로 변경 필요
//    @Bean
//    @ConditionalOnMissingBean
//    fun objectMapper(jsonMapper: JsonMapper, jacksonJsonMapper: JsonMapper): ObjectMapper {
//        log.info { "Create ObjectMapper for Problem Library" }
//
//        // 예외의 Stacktrace 정보까지 Client에 전송하기 위한 설정입니다.
//        return jsonMapper {
//            addModule(ProblemModule().withStackTraces())
//        }
//    }
}
