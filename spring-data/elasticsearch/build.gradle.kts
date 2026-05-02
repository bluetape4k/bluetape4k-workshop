plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.noarg)
    alias(libs.plugins.kotlin.allopen)
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
    implementation(libs.spring.boot.starter.data.elasticsearch.lib)
    testImplementation(libs.spring.boot.starter.data.elasticsearch.test)
    implementation(libs.elasticsearch.rest.client)

    // Elasticsearch Local Server 관련 의존성
    implementation(libs.bluetape4k.testcontainers)
    implementation(libs.testcontainers.lib)
    implementation(libs.testcontainers.elasticsearch)

    implementation(libs.spring.boot.starter.webflux.lib)
    testImplementation(libs.spring.boot.starter.webflux.test)

    testImplementation(libs.bluetape4k.spring.boot4.core)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(group = "org.mockito", module = "mockito-core")
    }

    implementation(libs.bluetape4k.jackson3)
    implementation(libs.bluetape4k.jackson2)
    testImplementation(libs.bluetape4k.junit5)

    // Coroutines
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    implementation(libs.reactor.core)
    implementation(libs.reactor.kotlin.extensions)
    testImplementation(libs.reactor.test)
}
