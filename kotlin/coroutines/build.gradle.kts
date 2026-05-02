plugins {
    alias(libs.plugins.kotlin.spring)
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Coroutines
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.slf4j)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    // Id Generators
    implementation(libs.bluetape4k.idgenerators)
    implementation(libs.java.uuid.generator)

    // Coroutines Flow를 Reactor처럼 테스트 할 수 있도록 해줍니다.
    // 참고: https://github.com/cashapp/turbine/
    testImplementation(libs.turbine)

    // TestContainers
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.lib)
    testImplementation(libs.testcontainers.junit.jupiter)

    // Spring Boot
    implementation(libs.spring.context)
    implementation(libs.bluetape4k.spring.boot4.core)

    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}
