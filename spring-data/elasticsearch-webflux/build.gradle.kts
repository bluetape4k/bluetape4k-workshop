plugins {
    id(Plugins.spring_boot)
    kotlin("plugin.spring")
    kotlin("plugin.noarg")
    kotlin("plugin.allopen")
}

allOpen {
    annotation("org.springframework.data.elasticsearch.annotations.Document")
}
noArg {
    annotation("org.springframework.data.elasticsearch.annotations.Document")
    invokeInitializers = true
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.elasticsearch.ElasticsearchApplicationKt")

    buildInfo {
        properties {
            additional.put("name", "ElasticsearchApplication")
            additional.put("description", "Elasticsearch + Webflux Application")
            version = "1.0.0"
            additional.put("java.version", JavaVersion.current())
        }
    }
}


configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Spring Boot
    implementation(Libs.springBoot("autoconfigure"))
    annotationProcessor(Libs.springBoot("autoconfigure-processor"))
    annotationProcessor(Libs.springBoot("configuration-processor"))
    runtimeOnly(Libs.springBoot("devtools"))

    implementation(Libs.springBootStarter("actuator"))
    implementation(Libs.springBootStarter("aspectj"))
    implementation(Libs.springBootStarter("webflux"))
    implementation(Libs.springBootStarter("validation"))

    implementation(Libs.springBootStarter("data-elasticsearch"))
    implementation(Libs.elasticsearch_rest_client)

    testImplementation(Libs.bluetape4k_spring_tests)
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(group = "org.mockito", module = "mockito-core")
    }

    // Elasticsearch Server 관련 의존성
    implementation(Libs.bluetape4k_testcontainers)
    implementation(Libs.testcontainers_elasticsearch)

    // Swagger
    implementation(Libs.springdoc_openapi_starter_webflux_ui)
    implementation(Libs.jackson_module_kotlin)
    implementation(Libs.jackson_module_blackbird)

    implementation(Libs.bluetape4k_jackson)
    testImplementation(Libs.bluetape4k_junit5)

    // Coroutines
    implementation(Libs.bluetape4k_coroutines)
    implementation(Libs.kotlinx_coroutines_core)
    implementation(Libs.kotlinx_coroutines_reactor)
    testImplementation(Libs.kotlinx_coroutines_test)

    implementation(Libs.reactor_core)
    implementation(Libs.reactor_kotlin_extensions)
    testImplementation(Libs.reactor_test)
}
