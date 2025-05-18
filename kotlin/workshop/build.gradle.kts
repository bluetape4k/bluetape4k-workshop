configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(Libs.bluetape4k_core)
    implementation(Libs.bluetape4k_coroutines)

    testImplementation(Libs.bluetape4k_junit5)
}
