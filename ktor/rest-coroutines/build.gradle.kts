plugins {
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("io.bluetape4k.workshop.ktor.MainKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(libs.ktor.bom))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.jackson3.module.kotlin)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.kotlinx.coroutines.test.lib)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.content.negotiation)
}
