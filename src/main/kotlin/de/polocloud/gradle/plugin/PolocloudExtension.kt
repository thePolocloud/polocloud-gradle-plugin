package de.polocloud.gradle.plugin

abstract class PolocloudExtension {

    var mainClass: String? = null
    val projects = mutableListOf<String>()
    var isProtoProject: Boolean = false

    fun include(vararg paths: String) {
        projects.addAll(paths)
    }
}