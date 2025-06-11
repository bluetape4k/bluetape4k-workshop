plugins {
    kotlin("plugin.spring")
    id(Plugins.spring_boot)
    id(Plugins.graalvm_native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.micrometer.TracingApplicationKt")
}


configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(Libs.micrometer_bom))
    implementation(platform(Libs.micrometer_tracing_bom))
    
    implementation(Libs.bluetape4k_micrometer)
    implementation(Libs.bluetape4k_spring_core)
    implementation(Libs.bluetape4k_jackson)
    implementation(Libs.bluetape4k_testcontainers)
    testImplementation(Libs.bluetape4k_junit5)

    // Observability
    implementation(Libs.micrometer_observation)
    testImplementation(Libs.micrometer_observation_test)

    // Tracing
    implementation(Libs.micrometer_tracing)
    testImplementation(Libs.micrometer_tracing_test)
    // testImplementation(Libs.micrometer_tracing_integeration_test)

    // Tracing Reporting 방식은
    // 1. Micrometer Tracing -> Otel Brigdge -> Otel Exporter -> Zipkin Server 로 하는 방식과
    implementation(Libs.micrometer_tracing_bridge_otel)  // tracing 정보를 opentelemetry format으로 bridge
    implementation(Libs.opentelemetry_exporter_zipkin)   // zipkin server로 export

    // 2. Micrometer Tracing -> Brave Bridge -> Zipkin Reporter -> Zipkin Server 로 하는 방식이 있다.
    // 참고: https://www.appsdeveloperblog.com/micrometer-and-zipkin-in-spring-boot/
    // implementation(Libs.micrometer_tracing_bridge_brave)
    // implementation("io.zipkin.reporter2:zipkin-reporter-brave:3.3.0")  // https://mvnrepository.com/artifact/io.zipkin.reporter2/zipkin-reporter-brave

    implementation(Libs.micrometer_context_propagation)  // thread local <-> reactor 등 상이한 환경에서 context 전파를 위해 사용

    // Spring Boot
    implementation(Libs.springBoot("autoconfigure"))
    annotationProcessor(Libs.springBoot("autoconfigure-processor"))
    annotationProcessor(Libs.springBoot("configuration-processor"))
    runtimeOnly(Libs.springBoot("devtools"))

    implementation(Libs.springBootStarter("aop"))
    implementation(Libs.springBootStarter("actuator"))
    implementation(Libs.springBootStarter("webflux"))
    implementation(Libs.bluetape4k_spring_webflux)

    testImplementation(Libs.bluetape4k_spring_tests)
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Coroutines
    implementation(Libs.bluetape4k_coroutines)
    implementation(Libs.kotlinx_coroutines_core)
    implementation(Libs.kotlinx_coroutines_reactor)
    testImplementation(Libs.kotlinx_coroutines_test)

    // Reactor
    implementation(Libs.reactor_netty)
    implementation(Libs.reactor_kotlin_extensions)
    testImplementation(Libs.reactor_test)

    implementation(Libs.datafaker)
}
