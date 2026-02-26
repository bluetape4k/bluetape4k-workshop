plugins {
    kotlin("plugin.spring")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Coroutines
    implementation(Libs.bluetape4k_coroutines)
    implementation(Libs.kotlinx_coroutines_core)
    implementation(Libs.kotlinx_coroutines_slf4j)
    implementation(Libs.kotlinx_coroutines_reactor)
    testImplementation(Libs.kotlinx_coroutines_test)

    // Id Generators
    implementation(Libs.bluetape4k_idgenerators)
    implementation(Libs.java_uuid_generator)

    // Coroutines Flow를 Reactor처럼 테스트 할 수 있도록 해줍니다.
    // 참고: https://github.com/cashapp/turbine/
    testImplementation(Libs.turbine)

    // TestContainers
    testImplementation(Libs.bluetape4k_testcontainers)
    testImplementation(Libs.testcontainers)
    testImplementation(Libs.testcontainers_junit_jupiter)

    // Spring Boot
    implementation(Libs.spring("context"))
    implementation(Libs.bluetape4k_spring_core)

    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}
