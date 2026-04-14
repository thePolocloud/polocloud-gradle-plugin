package de.polocloud.gradle.plugin

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class PolocloudExtension @Inject constructor(objects: ObjectFactory) {

    val mainClass: Property<String> = objects.property(String::class.java)

    val projectName: Property<String> = objects.property(String::class.java)

    val projects: ListProperty<String> = objects.listProperty(String::class.java)

    val publishEnabled: Property<Boolean> = objects.property(Boolean::class.java)
        .convention(false)

    fun include(vararg paths: String) {
        projects.addAll(paths.toList())
    }
}