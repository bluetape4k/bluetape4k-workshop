plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.idgenerator.IdGeneratorApplicationKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // bluetape4k 의존성
    implementation(libs.bluetape4k.idgenerators)
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.bluetape4k.coroutines)
    testImplementation(libs.bluetape4k.junit5)

    // Spring Boot 의존성
    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)

    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.webflux.lib)
    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // 코루틴
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    // Reactor 의존성
    implementation(libs.reactor.kotlin.extensions)
    testImplementation(libs.reactor.test)

    // SpringDoc - OpenAPI 의존성
    implementation(libs.springdoc.openapi.starter.webflux.ui)
}
