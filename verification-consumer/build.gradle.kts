plugins {
    kotlin("multiplatform") version "2.2.21-1.0.0"
    kotlin("plugin.serialization") version "2.2.21-1.0.0"
    id("com.android.library") version "8.10.1"
    id("com.google.devtools.ksp") version "2.2.21-2.0.4"
}
val routingVersion = providers.gradleProperty("routingVersion").orElse("0.1.0-rc.1").get()
kotlin {
    jvmToolchain(21)
    androidTarget { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    jvm()
    iosArm64(); iosX64()
    iosSimulatorArm64 { binaries.framework { baseName = "RoutingConsumer"; isStatic = true } }
    ohosArm64()
    sourceSets {
        commonMain.dependencies { implementation("com.github.gycrosskit.routing:routing-core:$routingVersion") }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
dependencies {
    listOf("Android", "Jvm", "IosArm64", "IosSimulatorArm64", "IosX64", "OhosArm64").forEach { target ->
        add("ksp$target", "com.github.gycrosskit.routing:routing-compiler:$routingVersion")
    }
}
android {
    namespace = "io.github.gycrosskit.routing.consumer"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
