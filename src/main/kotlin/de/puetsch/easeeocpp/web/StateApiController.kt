package de.puetsch.easeeocpp.web

import de.puetsch.easeeocpp.control.ControlState
import de.puetsch.easeeocpp.control.ControlStateStore
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class StateApiController(private val stateStore: ControlStateStore) {

    @GetMapping("/api/state")
    fun state(): ControlState = stateStore.current()
}
