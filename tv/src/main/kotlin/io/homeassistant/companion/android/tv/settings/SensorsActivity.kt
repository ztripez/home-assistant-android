package io.homeassistant.companion.android.tv.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.sensors.SensorManager
import io.homeassistant.companion.android.database.sensor.SensorDao
import io.homeassistant.companion.android.tv.sensors.SensorReceiver
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class SensorsActivity : ComponentActivity() {

    @Inject
    lateinit var sensorDao: SensorDao

    @Inject
    lateinit var serverManager: ServerManager

    companion object {
        fun newInstance(context: Context): Intent {
            return Intent(context, SensorsActivity::class.java)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                SensorsScreen(
                    sensorDao = sensorDao,
                    serverManager = serverManager,
                    onBackClick = { finish() }
                )
            }
        }
    }
}

@Composable
fun SensorsScreen(
    sensorDao: SensorDao,
    serverManager: ServerManager,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var serverId by remember { mutableStateOf<Int?>(null) }

    // Get sensor managers and their sensors
    val sensorManagers = remember {
        SensorReceiver.MANAGERS.sortedBy { context.getString(it.name) }.filter { it.hasSensor(context) }
    }

    // Cache of available sensors per manager
    val availableSensors = remember { mutableStateMapOf<Int, List<SensorManager.BasicSensor>>() }

    // Track sensor states
    val sensorStates = remember { mutableStateMapOf<String, Boolean>() }
    val sensorValues = remember { mutableStateMapOf<String, String>() }

    // Load initial sensor states and available sensors
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val server = serverManager.getServer()
            serverId = server?.id
            val currentServerId = server?.id ?: return@withContext

            sensorManagers.forEachIndexed { index, manager ->
                val sensors = manager.getAvailableSensors(context)
                availableSensors[index] = sensors
                sensors.forEach { basicSensor ->
                    val sensor = sensorDao.get(basicSensor.id, currentServerId)
                    val enabled = sensor?.enabled ?: basicSensor.enabledByDefault
                    sensorStates[basicSensor.id] = enabled
                    sensor?.state?.let { sensorValues[basicSensor.id] = it }
                }
            }
        }
    }

    // Wait for server to be loaded
    val currentServerId = serverId ?: return

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = Color(0xFF1A1A2E)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Sensors",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White
                )

                OutlinedButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Back")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Enable or disable sensors that are sent to Home Assistant",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(32.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sensorManagers.forEachIndexed { index, manager ->
                    val sensors = availableSensors[index] ?: emptyList()
                    if (sensors.isNotEmpty()) {
                        item {
                            Text(
                                text = context.getString(manager.name),
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFF03DAC5),
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }

                        items(sensors) { basicSensor ->
                            SensorItem(
                                manager = manager,
                                basicSensor = basicSensor,
                                isEnabled = sensorStates[basicSensor.id] ?: basicSensor.enabledByDefault,
                                currentValue = sensorValues[basicSensor.id],
                                onToggle = { enabled ->
                                    sensorStates[basicSensor.id] = enabled
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            sensorDao.setSensorsEnabled(
                                                sensorIds = listOf(basicSensor.id),
                                                serverId = currentServerId,
                                                enabled = enabled
                                            )
                                        }
                                    }
                                }
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SensorItem(
    manager: SensorManager,
    basicSensor: SensorManager.BasicSensor,
    isEnabled: Boolean,
    currentValue: String?,
    onToggle: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            hasPermission = true
            onToggle(true)
        }
    }

    val backgroundPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            hasPermission = true
            onToggle(true)
        }
    }

    LaunchedEffect(Unit) {
        hasPermission = manager.checkPermission(context, basicSensor.id)
    }

    Card(
        onClick = {
            if (!isEnabled) {
                val permissions = manager.requiredPermissions(context, basicSensor.id)
                if (permissions.isEmpty() || hasPermission) {
                    onToggle(true)
                } else {
                    // Handle permission request
                    val permList = permissions.toList()
                    val needsBackgroundLocation = permList.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    val needsBackgroundSensors = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        permList.contains(Manifest.permission.BODY_SENSORS_BACKGROUND)

                    if (needsBackgroundLocation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        // Request fine location first, then background
                        val foregroundPerms = permList.filter {
                            it != Manifest.permission.ACCESS_BACKGROUND_LOCATION
                        }.toTypedArray()
                        if (foregroundPerms.isNotEmpty()) {
                            permissionLauncher.launch(foregroundPerms)
                        } else {
                            backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }
                    } else if (needsBackgroundSensors) {
                        val foregroundPerms = permList.filter {
                            it != Manifest.permission.BODY_SENSORS_BACKGROUND
                        }.toTypedArray()
                        if (foregroundPerms.isNotEmpty()) {
                            permissionLauncher.launch(foregroundPerms)
                        } else {
                            backgroundPermissionLauncher.launch(Manifest.permission.BODY_SENSORS_BACKGROUND)
                        }
                    } else {
                        permissionLauncher.launch(permissions)
                    }
                }
            } else {
                onToggle(false)
            }
        },
        colors = CardDefaults.colors(
            containerColor = Color(0xFF16213E),
            focusedContainerColor = Color(0xFF2D2D44)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Sensors,
                    contentDescription = null,
                    tint = if (isEnabled) Color(0xFF03DAC5) else Color.Gray,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = context.getString(basicSensor.name),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White
                    )
                    if (isEnabled && currentValue != null) {
                        Text(
                            text = if (basicSensor.unitOfMeasurement.isNullOrBlank()) {
                                currentValue
                            } else {
                                "$currentValue ${basicSensor.unitOfMeasurement}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }
            }

            Switch(
                checked = isEnabled,
                onCheckedChange = null, // Handled by card click
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF03DAC5),
                    checkedTrackColor = Color(0xFF03DAC5).copy(alpha = 0.5f),
                    uncheckedThumbColor = Color.Gray,
                    uncheckedTrackColor = Color.Gray.copy(alpha = 0.5f)
                )
            )
        }
    }
}
