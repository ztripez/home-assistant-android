package io.homeassistant.companion.android.common.data.websocket.impl.entities

import kotlinx.serialization.Serializable

@Serializable
data class FloorRegistryResponse(
    val floorId: String,
    val name: String,
    val level: Int? = null,
    val icon: String? = null,
    val aliases: List<String> = emptyList(),
)
