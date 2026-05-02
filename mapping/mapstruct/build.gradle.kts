plugins {
    alias(libs.plugins.kotlin.kapt)
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(libs.mapstruct.lib)
    kapt(libs.mapstruct.processor)
    kaptTest(libs.mapstruct.processor)

    implementation(libs.bluetape4k.io)
}
