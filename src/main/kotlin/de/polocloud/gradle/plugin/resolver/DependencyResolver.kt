package de.polocloud.gradle.plugin.resolver

import de.polocloud.gradle.plugin.dependencies.Dependency
import org.gradle.api.Project

class DependencyResolver(
    private val project: Project
) {

    private val mavenResolver = MavenResolver()
    private val checksumService = ChecksumService()

    fun resolve(notation: String, repositories: List<String>): List<Dependency> {
        val dependency = project.dependencies.create(notation)

        val config = project.configurations.detachedConfiguration(dependency).apply {
            isTransitive = true
        }

        return config.resolvedConfiguration.resolvedArtifacts.mapNotNull { artifact ->
            val file = artifact.file
            val id = artifact.moduleVersion.id

            val groupPath = id.group.replace('.', '/')

            val url = runCatching {
                mavenResolver.resolveUrl(
                    repositories,
                    groupPath,
                    id.name,
                    id.version,
                    file
                )
            }.getOrElse {
                project.logger.warn("Optional dependency: ${id.group}:${id.name}:${id.version}")
                "unknown"
            }

            Dependency(
                groupId = id.group,
                artifactId = id.name,
                version = id.version,
                url = url,
                checksum = checksumService.resolve(url, file)
            )
        }
    }
}