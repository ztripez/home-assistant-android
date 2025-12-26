package io.homeassistant.companion.android.tv.onboarding

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.tv.onboarding.discovery.HomeAssistantInstance
import io.homeassistant.companion.android.tv.onboarding.discovery.HomeAssistantSearcher
import io.homeassistant.companion.android.common.data.HomeAssistantApis
import io.homeassistant.companion.android.common.data.authentication.impl.AuthenticationService
import io.homeassistant.companion.android.common.data.integration.DeviceRegistration
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.database.server.Server
import io.homeassistant.companion.android.database.server.ServerConnectionInfo
import io.homeassistant.companion.android.database.server.ServerSessionInfo
import io.homeassistant.companion.android.database.server.ServerType
import io.homeassistant.companion.android.database.server.ServerUserInfo
import io.homeassistant.companion.android.tv.BuildConfig
import io.homeassistant.companion.android.tv.home.HomeActivity
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber

@AndroidEntryPoint
class OnboardingActivity : ComponentActivity() {

    @Inject
    lateinit var serverManager: ServerManager

    companion object {
        fun newInstance(context: Context): Intent {
            return Intent(context, OnboardingActivity::class.java)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifiManager = getSystemService(Context.WIFI_SERVICE) as? WifiManager

        setContent {
            MaterialTheme {
                OnboardingScreen(
                    serverManager = serverManager,
                    nsdManager = nsdManager,
                    wifiManager = wifiManager,
                    onComplete = {
                        startActivity(HomeActivity.newInstance(this))
                        finish()
                    }
                )
            }
        }
    }
}

enum class OnboardingStep {
    DISCOVERY,
    MANUAL_URL,
    AUTHENTICATION,
    INTEGRATION
}

data class DiscoveredInstance(
    val name: String,
    val url: String
)

@Composable
fun OnboardingScreen(
    serverManager: ServerManager,
    nsdManager: NsdManager,
    wifiManager: WifiManager?,
    onComplete: () -> Unit
) {
    var currentStep by remember { mutableStateOf(OnboardingStep.DISCOVERY) }
    var selectedUrl by remember { mutableStateOf("") }
    var authCode by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(Build.MODEL) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = Color(0xFF1A1A2E)
        )
    ) {
        when (currentStep) {
            OnboardingStep.DISCOVERY -> DiscoveryScreen(
                nsdManager = nsdManager,
                wifiManager = wifiManager,
                onInstanceSelected = { url ->
                    selectedUrl = url
                    currentStep = OnboardingStep.AUTHENTICATION
                },
                onManualSetup = {
                    currentStep = OnboardingStep.MANUAL_URL
                }
            )
            OnboardingStep.MANUAL_URL -> ManualUrlScreen(
                onUrlEntered = { url ->
                    selectedUrl = url
                    currentStep = OnboardingStep.AUTHENTICATION
                },
                onBack = {
                    currentStep = OnboardingStep.DISCOVERY
                }
            )
            OnboardingStep.AUTHENTICATION -> AuthenticationScreen(
                url = selectedUrl,
                onAuthSuccess = { code ->
                    authCode = code
                    currentStep = OnboardingStep.INTEGRATION
                },
                onBack = {
                    currentStep = OnboardingStep.DISCOVERY
                }
            )
            OnboardingStep.INTEGRATION -> IntegrationScreen(
                serverManager = serverManager,
                url = selectedUrl,
                authCode = authCode,
                deviceName = deviceName,
                onDeviceNameChanged = { deviceName = it },
                onComplete = onComplete,
                onBack = {
                    currentStep = OnboardingStep.DISCOVERY
                }
            )
        }
    }
}

