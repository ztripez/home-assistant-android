package io.homeassistant.companion.android.tv

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.homeassistant.companion.android.common.data.integration.Entity
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AreaRegistryResponse
import io.homeassistant.companion.android.util.RegistriesDataHandler
import io.homeassistant.companion.android.tv.CameraPiPActivity
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class CameraAreaViewModel @Inject constructor(
    private val serverManager: ServerManager,
    application: Application
) : AndroidViewModel(application) {

    var areas = mutableStateOf<List<AreaRegistryResponse>>(emptyList())
        private set
    var camerasByArea = mutableStateMapOf<String?, List<Entity>>()
        private set
    var isLoading = mutableStateOf(true)
        private set

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            isLoading.value = true
            try {
                val ws = serverManager.webSocketRepository()
                val integrationRepo = serverManager.integrationRepository()
                val areaRegistry = ws.getAreaRegistry().orEmpty().sortedBy { it.name }
                val deviceRegistry = ws.getDeviceRegistry()
                val entityRegistry = ws.getEntityRegistry()
                val entities = integrationRepo.getEntities().orEmpty()
                    .filter { it.entityId.startsWith("camera.") }
                areas.value = areaRegistry
                camerasByArea.clear()
                for (entity in entities) {
                    val area = RegistriesDataHandler.getAreaForEntity(
                        entity.entityId,
                        areaRegistry,
                        deviceRegistry,
                        entityRegistry
                    )
                    val key = area?.areaId
                    val list = camerasByArea[key]?.toMutableList() ?: mutableListOf()
                    list.add(entity)
                    camerasByArea[key] = list
                }
            } finally {
                isLoading.value = false
            }
        }
    }

    fun openCamera(context: Context, camera: Entity) {
        val serverId = serverManager.getServer()?.id ?: return
        val intent = CameraPiPActivity.newInstance(
            context,
            "entityId:${camera.entityId}",
            serverId
        )
        context.startActivity(intent)
    }
}
