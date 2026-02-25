plugins {
    kotlin("plugin.spring")
    id(Plugins.spring_boot)
    id(Plugins.graalvm_native)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.bucket4j.CaffeineApplicationKt")
}


configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(Libs.bluetape4k_jackson3)
    testImplementation(Libs.bluetape4k_junit5)

    // Bucket4j
    api(Libs.bucket4j_core)
    api(Libs.bucket4j_caffeine)
    api(Libs.bucket4j_spring_boot_starter)

    // Caffeine - 로컬 캐시는 AsyncCacheResolver를 구현한 것이 아니므로 Webflux 에서는 사용하지 못한다.
    api(Libs.caffeine)
    api(Libs.caffeine_jcache)

    // Spring Boot
    implementation(Libs.springBoot("autoconfigure"))
    annotationProcessor(Libs.springBoot("autoconfigure-processor"))
    annotationProcessor(Libs.springBoot("configuration-processor"))
    runtimeOnly(Libs.springBoot("devtools"))

    implementation(Libs.springBootStarter("actuator"))
    implementation(Libs.springBootStarter("aspectj"))
    implementation(Libs.springBootStarter("cache"))
    testImplementation(Libs.springBootStarter("cache-test"))
    implementation(Libs.springBootStarter("validation"))
    implementation(Libs.springBootStarter("web"))
    testImplementation(Libs.springBootStarter("webmvc-test"))

    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // WebTestClient 사용
    testImplementation(Libs.springBootStarter("webflux"))
    testImplementation(Libs.springBootStarter("webflux-test"))
}
