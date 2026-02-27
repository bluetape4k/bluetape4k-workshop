plugins {
    kotlin("plugin.spring")
    kotlin("plugin.allopen")
    kotlin("plugin.noarg")
    kotlin("plugin.jpa")
    kotlin("kapt")
}

// JPA Entities 들을 Java와 같이 모두 override 가능하게 합니다 (Kotlin 은 기본이 final 입니다)
// 이렇게 해야 association의 proxy 가 만들어집니다.
// https://kotlinlang.org/docs/reference/compiler-plugins.html
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.Embeddable")
    annotation("jakarta.persistence.MappedSuperclass")
}

kapt {
    correctErrorTypes = true
    showProcessorStats = true

    arguments {
        arg("querydsl.entityAccessors", "true")  // Association의 property는 getter/setter를 사용하도록 합니다.
        arg("querydsl.kotlinCodegen", "true") // QueryDSL Kotlin Codegen 활성화
    }
    javacOptions {
        option("--add-modules", "java.base")
    }
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(Libs.spring_modulith_bom))

    // Spring Modulith
    implementation(Libs.spring_modulith_actuator)
    implementation(Libs.spring_modulith_observability)
    implementation(Libs.spring_modulith_starter_core)
    implementation(Libs.spring_modulith_starter_jpa)
    testImplementation(Libs.spring_modulith_starter_test)

    // Observability
    implementation(Libs.micrometer_tracing_bridge_otel)
    implementation(Libs.opentelemetry_exporter_zipkin)

    api(Libs.jakarta_annotation_api)
    api(Libs.jakarta_persistence_api)
    api(Libs.hibernate_core)

    // JPA / Hibernate
    implementation(Libs.bluetape4k_hibernate)

    // QueryDsl
    implementation(Libs.querydsl_jpa + ":jakarta")
    kapt(Libs.querydsl_apt + ":jakarta")
    kaptTest(Libs.querydsl_apt + ":jakarta")

    implementation(Libs.hikaricp)
    implementation(Libs.h2_v2)

    // MapStruct
    implementation(Libs.mapstruct)
    kapt(Libs.mapstruct_processor)
    kaptTest(Libs.mapstruct_processor)

    // Vaidators
    implementation(Libs.hibernate_validator)
    runtimeOnly(Libs.jakarta_validation_api)

    // Testcontainers
    implementation(Libs.bluetape4k_testcontainers)

    // Spring Boot
    testImplementation(Libs.springBoot("autoconfigure"))
    annotationProcessor(Libs.springBoot("autoconfigure-processor"))
    annotationProcessor(Libs.springBoot("configuration-processor"))
    runtimeOnly(Libs.springBoot("devtools"))

    implementation(Libs.springBootStarter("actuator"))
    implementation(Libs.springBootStarter("data-jpa"))
    implementation(Libs.springBootStarter("validation"))
    implementation(Libs.springBootStarter("web"))

    testImplementation(Libs.springBootStarter("webflux"))
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    testImplementation(Libs.bluetape4k_coroutines)
    testImplementation(Libs.kotlinx_coroutines_reactor)
    testImplementation(Libs.kotlinx_coroutines_test)
    testImplementation(Libs.reactor_kotlin_extensions)
    testImplementation(Libs.reactor_test)

    // OpenAPI Documentation
    implementation(Libs.springdoc_openapi_starter_webmvc_api)

    implementation(Libs.bluetape4k_idgenerators)
    testImplementation(Libs.bluetape4k_spring_tests)

    testImplementation(Libs.springmockk)
}
