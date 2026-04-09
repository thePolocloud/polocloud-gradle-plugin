package de.polocloud.gradle.plugin

import com.google.protobuf.gradle.id
import de.polocloud.gradle.plugin.dependencies.Dependency
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.attributes
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Gradle plugin that embeds a `dependencies.index` file into the produced JAR.
 *
 * <p>The index file contains runtime dependency metadata including group, artifact, version,
 * download URL, and checksum. This metadata is consumed by Polocloud at runtime to resolve
 * and verify dependencies without bundling them into the JAR itself.
 *
 * <p>Usage in a subproject's {@code build.gradle.kts}:
 * <pre>{@code
 * plugins {
 *     id("de.polocloud.dependency")
 * }
 *
 * polocloud {
 *     mainClass = "com.example.Main"
 * }
 *
 * // Register a runtime-resolved dependency
 * polocloudRuntime(libs.grpc.stub)
 *
 * // Configure the module as a gRPC/Protobuf project
 * asProtoProject()
 * }</pre>
 */
class PolocloudDependencyPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "polocloud",
            PolocloudExtension::class.java
        )

        project.afterEvaluate {
            project.tasks.withType(Jar::class.java).configureEach {
                manifest {
                    extension.mainClass?.let { main ->
                        attributes("Main-Class" to main)
                    }
                    attributes(
                        "groupId"    to project.group.toString(),
                        "artifactId" to project.name,
                        "version"    to project.version.toString()
                    )
                }

                val blobFile = project.layout.buildDirectory
                    .file("dependencies.index")
                    .get()
                    .asFile

                blobFile.parentFile.mkdirs()

                val repositories = project.repositories
                    .filterIsInstance<MavenArtifactRepository>()
                    .map { it.url.toString() }
                    .ifEmpty { listOf("https://repo.maven.apache.org/maven2") }

                doFirst {
                    val dependencies = extension.projects.flatMap { notation ->
                        resolveDependencies(project, notation, repositories)
                    }

                    if (dependencies.isNotEmpty()) {
                        blobFile.writeText(
                            dependencies.joinToString("\n") { it.toNotation() },
                            StandardCharsets.UTF_8
                        )
                    }
                }

                from(blobFile) {
                    into("/")
                }
            }
        }
    }

    /**
     * Resolves all transitive runtime artifacts for the given dependency notation
     * and maps them to {@link Dependency} instances containing download URLs and checksums.
     *
     * @param project      the Gradle project used for dependency resolution
     * @param notation     the dependency notation in {@code group:artifact:version} format
     * @param repositories ordered list of Maven repository base URLs to search
     * @return list of resolved dependencies; unresolvable artifacts are skipped with a warning
     */
    private fun resolveDependencies(
        project: Project,
        notation: String,
        repositories: List<String>
    ): List<Dependency> {
        val dependency = project.dependencies.create(notation)
        val config = project.configurations.detachedConfiguration(dependency).apply {
            isTransitive = true
        }

        return config.resolvedConfiguration.resolvedArtifacts.mapNotNull { artifact ->
            val file = artifact.file
            val id   = artifact.moduleVersion.id

            val group     = id.group
            val name      = id.name
            val version   = id.version
            val groupPath = group.replace('.', '/')

            val mavenUrl = runCatching {
                resolveMavenUrl(repositories, groupPath, name, version, file)
            }.getOrElse {
                project.logger.warn("[polocloud] Skipping unresolved dependency: $group:$name:$version")
                return@mapNotNull null
            }

            Dependency(
                groupId    = group,
                artifactId = name,
                version    = version,
                url        = mavenUrl,
                checksum   = runCatching {
                    fetchChecksum(mavenUrl)
                }.getOrElse {
                    computeLocalChecksum(file)
                }
            )
        }
    }

    /**
     * Resolves the remote download URL for the given artifact by probing each
     * configured repository with an HTTP HEAD request.
     *
     * <p>SNAPSHOT versions are delegated to {@link #resolveSnapshotUrl} which
     * parses {@code maven-metadata.xml} to determine the timestamped filename.
     *
     * @param repositories ordered list of Maven repository base URLs
     * @param groupPath    the group ID with dots replaced by slashes (e.g. {@code io/grpc})
     * @param artifactId   the artifact name
     * @param version      the artifact version
     * @param file         the locally resolved artifact file (used to derive the filename)
     * @return the first reachable download URL
     * @throws IllegalStateException if no repository responds with HTTP 200
     */
    private fun resolveMavenUrl(
        repositories: List<String>,
        groupPath: String,
        artifactId: String,
        version: String,
        file: File
    ): String {
        for (repoUrl in repositories) {
            if (version.endsWith("SNAPSHOT")) {
                return resolveSnapshotUrl(repoUrl, groupPath, artifactId, version, file)
            }

            val url = "$repoUrl/$groupPath/$artifactId/$version/${file.name}"

            val reachable = runCatching {
                val connection = URI(url).toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout    = 5_000
                connection.requestMethod  = "HEAD"
                connection.connect()
                val code = connection.responseCode
                connection.disconnect()
                code == 200
            }.getOrDefault(false)

            if (reachable) return url
        }

        error(
            "[polocloud] Could not resolve download URL for $artifactId:$version " +
                    "in any configured repository: $repositories"
        )
    }

    /**
     * Resolves the timestamped download URL for a SNAPSHOT artifact by fetching
     * and parsing the repository's {@code maven-metadata.xml}.
     *
     * <p>SNAPSHOT JARs use a filename like:
     * {@code artifactId-1.0.0-20241201.123456-1.jar}
     * rather than the generic {@code artifactId-1.0.0-SNAPSHOT.jar}.
     *
     * <p>Falls back to the generic SNAPSHOT filename if metadata cannot be retrieved.
     *
     * @param repoUrl    the base URL of the Maven repository
     * @param groupPath  the group ID path (dots replaced by slashes)
     * @param artifactId the artifact name
     * @param version    the full SNAPSHOT version string (e.g. {@code 1.0.0-SNAPSHOT})
     * @param file       the locally resolved artifact file used as fallback filename
     * @return the resolved (possibly timestamped) download URL
     */
    private fun resolveSnapshotUrl(
        repoUrl: String,
        groupPath: String,
        artifactId: String,
        version: String,
        file: File
    ): String {
        val metadataUrl = "$repoUrl/$groupPath/$artifactId/$version/maven-metadata.xml"

        val metadata = runCatching {
            URI(metadataUrl).toURL().readText()
        }.getOrElse {
            return "$repoUrl/$groupPath/$artifactId/$version/${file.name}"
        }

        val timestamp   = Regex("<timestamp>(.*?)</timestamp>").find(metadata)?.groupValues?.get(1)
        val buildNumber = Regex("<buildNumber>(.*?)</buildNumber>").find(metadata)?.groupValues?.get(1)

        return if (timestamp != null && buildNumber != null) {
            val baseVersion = version.removeSuffix("-SNAPSHOT")
            val fileName    = "$artifactId-$baseVersion-$timestamp-$buildNumber.jar"
            "$repoUrl/$groupPath/$artifactId/$version/$fileName"
        } else {
            "$repoUrl/$groupPath/$artifactId/$version/${file.name}"
        }
    }

    /**
     * Computes a SHA-256 checksum of a local file.
     *
     * <p>Used as a fallback when no remote checksum file is available.
     *
     * @param file the file to hash
     * @return lowercase hex-encoded SHA-256 digest
     */
    private fun computeLocalChecksum(file: File): String =
        file.inputStream().use { stream ->
            MessageDigest.getInstance("SHA-256")
                .digest(stream.readBytes())
                .joinToString("") { b -> "%02x".format(b) }
        }
}

