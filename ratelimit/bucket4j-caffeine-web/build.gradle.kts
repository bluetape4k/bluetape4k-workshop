plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.graalvm.native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.bucket4j.CaffeineApplicationKt")
}


configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(libs.bluetape4k.jackson3)
    testImplementation(libs.bluetape4k.junit5)

    // Bucket4j
    api(libs.bucket4j.core)
    api(libs.bucket4j.caffeine)
    api(libs.bucket4j.spring.boot.starter)

    // Caffeine - 로컬 캐시는 AsyncCacheResolver를 구현한 것이 아니므로 Webflux 에서는 사용하지 못한다.
    api(libs.caffeine.lib)
    api(libs.caffeine.jcache)

    // Spring Boot
    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)

    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.aspectj)
    implementation(libs.spring.boot.starter.cache.lib)
    testImplementation(libs.spring.boot.starter.cache.test)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.webmvc.test)

    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // WebTestClient 사용
    testImplementation(libs.spring.boot.starter.webflux.lib)
    testImplementation(libs.spring.boot.starter.webflux.test)
}
