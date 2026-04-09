package de.polocloud.gradle.plugin.dsl

import de.polocloud.gradle.plugin.PolocloudExtension
import org.gradle.api.Project

fun Project.polocloudRuntime(notation: String) {
    val ext = extensions.getByType(PolocloudExtension::class.java)
    ext.projects.add(notation)
    dependencies.add("implementation", notation)
}