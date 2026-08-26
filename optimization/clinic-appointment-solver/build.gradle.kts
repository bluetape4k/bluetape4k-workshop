plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.optimization.clinicappointment.ClinicAppointmentSolverApplicationKt")
}

dependencies {
    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.logging)
    implementation(libs.timefold.solver.core)

    implementation(libs.spring.boot.starter.webmvc.lib)
    implementation(libs.jackson3.module.kotlin)

    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
}
