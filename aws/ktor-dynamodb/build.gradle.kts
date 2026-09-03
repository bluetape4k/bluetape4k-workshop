import org.gradle.api.tasks.JavaExec

plugins {
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("io.bluetape4k.workshop.aws.ktordynamodb.KtorDynamoDbApplicationKt")
}

tasks.named<JavaExec>("run") {
    val awsWorkshopProperties = System.getProperties()
        .stringPropertyNames()
        .filter { it.startsWith("bluetape4k.aws.") }
        .associateWith { System.getProperty(it) }

    systemProperties(awsWorkshopProperties)
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
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.aws.kotlin.dynamodb)
    implementation(libs.aws.kotlin.dynamodbstreams)

    implementation(libs.bluetape4k.aws.ktor)
    implementation(libs.bluetape4k.aws.kotlin)
    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.bluetape4k.ktor.core)
    implementation(libs.bluetape4k.logging)

    implementation(libs.kotlinx.coroutines.core.lib)

    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.kotlinx.coroutines.test.lib)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers.localstack)
}
