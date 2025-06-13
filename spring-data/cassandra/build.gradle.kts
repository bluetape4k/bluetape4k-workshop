plugins {
    kotlin("plugin.spring")
    kotlin("plugin.noarg")
    kotlin("plugin.allopen")

}
allOpen {
    annotation("com.datastax.oss.driver.api.mapper.annotations.Entity")
}
noArg {
    annotation("com.datastax.oss.driver.api.mapper.annotations.Entity")
    invokeInitializers = true
}

// NOTE: implementation 나 runtimeOnly로 지정된 Dependency를 testimplementation 으로도 지정하도록 합니다.

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(Libs.bluetape4k_cassandra)
    implementation(Libs.bluetape4k_spring_cassandra)
    implementation(Libs.bluetape4k_jackson)

    testImplementation(Libs.bluetape4k_idgenerators)
    testImplementation(Libs.bluetape4k_testcontainers)
    testImplementation(Libs.testcontainers_cassandra)

    // NOTE: Cassandra 4 oss 버전을 사용합니다.
    implementation(Libs.cassandra_java_driver_core)
    implementation(Libs.cassandra_java_driver_query_builder)
    compileOnly(Libs.cassandra_java_driver_mapper_runtime)
    compileOnly(Libs.cassandra_java_driver_metrics_micrometer)

    // cassandra 의 @Mapper, @Dao 를 활용할 때 사용합니다.
    // 참고: https://docs.datastax.com/en/developer/java-driver/4.13/manual/mapper/
//    kapt(Libs.cassandra_java_driver_mapper_processor)
//    kaptTest(Libs.cassandra_java_driver_mapper_processor)

    implementation(Libs.springBootStarter("data-cassandra"))
    implementation(Libs.springBootStarter("aop"))
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(group = "org.mockito", module = "mockito-core")
    }

    // Coroutines
    implementation(Libs.bluetape4k_coroutines)
    implementation(Libs.kotlinx_coroutines_core)
    implementation(Libs.kotlinx_coroutines_reactive)
    implementation(Libs.kotlinx_coroutines_reactor)
    testImplementation(Libs.kotlinx_coroutines_test)

    compileOnly(Libs.reactor_core)
    compileOnly(Libs.reactor_kotlin_extensions)
    compileOnly(Libs.reactor_test)
}
