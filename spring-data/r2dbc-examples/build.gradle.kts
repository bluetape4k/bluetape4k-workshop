plugins {
    alias(libs.plugins.kotlin.spring)
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(libs.spring.boot4.dependencies))

    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.r2dbc)
    implementation(libs.bluetape4k.spring.boot4.r2dbc)
    testImplementation(libs.bluetape4k.junit5)

    // Coroutines
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    // Reactor
    implementation(libs.reactor.core)
    implementation(libs.reactor.kotlin.extensions)
    testImplementation(libs.reactor.test)

    // R2DBC
    implementation(libs.spring.boot.starter.data.r2dbc.lib)
    testImplementation(libs.spring.boot.starter.data.r2dbc.test)
    implementation(libs.h2.v2)
    implementation(libs.r2dbc.h2)
    implementation(libs.r2dbc.pool)

    testImplementation(libs.bluetape4k.spring.boot4.core)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}
