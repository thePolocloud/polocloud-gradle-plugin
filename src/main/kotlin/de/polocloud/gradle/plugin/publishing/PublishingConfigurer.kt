package de.polocloud.gradle.plugin.publishing

import de.polocloud.gradle.plugin.PolocloudExtension
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension

fun configurePublishingIfEnabled(project: Project, ext: PolocloudExtension) {
    if (ext.publishEnabled.orNull != true) return

    project.pluginManager.apply("maven-publish")
    project.pluginManager.apply("signing")

    val publishing = project.extensions.getByType(PublishingExtension::class.java)

    publishing.publications.create("maven", MavenPublication::class.java) {
        from(project.components.getByName("java"))

        pom {
            name.set(project.name)
            description.set("Polocloud module")
        }
    }

    val signing = project.extensions.getByType(SigningExtension::class.java)

    val key = System.getenv("GPG_PRIVATE_KEY")
    val pass = System.getenv("GPG_PASSPHRASE")

    if (key != null && pass != null) {
        signing.useInMemoryPgpKeys(key, pass)
        signing.sign(publishing.publications)
    }
}