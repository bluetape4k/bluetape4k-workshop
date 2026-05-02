plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.graalvm.native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.observation.ObservationAppKt")
}


configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {

    implementation(platform(libs.micrometer.bom))
    implementation(platform(libs.micrometer.tracing.bom))

    // Micrometer Observation
    implementation(libs.bluetape4k.micrometer)
    implementation(libs.micrometer.observation.lib)
    testImplementation(libs.micrometer.observation.test)

    // Micrometer Tracing
    implementation(libs.micrometer.tracing.lib)
    testImplementation(libs.micrometer.tracing.test)
    implementation(libs.micrometer.tracing.bridge.otel)  // tracing 정보를 opentelemetry format으로 bridge

    implementation(libs.opentelemetry.exporter.zipkin)   // zipkin server로 export

    implementation(libs.micrometer.context.propagation)  // thread local <-> reactor 등 상이한 환경에서 context 전파를 위해 사용

    // Spring Boot
    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)

    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.aspectj)
    implementation(libs.spring.boot.starter.opentelemetry.lib)
    testImplementation(libs.spring.boot.starter.opentelemetry.test)
    implementation(libs.spring.boot.starter.webmvc.lib)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    implementation(libs.bluetape4k.jackson3)
    testImplementation(libs.bluetape4k.junit5)

}
