package de.polocloud.gradle.plugin.resolver

import java.io.File
import java.net.HttpURLConnection
import java.net.URI

class MavenResolver {

    fun resolveUrl(
        repositories: List<String>,
        groupPath: String,
        artifactId: String,
        version: String,
        file: File
    ): String {
        for (repo in repositories) {

            if (version.endsWith("SNAPSHOT")) {
                return resolveSnapshot(repo, groupPath, artifactId, version, file)
            }

            val url = "$repo/$groupPath/$artifactId/$version/${file.name}"

            if (exists(url)) return url
        }

        error("Could not resolve $artifactId:$version")
    }

    private fun resolveSnapshot(
        repo: String,
        groupPath: String,
        artifactId: String,
        version: String,
        file: File
    ): String {
        val metadataUrl = "$repo/$groupPath/$artifactId/$version/maven-metadata.xml"

        val metadata = runCatching {
            URI(metadataUrl).toURL().readText()
        }.getOrElse {
            return "$repo/$groupPath/$artifactId/$version/${file.name}"
        }

        val timestamp = Regex("<timestamp>(.*?)</timestamp>").find(metadata)?.groupValues?.get(1)
        val build = Regex("<buildNumber>(.*?)</buildNumber>").find(metadata)?.groupValues?.get(1)

        return if (timestamp != null && build != null) {
            val base = version.removeSuffix("-SNAPSHOT")
            "$repo/$groupPath/$artifactId/$version/$artifactId-$base-$timestamp-$build.jar"
        } else {
            "$repo/$groupPath/$artifactId/$version/${file.name}"
        }
    }

    private fun exists(url: String): Boolean {
        return runCatching {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 3000
            conn.connect()
            conn.responseCode == 200
        }.getOrDefault(false)
    }
}