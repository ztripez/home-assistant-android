package io.homeassistant.companion.android.tv.camera

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.common.data.integration.Entity
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.data.servers.UrlState
import io.homeassistant.companion.android.util.UrlUtil
import java.net.URL
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class CameraActivity : ComponentActivity() {

    @Inject
    lateinit var serverManager: ServerManager

    companion object {
        private const val EXTRA_ENTITY_ID = "entity_id"

        fun newInstance(context: Context, entityId: String): Intent {
            return Intent(context, CameraActivity::class.java).apply {
                putExtra(EXTRA_ENTITY_ID, entityId)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val entityId = intent.getStringExtra(EXTRA_ENTITY_ID) ?: run {
            finish()
            return
        }

        setContent {
            MaterialTheme {
                CameraScreen(
                    entityId = entityId,
                    serverManager = serverManager,
                    onBackClick = { finish() }
                )
            }
        }
    }
}

private const val CAMERA_REFRESH_INTERVAL_MS = 500L

@Composable
fun CameraScreen(
    entityId: String,
    serverManager: ServerManager,
    onBackClick: () -> Unit
) {
    var entity by remember { mutableStateOf<Entity?>(null) }
    var baseUrl by remember { mutableStateOf<URL?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val server = serverManager.getServer()
                if (server != null) {
                    val wsRepo = serverManager.webSocketRepository(server.id)

                    // Get base URL
                    val urlState = serverManager.connectionStateProvider(server.id).urlFlow().first()
                    baseUrl = if (urlState is UrlState.HasUrl) urlState.url else null

                    // Get entity
                    val statesResponse = wsRepo.getStates()
                    entity = statesResponse?.find { it.entityId == entityId }?.let { response ->
                        Entity(
                            response.entityId,
                            response.state,
                            response.attributes,
                            response.lastChanged,
                            response.lastUpdated
                        )
                    }
                }
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                isLoading = false
            }
        }
    }

    // Request focus when screen loads
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp) {
                    when (event.key) {
                        Key.Back, Key.Escape -> {
                            onBackClick()
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            },
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = Color.Black
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> {
                    Text(
                        text = "Loading camera...",
                        color = Color.White
                    )
                }
                errorMessage != null -> {
                    Text(
                        text = "Error: $errorMessage",
                        color = Color.Red
                    )
                }
                entity != null -> {
                    CameraStreamView(
                        entity = entity!!,
                        baseUrl = baseUrl
                    )
                }
                else -> {
                    Text(
                        text = "Camera not found",
                        color = Color.White
                    )
                }
            }

            // Overlay header with camera name and back button
            entity?.let { cam ->
                CameraOverlay(
                    cameraName = cam.attributes["friendly_name"]?.toString() ?: cam.entityId,
                    onBackClick = onBackClick,
                    modifier = Modifier.align(Alignment.TopStart)
                )
            }
        }
    }
}

@Composable
fun CameraStreamView(
    entity: Entity,
    baseUrl: URL?
) {
    val context = LocalPlatformContext.current

    // Fast refresh timestamp for streaming-like effect
    var refreshTimestamp by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(CAMERA_REFRESH_INTERVAL_MS)
            refreshTimestamp = System.currentTimeMillis()
        }
    }

    val entityPicture = entity.attributes["entity_picture"]?.toString()
    val baseImageUrl = if (entityPicture != null && baseUrl != null) {
        UrlUtil.handle(baseUrl, entityPicture)?.toString()
    } else {
        null
    }

    // Build URL with timestamp for cache busting
    val streamUrl = if (baseImageUrl != null) {
        val separator = if (baseImageUrl.contains("?")) "&" else "?"
        "$baseImageUrl${separator}_ts=$refreshTimestamp"
    } else {
        null
    }

    // Keep track of last successful painter for seamless transitions
    var lastSuccessfulPainter by remember { mutableStateOf<Painter?>(null) }
    var hasLoadedOnce by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (streamUrl != null) {
            // Show last successful image as background while loading
            if (lastSuccessfulPainter != null) {
                Image(
                    painter = lastSuccessfulPainter!!,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }

            // Load new image on top
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(streamUrl)
                    .build(),
                contentDescription = entity.attributes["friendly_name"]?.toString() ?: "Camera",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                onState = { state ->
                    if (state is AsyncImagePainter.State.Success) {
                        lastSuccessfulPainter = state.painter
                        hasLoadedOnce = true
                    }
                }
            )
        } else if (!hasLoadedOnce) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = "Camera",
                    tint = Color.Gray,
                    modifier = Modifier.size(64.dp)
                )
                Text(
                    text = "No camera image available",
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}

@Composable
fun CameraOverlay(
    cameraName: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0x80000000))
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackClick,
                colors = IconButtonDefaults.colors(
                    containerColor = Color(0x80000000),
                    contentColor = Color.White,
                    focusedContainerColor = Color(0xFF2D2D44),
                    focusedContentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                text = cameraName,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )
        }
    }
}
