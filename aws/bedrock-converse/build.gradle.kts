plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.workshop.aws.bedrock.BedrockConverseApplicationKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(libs.aws.kotlin.bedrock.runtime)
    implementation(libs.bluetape4k.aws.kotlin)
    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.bluetape4k.logging)
    implementation(libs.kotlinx.coroutines.core.lib)

    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.kotlinx.coroutines.test.lib)
    testImplementation(libs.mockk)
}
