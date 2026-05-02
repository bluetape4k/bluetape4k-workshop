plugins {
    id("io.quarkus")
    alias(libs.plugins.kotlin.allopen)
}

allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.persistence.Entity")
    annotation("io.quarkus.test.junit.QuarkusTest")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // NOTE: Quarkus 는 꼭 gradle platform 으로 참조해야 제대로 빌드가 된다.
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation(platform(libs.quarkus.universe.bom))
    implementation(platform(libs.resteasy.bom))

    implementation(platform(libs.bluetape4k.bom))

    // Quarkus 라이브러리 (https://quarkus.io/extensions/)
    // rest
    implementation("io.quarkus.quarkus-rest")
    implementation("io.quarkus.quarkus-rest-kotlin")
    implementation("io.quarkus.quarkus-rest-jackson")

    // rest client
    implementation("io.quarkus.quarkus-rest-client")
    implementation("io.quarkus.quarkus-rest-client-jackson")

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.rest.assured.kotlin)

    // Bluetape4k
    implementation(libs.bluetape4k.jackson2)
    implementation(libs.bluetape4k.quarkus.core)
    testImplementation(libs.bluetape4k.quarkus.tests)
    testImplementation(libs.bluetape4k.idgenerators)

    // coroutines
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactive)
    testImplementation(libs.kotlinx.coroutines.test.lib)
}
