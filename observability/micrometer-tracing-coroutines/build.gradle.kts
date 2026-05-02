plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.graalvm.native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.micrometer.TracingApplicationKt")
}


configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(libs.micrometer.bom))
    implementation(platform(libs.micrometer.tracing.bom))

    testImplementation(project(":shared"))

    implementation(libs.bluetape4k.micrometer)
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.bluetape4k.junit5)

    // Observability
    implementation(libs.micrometer.observation.lib)
    testImplementation(libs.micrometer.observation.test)

    // Tracing
    implementation(libs.micrometer.tracing.lib)
    testImplementation(libs.micrometer.tracing.test)
    // testImplementation(libs.micrometer.tracing.integeration.test)

    // Tracing Reporting 방식은
    // 1. Micrometer Tracing -> Otel Brigdge -> Otel Exporter -> Zipkin Server 로 하는 방식과
    implementation(libs.micrometer.tracing.bridge.otel)  // tracing 정보를 opentelemetry format으로 bridge
    implementation(libs.opentelemetry.exporter.zipkin)   // zipkin server로 export

    // 2. Micrometer Tracing -> Brave Bridge -> Zipkin Reporter -> Zipkin Server 로 하는 방식이 있다.
    // 참고: https://www.appsdeveloperblog.com/micrometer-and-zipkin-in-spring-boot/
    // implementation(libs.micrometer.tracing.bridge.brave)
    // implementation("io.zipkin.reporter2:zipkin-reporter-brave:3.3.0")  // https://mvnrepository.com/artifact/io.zipkin.reporter2/zipkin-reporter-brave

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
    implementation(libs.spring.boot.starter.webflux.lib)
    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Coroutines
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    // Reactor
    implementation(libs.reactor.netty)
    implementation(libs.reactor.kotlin.extensions)
    testImplementation(libs.reactor.test)

    implementation(libs.datafaker)

    // Zipkin 에서 사용하는 Netty modules
    implementation(libs.netty.all)
    implementation(libs.netty.handler.ssl.ocsp)
}
