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
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(Libs.springBootStarter("data-elasticsearch"))
    testImplementation(Libs.springBootStarter("data-elasticsearch-test"))
    implementation(Libs.elasticsearch_rest_client)

    // Elasticsearch Local Server 관련 의존성
    implementation(Libs.bluetape4k_testcontainers)
    implementation(Libs.testcontainers)
    implementation(Libs.testcontainers_elasticsearch)

    implementation(Libs.springBootStarter("webflux"))
    testImplementation(Libs.springBootStarter("webflux-test"))

    testImplementation(Libs.bluetape4k_spring_tests)
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(group = "org.mockito", module = "mockito-core")
    }

    implementation(Libs.bluetape4k_jackson3)
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
