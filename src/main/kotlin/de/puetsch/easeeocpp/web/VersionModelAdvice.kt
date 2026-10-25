package de.puetsch.easeeocpp.web

import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

const val VERSION_ATTRIBUTE = "version"

@ControllerAdvice(assignableTypes = [StateController::class])
class VersionModelAdvice(private val versionProvider: VersionProvider) {

    @ModelAttribute(VERSION_ATTRIBUTE)
    fun version(): String = versionProvider.version()
}
