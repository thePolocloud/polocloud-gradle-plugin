package de.polocloud.gradle.plugin.task

import de.polocloud.gradle.plugin.resolver.DependencyResolver
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import java.nio.charset.StandardCharsets
import javax.inject.Inject

abstract class GenerateDependencyIndexTask @Inject constructor() : DefaultTask() {

    @get:Input
    abstract val projects: ListProperty<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun run() {
        val repositories = project.repositories
            .filterIsInstance<MavenArtifactRepository>()
            .map { it.url.toString() }
            .ifEmpty { listOf("https://repo.maven.apache.org/maven2") }

        val resolver = DependencyResolver(project)

        val dependencies = projects.get().flatMap {
            resolver.resolve(it, repositories)
        }

        val file = outputFile.get().asFile
        file.parentFile.mkdirs()

        if (dependencies.isNotEmpty()) {
            file.writeText(
                dependencies.joinToString("\n") { it.toNotation() },
                StandardCharsets.UTF_8
            )
        }
    }
}