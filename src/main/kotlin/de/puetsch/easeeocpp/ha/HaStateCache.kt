package de.puetsch.easeeocpp.ha

import de.puetsch.easeeocpp.domain.model.EntityId
import de.puetsch.easeeocpp.ports.HaEntityState
import java.util.concurrent.ConcurrentHashMap

internal class HaStateCache {
    private val statesByEntity = ConcurrentHashMap<EntityId, HaEntityState>()

    fun replaceAll(states: List<HaEntityState>) {
        val incoming = states.associateBy { it.entityId }
        statesByEntity.keys.retainAll(incoming.keys)
        statesByEntity.putAll(incoming)
    }

    fun put(state: HaEntityState) {
        statesByEntity[state.entityId] = state
    }

    fun remove(entityId: EntityId) {
        statesByEntity.remove(entityId)
    }

    fun get(entityId: EntityId): HaEntityState? = statesByEntity[entityId]
}
