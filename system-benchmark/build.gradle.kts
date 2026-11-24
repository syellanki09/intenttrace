plugins {
    id("intenttrace.kotlin-conventions")
    application
}

description = "Kafka and Redis backed synthetic benchmark of the runtime detector's overhead and detection"

dependencies {
    implementation(project(":detector"))
    implementation(libs.kafka.clients)
    implementation(libs.jedis)
    runtimeOnly(libs.slf4j.nop)
}

application {
    applicationName = "intenttrace-system-benchmark"
    mainClass.set("io.intenttrace.systembenchmark.SystemBenchmarkMainKt")
    applicationDefaultJvmArgs = listOf("-Xms1g", "-Xmx1g", "-XX:+UseG1GC", "-XX:+AlwaysPreTouch")
}
