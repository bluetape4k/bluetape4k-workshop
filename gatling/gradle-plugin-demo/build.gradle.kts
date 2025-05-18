plugins {
    id(Plugins.gatling) version Plugins.Versions.gatling
}

gatling {
    // logLevel = "WARN" // WARNING: options below only work when logback config file isn't provided
    // logHttp = io.gatling.gradle.LogHttp.NONE  // set to 'ALL' for all HTTP traffic in TRACE, 'FAILURES' for failed HTTP traffic in DEBUG

    // enterprise.closureOf<Any> {
    // Enterprise Cloud (https://cloud.gatling.io/) configuration reference: https://gatling.io/docs/gatling/reference/current/extensions/gradle_plugin/#working-with-gatling-enterprise-cloud
    // Enterprise Self-Hosted configuration reference: https://gatling.io/docs/gatling/reference/current/extensions/gradle_plugin/#working-with-gatling-enterprise-self-hosted
    // }
}

dependencies {
    // Gatling
    implementation(Libs.gatling_app)
    implementation(Libs.gatling_core_java)
    implementation(Libs.gatling_http_java)
    implementation(Libs.gatling_recorder)
    implementation(Libs.gatling_charts_highcharts)
}
