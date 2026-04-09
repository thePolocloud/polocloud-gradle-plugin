package de.polocloud.gradle.plugin.dependencies

data class Dependency(val groupId: String, val artifactId: String, val version: String, val url: String, val checksum: String) {

    fun toNotation(): String {
        return "$groupId;$artifactId;$version;$url;$checksum"
    }
}
