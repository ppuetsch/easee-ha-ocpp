package de.puetsch.easeeocpp.web

import de.puetsch.easeeocpp.control.ControlStateStore
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

const val STATE_VIEW_ATTRIBUTE = "view"
private const val PAGE_TEMPLATE = "index"
private const val STATE_FRAGMENT_TEMPLATE = "fragments/state :: inhalt"

@Controller
class StateController(
    private val stateStore: ControlStateStore,
    private val stateViewFactory: StateViewFactory,
) {

    @GetMapping("/")
    fun page(model: Model): String {
        addCurrentView(model)
        return PAGE_TEMPLATE
    }

    @GetMapping("/fragments/state")
    fun stateFragment(model: Model): String {
        addCurrentView(model)
        return STATE_FRAGMENT_TEMPLATE
    }

    private fun addCurrentView(model: Model) {
        model.addAttribute(STATE_VIEW_ATTRIBUTE, stateViewFactory.create(stateStore.current()))
    }
}
