plugins {
    alias(libs.plugins.kotlin.jvm)
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // bluetape4k-text — AhoCorasick pattern search
    implementation(libs.bluetape4k.text.search)
    // bluetape4k-text — Lingua language detection
    implementation(libs.bluetape4k.text.lingua)
    // bluetape4k-text — Korean tokenizer / blockword processing
    implementation(libs.bluetape4k.text.korean)

    implementation(libs.bluetape4k.logging)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.logback.lib)
}
