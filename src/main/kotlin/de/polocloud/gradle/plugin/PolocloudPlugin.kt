package de.polocloud.gradle.plugin

import de.polocloud.gradle.plugin.publishing.configurePublishingIfEnabled
import de.polocloud.gradle.plugin.task.GenerateDependencyIndexTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependencyBundle
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.attributes

class PolocloudPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "polocloud",
            PolocloudExtension::class.java
        )

        project.afterEvaluate {
            project.tasks.withType(Jar::class.java).configureEach {

                manifest {
                    attributes(
                        "Main-Class" to extension.mainClass,
                        "groupId" to project.group.toString(),
                        "artifactId" to project.name,
                        "version" to project.version.toString()
                    )
                }
            }
        }

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

            from(task.get().outputFile) {
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
            when (val value = notation.get()) {
                is ExternalModuleDependencyBundle -> {
                    value.forEach { dep ->
                        val version = dep.versionConstraint.requiredVersion
                            .takeIf { it.isNotBlank() }
                            ?: dep.versionConstraint.displayName

                        val gav = "${dep.module.group}:${dep.module.name}:$version"
                        extension.projects.add(gav)
                    }
                    dependencies.add("implementation", notation)
                }

                is MinimalExternalModuleDependency -> {
                    val version = value.versionConstraint.requiredVersion
                        .takeIf { it.isNotBlank() }
                        ?: value.versionConstraint.displayName

                    val gav = "${value.module.group}:${value.module.name}:$version"
                    extension.projects.add(gav)
                    dependencies.add("implementation", notation)
                }

                else -> {
                    throw IllegalArgumentException(
                        "Unsupported dependency notation type: ${value::class.java.name}. " +
                        "Expected a MinimalExternalModuleDependency or ExternalModuleDependencyBundle Provider. " +
                        "Use 'libs.someLibrary' or 'libs.bundles.someBundle', not an accessor group."
                    )
                }
            }
        }

        else -> {
            val notationStr = notation.toString()
            if (notationStr.count { it == ':' } < 2) {
                throw IllegalArgumentException(
                    "Invalid dependency notation: '$notationStr' (type: ${notation::class.java.name}). " +
                    "Expected format 'group:artifact:version' or a version catalog Provider " +
                    "(e.g. libs.someLibrary or libs.bundles.someBundle)."
                )
            }
            extension.projects.add(notationStr)
            dependencies.add("implementation", notation)
        }
    }
}