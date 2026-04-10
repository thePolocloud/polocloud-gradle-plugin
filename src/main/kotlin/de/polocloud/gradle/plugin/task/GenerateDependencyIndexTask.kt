package de.polocloud.gradle.plugin.task

import de.polocloud.gradle.plugin.resolver.DependencyResolver
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import org.gradle.api.logging.Logging
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject

abstract class GenerateDependencyIndexTask @Inject constructor() : DefaultTask() {

    private val logger = Logging.getLogger(GenerateDependencyIndexTask::class.java)

    @get:Input
    abstract val projects: ListProperty<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun run() {
        logger.lifecycle("Starting GenerateDependencyIndexTask")

        val repositories = project.repositories
            .filterIsInstance<MavenArtifactRepository>()
            .map { it.url.toString() }
            .ifEmpty { listOf("https://repo.maven.apache.org/maven2") }

        logger.lifecycle("Using repositories: $repositories")

        val resolver = DependencyResolver(project)
        val projectList = projects.get()

        logger.lifecycle("Projects to resolve (${projectList.size}): $projectList")

        val threadCount = maxOf(1, minOf(projectList.size, Runtime.getRuntime().availableProcessors()))
        logger.lifecycle("Using $threadCount threads")

        val results = ConcurrentLinkedQueue<String>()

        projectList.forEach { proj ->
            try {
                logger.info("Resolving project: $proj")

                val resolved = resolver.resolve(proj, repositories)

                logger.info("Resolved ${resolved.size} dependencies for $proj")

                resolved.forEach {
                    results.add(it.toNotation())
                }

            } catch (e: Exception) {
                logger.error("Failed to resolve project: $proj", e)
            }
        }

        logger.error("Executor did not finish within timeout!")


        logger.lifecycle("Collected ${results.size} dependencies total")

        val file = outputFile.get().asFile
        logger.lifecycle("Writing output to: ${file.absolutePath}")

        try {
            file.parentFile.mkdirs()

            if (results.isNotEmpty()) {
                file.writeText(results.joinToString("\n"), StandardCharsets.UTF_8)
                logger.lifecycle("Successfully wrote dependency index")
            } else {
                logger.warn("No dependencies resolved, output file will not be written")
            }
        } catch (e: Exception) {
            logger.error("Failed to write output file", e)
            throw e
        }

        logger.lifecycle("GenerateDependencyIndexTask finished")
    }
}