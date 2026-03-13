plugins {
    kotlin("plugin.spring")
    id(Plugins.spring_boot)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.chaos.ChaosApplicationKt")
}


configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(Libs.bluetape4k_core)
    testImplementation(Libs.bluetape4k_junit5)

    // FIXME: chaos monkey 3.2.2 는 아직 Spring Boot 4 를 지원하지 않습니다.
    // Chaos Monkey (https://github.com/codecentric/chaos-monkey-spring-boot)
    implementation(Libs.chaos_monkey_spring_boot)

    // Spring Boot
    implementation(Libs.springBoot("autoconfigure"))
    annotationProcessor(Libs.springBoot("autoconfigure-processor"))
    annotationProcessor(Libs.springBoot("configuration-processor"))
    runtimeOnly(Libs.springBoot("devtools"))

    implementation(Libs.springBootStarter("web"))
    implementation(Libs.springBootStarter("jdbc"))
    implementation(Libs.springBootStarter("actuator"))
    implementation(Libs.springBootStarter("aspectj"))

    implementation(Libs.springBootStarter("restclient"))
    implementation(Libs.springBootStarter("webclient"))

    testImplementation(Libs.springBootStarter("webflux"))
    testImplementation(Libs.springBootStarter("webflux-test"))
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    implementation(Libs.h2)
    implementation(Libs.datafaker)

    testImplementation(Libs.bluetape4k_coroutines)
    testImplementation(Libs.kotlinx_coroutines_reactor)
    testImplementation(Libs.kotlinx_coroutines_test)
}
