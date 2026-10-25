package de.puetsch.easeeocpp.web

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

data class VersionResponse(val version: String)

@RestController
class VersionController(private val versionProvider: VersionProvider) {

    @GetMapping("/api/version")
    fun version(): VersionResponse = VersionResponse(versionProvider.version())
}