/**
 * Fetches a checksum for the given artifact URL.
 *
 * <p>Prefers SHA-256 ({@code .sha256}) and automatically falls back to
 * SHA-1 ({@code .sha1}) if the preferred file is unavailable.
 *
 * @param jarUrl the base URL of the JAR artifact (without checksum extension)
 * @return the checksum as a hexadecimal string
 * @throws RuntimeException if neither checksum file can be fetched
 */
fun fetchChecksum(jarUrl: String): String {
    fun load(url: String): String = URI(url).toURL().readText().trim().split(" ")[0]
    return runCatching { load("$jarUrl.sha256") }.getOrElse { load("$jarUrl.sha1") }
}

/**
 * Registers a dependency both as a Polocloud runtime dependency (embedded in
 * {@code dependencies.index}) and as a standard Gradle {@code implementation} dependency.
 *
 * <p>Accepts either a plain notation string or a version catalog {@link Provider}:
 * <pre>{@code
 * polocloudRuntime("io.grpc:grpc-stub:1.60.0")
 * polocloudRuntime(libs.grpc.stub)
 * }</pre>
 *
 * @param notation a dependency notation string or a version catalog {@code Provider}
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
                    extension.projects.add("${dep.module.group}:${dep.module.name}:$version")
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

/**
 * Configures the current project as a gRPC/Protobuf module.
 *
 * <p>Applies the following plugins programmatically:
 * <ul>
 *   <li>{@code java-library}</li>
 *   <li>{@code org.jetbrains.kotlin.jvm}</li>
 *   <li>{@code com.google.protobuf}</li>
 *   <li>{@code de.polocloud}</li>
 * </ul>
 *
 * <p>Adds all standard gRPC and Protobuf dependencies from the version catalog
 * and configures the Protobuf compiler to generate both Java and Kotlin stubs.
 *
 * <p>The version catalog is resolved from the current project first, falling back
 * to {@code rootProject} for compatibility with composite builds.
 *
 * <p>Usage:
 * <pre>{@code
 * plugins {
 *     id("de.polocloud.dependency")
 * }
 *
 * asProtoProject()
 * }</pre>
 */
