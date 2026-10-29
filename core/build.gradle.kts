plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    jacoco
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies {
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
}
tasks.test { finalizedBy(tasks.jacocoTestReport) }
jacoco { toolVersion = libs.versions.jacoco.get() }
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports { xml.required = true; html.required = true }
}
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule { limit { counter = "LINE"; minimum = "0.80".toBigDecimal() } }
    }
}
tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }
