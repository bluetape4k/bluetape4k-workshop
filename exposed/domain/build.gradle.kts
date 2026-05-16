plugins {
    alias(libs.plugins.kotlin.serialization)
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(libs.jetbrains.exposed.bom))

    testImplementation(project(":shared"))

    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    testImplementation(libs.exposed.jdbc)
    implementation(libs.exposed.jackson3)
    testImplementation(libs.exposed.jdbc)
    testImplementation(libs.exposed.jdbc.tests)

    implementation(libs.jetbrains.exposed.core)
    implementation(libs.jetbrains.exposed.crypt)
    implementation(libs.jetbrains.exposed.dao)
    implementation(libs.jetbrains.exposed.java.time)
    implementation(libs.jetbrains.exposed.jdbc)
    implementation(libs.jetbrains.exposed.json)
    implementation(libs.jetbrains.exposed.kotlin.datetime)
    implementation(libs.jetbrains.exposed.migration.jdbc)
    implementation(libs.jetbrains.exposed.money)
    implementation(libs.jetbrains.exposed.spring.boot4.starter)

    implementation(libs.bluetape4k.jdbc)
    testImplementation(libs.bluetape4k.junit5)

    compileOnly(libs.h2.v2)
    compileOnly(libs.mariadb.java.client)
    compileOnly(libs.mysql.connector.j)
    compileOnly(libs.postgresql.driver)
    compileOnly(libs.pgjdbc.ng)

    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.lib)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.cockroachdb)

    // Identifier 자동 생성
    implementation(libs.bluetape4k.idgenerators)
    implementation(libs.java.uuid.generator)

    // Coroutines
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    testImplementation(libs.logcaptor)

    // Kotlin Serialization JSON
    implementation(platform(libs.kotlinx.serialization.bom))
    implementation(libs.kotlinx.serialization.json)

    // Jackson 3 for Kotlin
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.jackson3.module.kotlin)
    implementation(libs.jackson3.module.blackbird)

    // Java Money
    implementation(libs.bluetape4k.money)
    implementation(libs.javax.money.api)
    implementation(libs.javamoney.moneta)

}
