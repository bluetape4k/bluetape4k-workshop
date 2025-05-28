plugins {
    kotlin("plugin.spring")
    id(Plugins.spring_boot)
    id(Plugins.graalvm_native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.bucket4j.CaffeineApplicationKt")
}

@Suppress("UnstableApiUsage")
configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(Libs.bluetape4k_spring_core)
    implementation(Libs.bluetape4k_jackson)
    testImplementation(Libs.bluetape4k_junit5)

    // Bucket4j
    api(Libs.bucket4j_core)
    api(Libs.bucket4j_caffeine)
    api(Libs.bucket4j_spring_boot_starter)

    api(Libs.javax_cache_api)

    // Caffeine - 로컬 캐시는 AsyncCacheResolver를 구현한 것이 아니므로 Webflux 에서는 사용하지 못한다.
    api(Libs.caffeine)
    api(Libs.caffeine_jcache)

    // Spring Boot
    implementation(Libs.springBoot("autoconfigure"))
    annotationProcessor(Libs.springBoot("autoconfigure-processor"))
    annotationProcessor(Libs.springBoot("configuration-processor"))
    runtimeOnly(Libs.springBoot("devtools"))

    implementation(Libs.springBootStarter("web"))
    implementation(Libs.springBootStarter("cache"))
    implementation(Libs.springBootStarter("validation"))
    implementation(Libs.springBootStarter("actuator"))

    testImplementation(Libs.bluetape4k_spring_tests)
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // WebTestClient 사용
    testImplementation(Libs.springBootStarter("webflux"))
}
