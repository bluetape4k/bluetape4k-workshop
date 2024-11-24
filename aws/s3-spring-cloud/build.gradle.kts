plugins {
    kotlin("plugin.spring")
    kotlin("plugin.noarg")
    id(Plugins.spring_boot)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.aws.s3.SpringCloudAwsS3SampleKt")
}

@Suppress("UnstableApiUsage")
configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {

    // AWS S3
    implementation(Libs.spring_cloud_aws_starter)
    implementation(Libs.spring_cloud_aws_s3)
    implementation(Libs.aws2_s3)
    implementation(Libs.aws2_s3_transfer_manager)
    implementation(Libs.aws2_aws_crt_client)
    implementation(Libs.bluetape4k_aws_s3)

    // AWS Testcontainers
    implementation(Libs.bluetape4k_testcontainers)
    implementation(Libs.testcontainers_localstack)

    // Jackson
    implementation(Libs.bluetape4k_jackson)
    implementation(Libs.jackson_databind)
    implementation(Libs.jackson_module_kotlin)
    implementation(Libs.jackson_module_blackbird)

    // Spring Boot
    implementation(Libs.springBoot("autoconfigure"))
    annotationProcessor(Libs.springBoot("autoconfigure-processor"))
    annotationProcessor(Libs.springBoot("configuration-processor"))
    runtimeOnly(Libs.springBoot("devtools"))

    implementation(Libs.springBootStarter("web"))
    implementation(Libs.springBootStarter("actuator"))
    testImplementation(Libs.springBootStarter("test")) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}
