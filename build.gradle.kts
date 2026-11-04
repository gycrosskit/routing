plugins {
    kotlin("multiplatform") version "2.2.21-1.0.0" apply false
    kotlin("jvm") version "2.2.21-1.0.0" apply false
    kotlin("plugin.serialization") version "2.2.21-1.0.0" apply false
    id("com.android.library") version "8.10.1" apply false
}
allprojects {
    group = "com.github.gycrosskit.routing"
    version = providers.environmentVariable("VERSION").orElse("1.0.1").get()
}
subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<org.gradle.api.publish.PublishingExtension> {
            repositories { maven { name = "staging"; url = uri(rootProject.layout.buildDirectory.dir("maven")) } }
            publications.withType<org.gradle.api.publish.maven.MavenPublication>().configureEach {
                pom.licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
            }
        }
    }
}
