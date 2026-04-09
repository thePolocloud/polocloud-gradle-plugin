package de.polocloud.gradle.plugin

import de.polocloud.gradle.plugin.publishing.configurePublishingIfEnabled
import de.polocloud.gradle.plugin.task.GenerateDependencyIndexTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.bundling.Jar

class PolocloudPlugin : Plugin<Project> {

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

/**
 * Adds a runtime dependency that will be embedded into the `dependencies.index`
 * and also registers it as an `implementation` dependency.
 *
 * @param notation dependency notation (`group:artifact:version`)
 */

fun Project.polocloudRuntime(notation: Any) {

    val extension = extensions.getByType(PolocloudExtension::class.java)

    when (notation) {

        is Provider<*> -> {
            notation.map { dep ->
                if (dep is MinimalExternalModuleDependency) {
                    val version = dep.versionConstraint.requiredVersion
                        .takeIf { it.isNotBlank() }
                        ?: dep.versionConstraint.displayName

                    val gav = "${dep.module.group}:${dep.module.name}:$version"

                    extension.projects.add(gav)
                }
            }.get()
            dependencies.add("implementation", notation)
        }

        else -> {
            extension.projects.add(notation.toString())
            dependencies.add("implementation", notation)
        }
    }
}