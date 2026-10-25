package de.puetsch.easeeocpp.e2e

import de.puetsch.easeeocpp.control.ControlState
import de.puetsch.easeeocpp.control.ControlStateStore

class FakeControlStateStore(initialState: ControlState) : ControlStateStore {
    @Volatile
    var state: ControlState = initialState

    override fun current(): ControlState = state
}
