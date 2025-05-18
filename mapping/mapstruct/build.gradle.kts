plugins {
    kotlin("kapt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(Libs.mapstruct)
    kapt(Libs.mapstruct_processor)
    kaptTest(Libs.mapstruct_processor)

    implementation(Libs.bluetape4k_io)
}
