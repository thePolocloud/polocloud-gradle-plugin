plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

group = "de.polocloud"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    gradlePluginPortal()
}

gradlePlugin {
    plugins {
        create("polocloudDependencyPlugin") {
            id = "de.polocloud.dependency"
            implementationClass = "de.polocloud.gradle.plugin.PolocloudPlugin"
            displayName = "Polocloud Gradle Plugin"
            description = "Development tool for every polocloud component"
        }
    }
}

dependencies {
    implementation(gradleApi())
    implementation(libs.protobuf.plugin)
    implementation(libs.kotlin.plugin)
}

kotlin {
    jvmToolchain(25)
}