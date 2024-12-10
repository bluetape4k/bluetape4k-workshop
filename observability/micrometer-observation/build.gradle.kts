plugins {
    kotlin("plugin.spring")
    id(Plugins.spring_boot)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.observation.ObservationAppKt")
}

@Suppress("UnstableApiUsage")
configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {

    implementation(platform(Libs.micrometer_bom))
    implementation(platform(Libs.micrometer_tracing_bom))

    // Observability
    implementation(Libs.micrometer_observation)
    testImplementation(Libs.micrometer_observation_test)

    // Tracing
    implementation(Libs.micrometer_tracing)
    testImplementation(Libs.micrometer_tracing_test)

    implementation(Libs.micrometer_tracing_bridge_otel)  // tracing 정보를 opentelemetry format으로 bridge
    implementation(Libs.opentelemetry_exporter_zipkin)   // zipkin server로 export

    implementation(Libs.micrometer_context_propagation)  // thread local <-> reactor 등 상이한 환경에서 context 전파를 위해 사용

    // Spring Boot
    implementation(Libs.springBoot("autoconfigure"))
    annotationProcessor(Libs.springBoot("autoconfigure-processor"))
    annotationProcessor(Libs.springBoot("configuration-processor"))
    runtimeOnly(Libs.springBoot("devtools"))

    implementation(Libs.springBootStarter("web"))
    implementation(Libs.springBootStarter("aop"))
    implementation(Libs.springBootStarter("actuator"))
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    implementation(Libs.bluetape4k_spring_core)
    implementation(Libs.bluetape4k_jackson)
    testImplementation(Libs.bluetape4k_junit5)

}
