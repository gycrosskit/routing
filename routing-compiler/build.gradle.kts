plugins {
    kotlin("jvm")
    `maven-publish`
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:2.2.21-2.0.4")
    testImplementation(kotlin("test-junit"))
    testImplementation(project(":routing-core"))
    testImplementation("com.google.devtools.ksp:symbol-processing-aa-embeddable:2.2.21-2.0.4")
    testImplementation("com.google.devtools.ksp:symbol-processing-common-deps:2.2.21-2.0.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0")
}

tasks.test {
    systemProperty("routingCompilerClasspath", configurations.named("kotlinCompilerClasspath").get().asPath)
    systemProperty("routingConsumerClasspath", sourceSets.test.get().runtimeClasspath.asPath)
}

java { withSourcesJar() }
publishing {
    publications { create<MavenPublication>("mavenJava") { from(components["java"]) } }
}