@Composable
fun DiscoveryScreen(
    nsdManager: NsdManager,
    wifiManager: WifiManager?,
    onInstanceSelected: (String) -> Unit,
    onManualSetup: () -> Unit
) {
    val discoveredInstances = remember { mutableStateListOf<DiscoveredInstance>() }
    var isSearching by remember { mutableStateOf(true) }

    val searcher = remember {
        HomeAssistantSearcher(
            nsdManager = nsdManager,
            wifiManager = wifiManager,
            onStart = { isSearching = true },
            onInstanceFound = { instance ->
                val discovered = DiscoveredInstance(
                    name = instance.name,
                    url = instance.url.toString()
                )
                if (discoveredInstances.none { it.url == discovered.url }) {
                    discoveredInstances.add(discovered)
                }
            },
            onError = { isSearching = false }
        )
    }

    DisposableEffect(Unit) {
        searcher.beginSearch()
        onDispose {
            searcher.stopSearch()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Welcome to Home Assistant",
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Connect to your Home Assistant server",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(48.dp))

        if (isSearching) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Searching",
                    tint = Color(0xFF03DAC5),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Searching for Home Assistant...",
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (discoveredInstances.isNotEmpty()) {
            Text(
                text = "Found Instances:",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(discoveredInstances) { instance ->
                    Card(
                        onClick = { onInstanceSelected(instance.url) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        colors = CardDefaults.colors(
                            containerColor = Color(0xFF16213E),
                            focusedContainerColor = Color(0xFF2D2D44)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = null,
                                tint = Color(0xFF03DAC5),
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = instance.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.White
                                )
                                Text(
                                    text = instance.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        Button(
            onClick = onManualSetup,
            colors = ButtonDefaults.colors(
                containerColor = Color(0xFF03DAC5),
                contentColor = Color.Black,
                focusedContainerColor = Color(0xFF018786),
                focusedContentColor = Color.White
            ),
            modifier = Modifier.fillMaxWidth(0.5f)
        ) {
            Text("Enter URL Manually")
        }
    }
}

@Composable
fun ManualUrlScreen(
    onUrlEntered: (String) -> Unit,
    onBack: () -> Unit
) {
    var url by remember { mutableStateOf("http://") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Enter Home Assistant URL",
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Enter the URL of your Home Assistant server",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Custom text field with TV-friendly styling
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .background(Color(0xFF16213E), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            BasicTextField(
                value = url,
                onValueChange = {
                    url = it
                    errorMessage = null
                },
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 18.sp
                ),
                singleLine = true,
                cursorBrush = SolidColor(Color(0xFF03DAC5)),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (validateUrl(url)) {
                            onUrlEntered(url)
                        } else {
                            errorMessage = "Please enter a valid URL"
                        }
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            )
            if (url.isEmpty()) {
                Text(
                    text = "http://homeassistant.local:8123",
                    color = Color.Gray,
                    fontSize = 18.sp
                )
            }
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage!!,
                color = Color.Red,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.width(150.dp)
            ) {
                Text("Back")
            }

            Button(
                onClick = {
                    if (validateUrl(url)) {
                        onUrlEntered(url)
                    } else {
                        errorMessage = "Please enter a valid URL"
                    }
                },
                colors = ButtonDefaults.colors(
                    containerColor = Color(0xFF03DAC5),
                    contentColor = Color.Black,
                    focusedContainerColor = Color(0xFF018786),
                    focusedContentColor = Color.White
                ),
                modifier = Modifier.width(150.dp)
            ) {
                Text("Connect")
            }
        }
    }
}

private fun validateUrl(url: String): Boolean {
    return try {
        val httpUrl = url.toHttpUrlOrNull()
        httpUrl != null
    } catch (e: Exception) {
        false
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AuthenticationScreen(
    url: String,
    onAuthSuccess: (String) -> Unit,
    onBack: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }

    val authUrl = remember(url) {
        try {
            val httpUrl = url.toHttpUrlOrNull() ?: return@remember ""
            val builder = if (httpUrl.host.endsWith("ui.nabu.casa", true)) {
                okhttp3.HttpUrl.Builder()
                    .scheme(httpUrl.scheme)
                    .host(httpUrl.host)
                    .port(httpUrl.port)
            } else {
                httpUrl.newBuilder()
            }
            builder
                .addPathSegments("auth/authorize")
                .addEncodedQueryParameter("response_type", "code")
                .addEncodedQueryParameter("client_id", AuthenticationService.CLIENT_ID)
                .addEncodedQueryParameter("redirect_uri", "homeassistant://auth-callback")
                .build()
                .toString()
        } catch (e: Exception) {
            Timber.e(e, "Unable to build authentication URL")
            ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Sign In to Home Assistant",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )

            OutlinedButton(onClick = onBack) {
                Text("Cancel")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

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
        }

        if (authUrl.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = settings.userAgentString + " ${HomeAssistantApis.USER_AGENT_STRING}"

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val requestUrl = request?.url?.toString() ?: return false
                                    return handleRedirect(requestUrl, onAuthSuccess)
                                }

                                @Deprecated("Deprecated in Java")
                                override fun shouldOverrideUrlLoading(view: WebView?, urlString: String?): Boolean {
                                    return urlString?.let { handleRedirect(it, onAuthSuccess) } ?: false
                                }

                                override fun onPageFinished(view: WebView?, urlString: String?) {
                                    super.onPageFinished(view, urlString)
                                    isLoading = false
                                }
                            }

                            loadUrl(authUrl)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private fun handleRedirect(url: String, onAuthSuccess: (String) -> Unit): Boolean {
    if (url.startsWith("homeassistant://auth-callback")) {
        val code = Uri.parse(url).getQueryParameter("code")
        if (!code.isNullOrBlank()) {
            onAuthSuccess(code)
            return true
        }
    }
    return false
}

@Composable
fun IntegrationScreen(
    serverManager: ServerManager,
    url: String,
    authCode: String,
    deviceName: String,
    onDeviceNameChanged: (String) -> Unit,
    onComplete: () -> Unit,
    onBack: () -> Unit
) {
    var isRegistering by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Complete Setup",
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Configure your device settings",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Device name input
        Column(modifier = Modifier.fillMaxWidth(0.6f)) {
            Text(
                text = "Device Name",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF16213E), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(16.dp)
            ) {
                BasicTextField(
                    value = deviceName,
                    onValueChange = onDeviceNameChanged,
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 18.sp
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(Color(0xFF03DAC5)),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "This name will be used to identify your TV in Home Assistant",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = errorMessage!!,
                color = Color.Red
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                enabled = !isRegistering,
                modifier = Modifier.width(150.dp)
            ) {
                Text("Back")
            }

            Button(
                onClick = {
                    isRegistering = true
                    errorMessage = null

                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                // Create server entry as TEMPORARY first
                                val server = Server(
                                    _name = "",
                                    type = ServerType.TEMPORARY,
                                    connection = ServerConnectionInfo(externalUrl = url),
                                    session = ServerSessionInfo(),
                                    user = ServerUserInfo()
                                )

                                // Add server and register
                                var serverId = serverManager.addServer(server)
                                serverManager.authenticationRepository(serverId).registerAuthorizationCode(authCode)
                                serverManager.integrationRepository(serverId).registerDevice(
                                    DeviceRegistration(
                                        "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                        deviceName
                                    )
                                )
                                serverManager.convertTemporaryServer(serverId)
                            }

                            onComplete()
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to register device")
                            errorMessage = "Failed to register: ${e.message}"
                            isRegistering = false
                        }
                    }
                },
                enabled = !isRegistering && deviceName.isNotBlank(),
                colors = ButtonDefaults.colors(
                    containerColor = Color(0xFF03DAC5),
                    contentColor = Color.Black,
                    focusedContainerColor = Color(0xFF018786),
                    focusedContentColor = Color.White
                ),
                modifier = Modifier.width(150.dp)
            ) {
                if (isRegistering) {
                    Text("Registering...")
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text("Finish")
                    }
                }
            }
        }
    }
}