fun Project.asProtoProject() {
    pluginManager.apply("java-library")
    pluginManager.apply("org.jetbrains.kotlin.jvm")
    pluginManager.apply("com.google.protobuf")
    pluginManager.apply("de.polocloud")

    // Resolve the version catalog from the current project, falling back to
    // rootProject for compatibility with included/composite builds.
    val libs = runCatching {
        extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
    }.getOrElse {
        rootProject.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
    }

    fun lib(alias: String)     = libs.findLibrary(alias).get()
    fun version(alias: String) = libs.findVersion(alias).get().toString()

    dependencies.add("api",          lib("protobuf-kotlin"))
    dependencies.add("api",          lib("grpc-kotlin-stub"))
    dependencies.add("api",          lib("grpc-stub"))
    dependencies.add("api",          lib("grpc-protobuf"))
    dependencies.add("api",          lib("grpc-netty-shaded"))
    dependencies.add("api",          lib("kotlinx-coroutines-core"))
    dependencies.add("compileOnly",  lib("javax-annotation-api"))

    extensions.configure(JavaPluginExtension::class.java) {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        withSourcesJar()
    }

    extensions.configure(KotlinJvmProjectExtension::class.java) {
        jvmToolchain(21)
    }

    // Defer Protobuf configuration until after the plugin is fully initialized.
    pluginManager.withPlugin("com.google.protobuf") {
        extensions.configure(com.google.protobuf.gradle.ProtobufExtension::class.java) {
            protoc {
                artifact = "com.google.protobuf:protoc:${version("protobuf-java")}"
            }
            plugins {
                id("grpc") {
                    artifact = "io.grpc:protoc-gen-grpc-java:${version("grpc")}"
                }
                id("grpckt") {
                    artifact = "io.grpc:protoc-gen-grpc-kotlin:${version("grpcKotlinVersion")}:jdk8@jar"
                }
            }
            generateProtoTasks {
                all().forEach { task ->
                    task.builtins { create("kotlin") }
                    task.plugins {
                        id("grpc")
                        id("grpckt")
                    }
                }
            }
        }
    }
}