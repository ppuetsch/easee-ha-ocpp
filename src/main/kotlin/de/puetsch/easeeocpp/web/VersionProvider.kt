package de.puetsch.easeeocpp.web

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Component

const val UNKNOWN_VERSION = "unbekannt"

/** Versionsnummer ist der Short-SHA des Commits, aus dem gebaut wurde (siehe build.gradle.kts). */
@Component
class VersionProvider(private val buildProperties: ObjectProvider<BuildProperties>) {
    fun version(): String = buildProperties.ifAvailable?.version ?: UNKNOWN_VERSION
}
