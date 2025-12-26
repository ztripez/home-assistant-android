package io.homeassistant.companion.android.tv.sensors

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.tv.BuildConfig
import io.homeassistant.companion.android.common.sensors.AndroidOsSensorManager
import io.homeassistant.companion.android.common.sensors.BatterySensorManager
import io.homeassistant.companion.android.common.sensors.DisplaySensorManager
import io.homeassistant.companion.android.common.sensors.KeyguardSensorManager
import io.homeassistant.companion.android.common.sensors.LastRebootSensorManager
import io.homeassistant.companion.android.common.sensors.LastUpdateManager
import io.homeassistant.companion.android.common.sensors.NetworkSensorManager
import io.homeassistant.companion.android.common.sensors.PowerSensorManager
import io.homeassistant.companion.android.common.sensors.SensorManager
import io.homeassistant.companion.android.common.sensors.SensorReceiverBase
import io.homeassistant.companion.android.common.sensors.StorageSensorManager
import io.homeassistant.companion.android.common.sensors.TimeZoneManager
import io.homeassistant.companion.android.common.sensors.TrafficStatsManager
import io.homeassistant.companion.android.tv.home.HomeActivity

@AndroidEntryPoint
class SensorReceiver : SensorReceiverBase() {

    override val currentAppVersion: String
        get() = BuildConfig.VERSION_NAME

    override val managers: List<SensorManager>
        get() = MANAGERS

    companion object {
        val MANAGERS = listOf(
            AndroidOsSensorManager(),
            BatterySensorManager(),
            DisplaySensorManager(),
            KeyguardSensorManager(),
            LastRebootSensorManager(),
            LastUpdateManager(),
            NetworkSensorManager(),
            PowerSensorManager(),
            StorageSensorManager(),
            TimeZoneManager(),
            TrafficStatsManager(),
            TvSensorManager()
        )

        const val ACTION_REQUEST_SENSORS_UPDATE =
            "io.homeassistant.companion.android.background.REQUEST_SENSORS_UPDATE"

        fun updateAllSensors(context: Context) {
            val intent = Intent(context, SensorReceiver::class.java)
            intent.action = ACTION_UPDATE_SENSORS
            context.sendBroadcast(intent)
        }
    }

    @SuppressLint("InlinedApi")
    override val skippableActions = mapOf(
        Intent.ACTION_SCREEN_OFF to listOf(PowerSensorManager.interactiveDevice.id),
        Intent.ACTION_SCREEN_ON to listOf(PowerSensorManager.interactiveDevice.id),
        PowerManager.ACTION_POWER_SAVE_MODE_CHANGED to listOf(PowerSensorManager.powerSave.id),
        PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED to listOf(PowerSensorManager.doze.id)
    )

    override fun getSensorSettingsIntent(
        context: Context,
        sensorId: String,
        sensorManagerId: String,
        notificationId: Int
    ): PendingIntent? {
        val intent = Intent(context, HomeActivity::class.java)
        return PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
