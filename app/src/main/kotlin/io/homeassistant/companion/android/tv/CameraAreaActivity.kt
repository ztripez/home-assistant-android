package io.homeassistant.companion.android.tv

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.BaseActivity
import io.homeassistant.companion.android.BuildConfig
import io.homeassistant.companion.android.common.data.authentication.SessionState
import io.homeassistant.companion.android.common.data.integration.DeviceRegistration
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.database.server.Server
import io.homeassistant.companion.android.database.server.ServerConnectionInfo
import io.homeassistant.companion.android.database.server.ServerSessionInfo
import io.homeassistant.companion.android.database.server.ServerType
import io.homeassistant.companion.android.database.server.ServerUserInfo
import io.homeassistant.companion.android.database.sensor.SensorDao
import io.homeassistant.companion.android.onboarding.OnboardApp
import io.homeassistant.companion.android.onboarding.getMessagingToken
import io.homeassistant.companion.android.sensors.LocationSensorManager
import io.homeassistant.companion.android.settings.SettingViewModel
import io.homeassistant.companion.android.tv.views.CameraAreaView
import io.homeassistant.companion.android.util.UrlUtil
import javax.inject.Inject
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class CameraAreaActivity : BaseActivity() {

    private val viewModel: CameraAreaViewModel by viewModels()

    @Inject
    lateinit var serverManager: ServerManager

    @Inject
    lateinit var sensorDao: SensorDao

    private val settingViewModel: SettingViewModel by viewModels()

    private val registerActivityResult = registerForActivityResult(
        OnboardApp()
    ) { result ->
        onOnboardingComplete(result)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isAuthenticated()) {
            setContent { CameraAreaView(viewModel) }
        } else {
            registerActivityResult.launch(OnboardApp.Input())
        }
    }

    private fun isAuthenticated(): Boolean {
        return try {
            serverManager.isRegistered() &&
                serverManager.authenticationRepository().getSessionState() == SessionState.CONNECTED
        } catch (e: Exception) {
            false
        }
    }

    private fun onOnboardingComplete(result: OnboardApp.Output?) {
        if (result == null) {
            finish()
            return
        }

        lifecycleScope.launch {
            registerServer(result)
            setContent { CameraAreaView(viewModel) }
        }
    }

    private suspend fun registerServer(result: OnboardApp.Output) {
        var serverId: Int? = null
        try {
            val (url, authCode, deviceName, deviceTrackingEnabled, notificationsEnabled) = result
            val messagingToken = getMessagingToken()
            val formattedUrl = UrlUtil.formattedUrlString(url)
            val server = Server(
                _name = deviceName,
                type = ServerType.TEMPORARY,
                connection = ServerConnectionInfo(
                    externalUrl = formattedUrl
                ),
                session = ServerSessionInfo(),
                user = ServerUserInfo()
            )
            serverId = serverManager.addServer(server)
            serverManager.authenticationRepository(serverId).registerAuthorizationCode(authCode)
            serverManager.integrationRepository(serverId).registerDevice(
                DeviceRegistration(
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    deviceName,
                    messagingToken
                )
            )
            serverId = serverManager.convertTemporaryServer(serverId)
        } catch (e: Exception) {
            Timber.e(e, "Exception while registering")
            try {
                if (serverId != null) {
                    serverManager.authenticationRepository(serverId).revokeSession()
                    serverManager.removeServer(serverId)
                }
            } catch (e2: Exception) {
                Timber.e(e2, "Can't revoke session")
            }
            return
        }
        serverId?.let {
            setLocationTracking(it, result.deviceTrackingEnabled)
            setNotifications(it, result.notificationsEnabled)
        }
    }

    private suspend fun setLocationTracking(serverId: Int, enabled: Boolean) {
        sensorDao.setSensorsEnabled(
            sensorIds = listOf(
                LocationSensorManager.backgroundLocation.id,
                LocationSensorManager.zoneLocation.id,
                LocationSensorManager.singleAccurateLocation.id
            ),
            serverId = serverId,
            enabled = enabled
        )
    }

    private fun setNotifications(serverId: Int, enabled: Boolean) {
        // No-op
    }
}
