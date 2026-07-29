plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.leader.zookeeper.LeaderZookeeperAppKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

tasks.test {
    useJUnitPlatform {
        // 기본 테스트 실행에서는 @Tag("smoke") 테스트를 제외한다.
        excludeTags("smoke")
    }
}

dependencies {
    // bluetape4k-leader-zookeeper 의존성: Apache Curator 5.9.0은 전이 의존성으로 제공된다.
    implementation(libs.bluetape4k.leader.zookeeper)

    implementation(libs.bluetape4k.logging)

    // Spring Boot 구성
    implementation(libs.spring.boot.autoconfigure.lib)
    implementation(libs.spring.boot.starter.actuator)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)

    // 테스트 의존성
    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.coroutines)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.kotlinx.coroutines.test.lib)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
    testImplementation(libs.mockk)
    // T7의 ListAppender<ILoggingEvent> 검증에 필요한 logback-classic은 spring-boot-starter-test가 전이 제공한다.
}
