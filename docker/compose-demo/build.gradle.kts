configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {

    implementation(libs.bluetape4k.core)

    testImplementation(libs.redisson.lib)

    testImplementation(libs.testcontainers.lib)
    testImplementation(libs.testcontainers.junit.jupiter)

    // Apple Silicon M1 에서 linux/amd64 platform 용 Docker 이미지를 실행하기 위해서 필요한 라이브러리
    testImplementation(libs.jna.lib)
    testImplementation(libs.jna.platform)
}
