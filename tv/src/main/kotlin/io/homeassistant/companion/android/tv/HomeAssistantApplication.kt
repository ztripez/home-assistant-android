package io.homeassistant.companion.android.tv

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.HiltAndroidApp
import io.homeassistant.companion.android.common.data.keychain.KeyChainRepository
import io.homeassistant.companion.android.common.data.keychain.KeyStoreRepositoryImpl
import io.homeassistant.companion.android.common.data.keychain.NamedKeyStore
import io.homeassistant.companion.android.tv.sensors.SensorReceiver
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltAndroidApp
open class HomeAssistantApplication : Application() {
    private val ioScope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job())

    @Inject
    @NamedKeyStore
    lateinit var keyStore: KeyChainRepository

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())

        ioScope.launch {
            keyStore.load(applicationContext, KeyStoreRepositoryImpl.ALIAS)
        }

        val sensorReceiver = SensorReceiver()

        // Battery state changes
        ContextCompat.registerReceiver(
            this,
            sensorReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_BATTERY_OKAY)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
            ContextCompat.RECEIVER_EXPORTED
        )

        // Screen state changes (for interactive sensor)
        ContextCompat.registerReceiver(
            this,
            sensorReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            },
            ContextCompat.RECEIVER_EXPORTED
        )

        // Device idle mode changes
        ContextCompat.registerReceiver(
            this,
            sensorReceiver,
            IntentFilter().apply {
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
    }
}
