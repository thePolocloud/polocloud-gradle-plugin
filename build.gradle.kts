plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
    signing

    alias(libs.plugins.nexus.publish)
}

group = "de.polocloud"
version = "3.0.0-SNAPSHOT"

java {
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

gradlePlugin {
    plugins {
        create("polocloudGradlePlugin") {
            id = "de.polocloud.gradle.plugin"
            implementationClass = "de.polocloud.gradle.plugin.PolocloudPlugin"
            displayName = "Polocloud Gradle Plugin"
            description = "Development tool for every polocloud component"
        }
    }
}

publishing {
    publications {
        withType<MavenPublication>().configureEach {
            pom {
                name.set("PoloCloud Gradle Plugin")
                description.set("Gradle plugin for PoloCloud")
                url.set("https://github.com/thePolocloud/polocloud")

                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }

                developers {
                    developer {
                        id.set("httpmarco")
                        name.set("Mirco Lindenau")
                        email.set("mirco.lindenau@gmx.de")
                    }
                }

                scm {
                    url.set("https://github.com/thePolocloud/polocloud-gradle-plugin")
                    connection.set("scm:git:https://github.com/thePolocloud/polocloud-gradle-plugin.git")
                    developerConnection.set("scm:git:https://github.com/thePolocloud/polocloud-gradle-plugin.git")
                }
            }
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


nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))

            username.set(System.getenv("OSSRH_USERNAME"))
            password.set(System.getenv("OSSRH_PASSWORD"))
        }
    }
}

signing {
    val signingKey = System.getenv("GPG_PRIVATE_KEY")
    val signingPassphrase = System.getenv("GPG_PASSPHRASE")

    if (signingKey != null && signingPassphrase != null) {
        useInMemoryPgpKeys(signingKey, signingPassphrase)
        sign(publishing.publications)
    }
}