package io.homeassistant.companion.android.tv.pip

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.data.servers.UrlState
import io.homeassistant.companion.android.util.UrlUtil
import java.net.URL
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

@AndroidEntryPoint
class PictureInPictureActivity : ComponentActivity() {

    @Inject
    lateinit var serverManager: ServerManager

    @Inject
    lateinit var okHttpClient: OkHttpClient

    companion object {
        private const val EXTRA_STREAM_URL = "stream_url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_STREAM_TYPE = "stream_type"
        private const val EXTRA_SERVER_ID = "server_id"

        const val STREAM_TYPE_VIDEO = "video"
        const val STREAM_TYPE_IMAGE = "image"

        fun newInstance(
            context: Context,
            streamUrl: String,
            title: String? = null,
            streamType: String = STREAM_TYPE_VIDEO,
            serverId: Int? = null
        ): Intent {
            return Intent(context, PictureInPictureActivity::class.java).apply {
                putExtra(EXTRA_STREAM_URL, streamUrl)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_STREAM_TYPE, streamType)
                serverId?.let { putExtra(EXTRA_SERVER_ID, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    private var isInPipMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: run {
            finish()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE)
        val streamType = intent.getStringExtra(EXTRA_STREAM_TYPE) ?: STREAM_TYPE_VIDEO
        val serverId = intent.getIntExtra(EXTRA_SERVER_ID, -1).takeIf { it != -1 }

        setContent {
            MaterialTheme {
                PipContent(
                    streamUrl = streamUrl,
                    title = title,
                    streamType = streamType,
                    serverId = serverId,
                    isInPipMode = isInPipMode,
                    serverManager = serverManager,
                    okHttpClient = okHttpClient
                )
            }
        }

        // Enter PiP mode immediately
        enterPipMode()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipMode()
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode

        // If user exits PiP mode by expanding, close the activity
        if (!isInPictureInPictureMode) {
            finish()
        }
    }
}

private const val IMAGE_REFRESH_INTERVAL_MS = 1000L

@OptIn(UnstableApi::class)
@Composable
fun PipContent(
    streamUrl: String,
    title: String?,
    streamType: String,
    serverId: Int?,
    isInPipMode: Boolean,
    serverManager: ServerManager,
    okHttpClient: OkHttpClient
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = Color.Black
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when (streamType) {
                PictureInPictureActivity.STREAM_TYPE_VIDEO -> {
                    VideoStream(
                        streamUrl = streamUrl,
                        serverId = serverId,
                        serverManager = serverManager
                    )
                }
                PictureInPictureActivity.STREAM_TYPE_IMAGE -> {
                    ImageStream(
                        streamUrl = streamUrl,
                        serverId = serverId,
                        serverManager = serverManager,
                        okHttpClient = okHttpClient
                    )
                }
            }

            // Show title overlay when not in PiP mode
            if (!isInPipMode && title != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(Color(0x80000000))
                        .padding(16.dp)
                ) {
                    Text(
                        text = title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoStream(
    streamUrl: String,
    serverId: Int?,
    serverManager: ServerManager
) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<Player?>(null) }
    var resolvedUrl by remember { mutableStateOf<String?>(null) }
    var authHeader by remember { mutableStateOf<String?>(null) }

    // Resolve URL and get auth if needed
    LaunchedEffect(streamUrl, serverId) {
        withContext(Dispatchers.IO) {
            try {
                if (UrlUtil.isAbsoluteUrl(streamUrl)) {
                    resolvedUrl = streamUrl
                } else if (serverId != null) {
                    val urlState = serverManager.connectionStateProvider(serverId).urlFlow().first()
                    if (urlState is UrlState.HasUrl) {
                        resolvedUrl = UrlUtil.handle(urlState.url, streamUrl)?.toString()
                        authHeader = serverManager.authenticationRepository(serverId).buildBearerToken()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to resolve stream URL")
            }
        }
    }

    // Initialize/release player based on lifecycle
    LifecycleStartEffect(Unit) {
        player = ExoPlayer.Builder(context).build()
        onStopOrDispose {
            player?.release()
            player = null
        }
    }

    // Set media item when URL is resolved
    LaunchedEffect(resolvedUrl, player) {
        val url = resolvedUrl ?: return@LaunchedEffect
        val p = player ?: return@LaunchedEffect

        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .build()

        p.setMediaItem(mediaItem)
        p.playWhenReady = true
        p.prepare()
    }

    player?.let { p ->
        PlayerSurface(
            player = p,
            modifier = Modifier.fillMaxSize()
        )
    } ?: run {
        // Loading state
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Videocam,
                contentDescription = "Loading",
                tint = Color.Gray,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = "Loading...",
                color = Color.Gray,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun ImageStream(
    streamUrl: String,
    serverId: Int?,
    serverManager: ServerManager,
    okHttpClient: OkHttpClient
) {
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(streamUrl, serverId) {
        while (true) {
            try {
                withContext(Dispatchers.IO) {
                    val resolvedUrl: URL? = if (UrlUtil.isAbsoluteUrl(streamUrl)) {
                        URL(streamUrl)
                    } else if (serverId != null) {
                        val urlState = serverManager.connectionStateProvider(serverId).urlFlow().first()
                        if (urlState is UrlState.HasUrl) {
                            UrlUtil.handle(urlState.url, streamUrl)
                        } else null
                    } else null

                    if (resolvedUrl != null) {
                        val requiresAuth = !UrlUtil.isAbsoluteUrl(streamUrl) && serverId != null
                        val timestampedUrl = "${resolvedUrl}${if (resolvedUrl.toString().contains("?")) "&" else "?"}_ts=${System.currentTimeMillis()}"

                        val request = Request.Builder().apply {
                            url(timestampedUrl)
                            if (requiresAuth) {
                                addHeader("Authorization", serverManager.authenticationRepository(serverId!!).buildBearerToken())
                            }
                        }.build()

                        val response = okHttpClient.newCall(request).execute()
                        val bitmap = BitmapFactory.decodeStream(response.body?.byteStream())
                        response.close()

                        if (bitmap != null) {
                            currentBitmap = bitmap
                            isLoading = false
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load camera image")
            }

            delay(IMAGE_REFRESH_INTERVAL_MS)
        }
    }

    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap!!.asImageBitmap(),
            contentDescription = "Camera stream",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    } else if (isLoading) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Videocam,
                contentDescription = "Loading",
                tint = Color.Gray,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = "Loading...",
                color = Color.Gray,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
