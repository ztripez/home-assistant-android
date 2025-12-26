package io.homeassistant.companion.android.tv.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.compose.Image
import com.mikepenz.iconics.typeface.library.community.material.CommunityMaterial
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.common.data.integration.Entity
import io.homeassistant.companion.android.common.data.integration.domain
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AreaRegistryResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.EntityRegistryResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.FloorRegistryResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.GetConfigResponse
import io.homeassistant.companion.android.tv.onboarding.OnboardingActivity
import io.homeassistant.companion.android.tv.settings.SettingsActivity
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class HomeActivity : ComponentActivity() {

    @Inject
    lateinit var serverManager: ServerManager

    companion object {
        fun newInstance(context: Context): Intent {
            return Intent(context, HomeActivity::class.java)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if user is registered/onboarded
        val isRegistered = runBlocking { serverManager.isRegistered() }
        if (!isRegistered) {
            startActivity(OnboardingActivity.newInstance(this))
            finish()
            return
        }

        setContent {
            MaterialTheme {
                TvHomeScreen(
                    serverManager = serverManager,
                    onSettingsClick = {
                        startActivity(SettingsActivity.newInstance(this))
                    },
                    onCameraClick = { entityId ->
                        // TODO: Open camera view
                    }
                )
            }
        }
    }
}

@Composable
fun TvHomeScreen(
    serverManager: ServerManager,
    onSettingsClick: () -> Unit,
    onCameraClick: (String) -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var instanceName by remember { mutableStateOf("Home Assistant") }
    var areas by remember { mutableStateOf<List<AreaRegistryResponse>>(emptyList()) }
    var floors by remember { mutableStateOf<List<FloorRegistryResponse>>(emptyList()) }
    var entities by remember { mutableStateOf<Map<String, Entity>>(emptyMap()) }
    var entityRegistry by remember { mutableStateOf<List<EntityRegistryResponse>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val server = serverManager.getServer()
                if (server != null) {
                    val wsRepo = serverManager.webSocketRepository(server.id)

                    // Get config for instance name
                    val config = wsRepo.getConfig()
                    instanceName = config?.locationName ?: "Home Assistant"

                    // Get registries
                    areas = wsRepo.getAreaRegistry() ?: emptyList()
                    floors = wsRepo.getFloorRegistry() ?: emptyList()
                    entityRegistry = wsRepo.getEntityRegistry() ?: emptyList()

                    val statesResponse = wsRepo.getStates()
                    entities = statesResponse?.associate { response ->
                        response.entityId to Entity(
                            response.entityId,
                            response.state,
                            response.attributes,
                            response.lastChanged,
                            response.lastUpdated
                        )
                    } ?: emptyMap()
                }
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                isLoading = false
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = Color(0xFF1A1A2E)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = instanceName,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White
                )

                TvButton(
                    icon = Icons.Default.Settings,
                    label = "Settings",
                    onClick = onSettingsClick
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Loading...",
                        color = Color.White
                    )
                }
            } else if (errorMessage != null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Error: $errorMessage",
                        color = Color.Red
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Cameras Section
                    val cameras = entities.values.filter { it.domain == "camera" }
                    if (cameras.isNotEmpty()) {
                        item {
                            Text(
                                text = "Cameras",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(end = 16.dp)
                            ) {
                                items(cameras.toList()) { camera ->
                                    CameraCard(
                                        entity = camera,
                                        onClick = { onCameraClick(camera.entityId) }
                                    )
                                }
                            }
                        }
                    }

                    // Areas Section - grouped by floor
                    if (areas.isNotEmpty()) {
                        // Sort floors by level (ascending), null levels go at the end
                        val sortedFloors = floors.sortedBy { it.level ?: Int.MAX_VALUE }

                        // Group areas by floor
                        val areasByFloor = areas.groupBy { it.floorId }
                        val areasWithoutFloor = areasByFloor[null] ?: emptyList()

                        // Show areas for each floor
                        sortedFloors.forEach { floor ->
                            val floorAreas = areasByFloor[floor.floorId] ?: emptyList()
                            if (floorAreas.isNotEmpty()) {
                                item {
                                    Text(
                                        text = floor.name,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                        contentPadding = PaddingValues(end = 16.dp)
                                    ) {
                                        items(floorAreas) { area ->
                                            val areaEntities = entityRegistry
                                                .filter { it.areaId == area.areaId }
                                                .mapNotNull { reg -> entities[reg.entityId] }

                                            AreaCard(
                                                area = area,
                                                entityCount = areaEntities.size,
                                                onClick = { /* TODO: Open area view */ }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Show areas without floor assignment
                        if (areasWithoutFloor.isNotEmpty()) {
                            item {
                                Text(
                                    text = if (floors.isNotEmpty()) "Other Areas" else "Areas",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    contentPadding = PaddingValues(end = 16.dp)
                                ) {
                                    items(areasWithoutFloor) { area ->
                                        val areaEntities = entityRegistry
                                            .filter { it.areaId == area.areaId }
                                            .mapNotNull { reg -> entities[reg.entityId] }

                                        AreaCard(
                                            area = area,
                                            entityCount = areaEntities.size,
                                            onClick = { /* TODO: Open area view */ }
                                        )
                                    }
                                }
                            }
                        }
                    }

                }
            }
        }
    }
}

@Composable
fun TvButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = Color(0xFF16213E),
            contentColor = Color.White,
            focusedContainerColor = Color(0xFF2D2D44),
            focusedContentColor = Color.White
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp)
            )
            Text(text = label)
        }
    }
}

@Composable
fun CameraCard(
    entity: Entity,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(280.dp)
            .height(180.dp),
        colors = CardDefaults.colors(
            containerColor = Color(0xFF16213E),
            focusedContainerColor = Color(0xFF2D2D44)
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Camera placeholder/thumbnail
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0F0F23)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = "Camera",
                    tint = Color.Gray,
                    modifier = Modifier.size(48.dp)
                )
            }

            // Camera name overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .background(Color(0x99000000))
                    .padding(12.dp)
            ) {
                Text(
                    text = entity.attributes["friendly_name"]?.toString() ?: entity.entityId,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun AreaCard(
    area: AreaRegistryResponse,
    entityCount: Int,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(200.dp)
            .height(120.dp),
        colors = CardDefaults.colors(
            containerColor = Color(0xFF16213E),
            focusedContainerColor = Color(0xFF2D2D44)
        )
    ) {
        val context = LocalContext.current

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Use MDI icon if available, fallback to default Home icon
            val areaIcon = area.icon
            if (areaIcon != null && areaIcon.startsWith("mdi:")) {
                val mdiName = areaIcon.removePrefix("mdi:")
                val iconName = "cmd_${mdiName.replace('-', '_')}"
                val icon = try {
                    CommunityMaterial.getIcon(iconName)
                } catch (e: Exception) {
                    null
                }
                if (icon != null) {
                    Image(
                        asset = icon,
                        colorFilter = ColorFilter.tint(Color(0xFF03DAC5)),
                        modifier = Modifier.size(32.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = "Area",
                        tint = Color(0xFF03DAC5),
                        modifier = Modifier.size(32.dp)
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.Home,
                    contentDescription = "Area",
                    tint = Color(0xFF03DAC5),
                    modifier = Modifier.size(32.dp)
                )
            }

            Column {
                Text(
                    text = area.name,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "$entityCount entities",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
