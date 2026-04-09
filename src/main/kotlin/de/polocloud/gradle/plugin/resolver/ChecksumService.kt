package de.polocloud.gradle.plugin.resolver

import java.io.File
import java.net.URI
import java.security.MessageDigest

class ChecksumService {

    fun resolve(url: String, file: File): String {
        return runCatching { fetch("$url.sha256") }
            .getOrElse {
                runCatching { fetch("$url.sha1") }
                    .getOrElse { compute(file) }
            }
    }

    private fun fetch(url: String): String {
        return URI(url).toURL().readText().trim().split(" ")[0]
    }

    private fun compute(file: File): String =
        file.inputStream().use {
            MessageDigest.getInstance("SHA-256")
                .digest(it.readBytes())
                .joinToString("") { b -> "%02x".format(b) }
        }
}