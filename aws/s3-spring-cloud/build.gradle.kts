plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.noarg)
    alias(libs.plugins.spring.boot)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.aws.s3.SpringCloudAwsS3SampleKt")
}


configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {

    // AWS S3
    implementation(libs.spring.cloud.aws.starter)
    implementation(libs.spring.cloud.aws.s3)
    implementation(libs.aws2.s3.lib)
    implementation(libs.aws2.s3.transfer.manager)
    implementation(libs.aws2.aws.crt.client)
    implementation(libs.bluetape4k.aws)

    // AWS Testcontainers
    implementation(libs.bluetape4k.testcontainers)
    implementation(libs.testcontainers.localstack)

    // Jackson
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.jackson3.databind)
    implementation(libs.jackson3.module.kotlin)
    implementation(libs.jackson3.module.blackbird)

    // Spring Boot
    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}
