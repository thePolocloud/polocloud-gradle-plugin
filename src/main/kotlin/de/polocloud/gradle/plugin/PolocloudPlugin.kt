package de.polocloud.gradle.plugin

import de.polocloud.gradle.plugin.publishing.configurePublishingIfEnabled
import de.polocloud.gradle.plugin.task.GenerateDependencyIndexTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar

class PolocloudDependencyPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "polocloud",
            PolocloudExtension::class.java
        )

        configurePublishingIfEnabled(project, extension)

        val task = project.tasks.register(
            "generateDependencyIndex",
            GenerateDependencyIndexTask::class.java
        ) {
            projects.set(extension.projects)
            outputFile.set(project.layout.buildDirectory.file("dependencies.index"))
        }

        project.tasks.withType(Jar::class.java).configureEach {
            dependsOn(task)

            from(task.flatMap { it.outputFile }) {
                into("/")
            }
        }
    }
}