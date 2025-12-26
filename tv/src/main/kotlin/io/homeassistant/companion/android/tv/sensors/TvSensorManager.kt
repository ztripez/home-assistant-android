package io.homeassistant.companion.android.tv.sensors

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import androidx.core.content.getSystemService
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.sensors.SensorManager

class TvSensorManager : SensorManager {

    companion object {
        private const val TAG = "TvSensor"

        val hdmiCecAvailable = SensorManager.BasicSensor(
            "hdmi_cec_available",
            "binary_sensor",
            commonR.string.sensor_name_hdmi_cec_enabled,
            commonR.string.sensor_description_hdmi_cec_enabled,
            "mdi:hdmi",
            deviceClass = "connectivity",
            updateType = SensorManager.BasicSensor.UpdateType.INTENT
        )

        val displayResolution = SensorManager.BasicSensor(
            "display_resolution",
            "sensor",
            commonR.string.sensor_name_display_resolution,
            commonR.string.sensor_description_display_resolution,
            "mdi:monitor",
            unitOfMeasurement = "px",
            updateType = SensorManager.BasicSensor.UpdateType.INTENT
        )

        val displayRefreshRate = SensorManager.BasicSensor(
            "display_refresh_rate",
            "sensor",
            commonR.string.sensor_name_display_refresh_rate,
            commonR.string.sensor_description_display_refresh_rate,
            "mdi:monitor",
            unitOfMeasurement = "Hz",
            stateClass = SensorManager.STATE_CLASS_MEASUREMENT,
            updateType = SensorManager.BasicSensor.UpdateType.INTENT
        )

        val hdrCapabilities = SensorManager.BasicSensor(
            "hdr_capabilities",
            "sensor",
            commonR.string.sensor_name_hdr_capabilities,
            commonR.string.sensor_description_hdr_capabilities,
            "mdi:television",
            updateType = SensorManager.BasicSensor.UpdateType.INTENT
        )
    }

    override fun docsLink(): String {
        return "https://companion.home-assistant.io/docs/core/sensors"
    }

    override val name: Int
        get() = commonR.string.sensor_name_tv

    override suspend fun getAvailableSensors(context: Context): List<SensorManager.BasicSensor> {
        return listOf(
            hdmiCecAvailable,
            displayResolution,
            displayRefreshRate,
            hdrCapabilities
        )
    }

    override fun requiredPermissions(sensorId: String): Array<String> {
        return emptyArray()
    }

    override fun hasSensor(context: Context): Boolean {
        return true // TV module is only installed on TV devices
    }

    override suspend fun requestSensorUpdate(context: Context) {
        updateHdmiCecAvailable(context)
        updateDisplayResolution(context)
        updateDisplayRefreshRate(context)
        updateHdrCapabilities(context)
    }

    private suspend fun updateHdmiCecAvailable(context: Context) {
        if (!isEnabled(context, hdmiCecAvailable)) {
            return
        }

        val hasHdmiCec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val hdmiManager = context.getSystemService("hdmi_control")
                hdmiManager != null
            } catch (e: Exception) {
                false
            }
        } else {
            false
        }

        onSensorUpdated(
            context,
            hdmiCecAvailable,
            hasHdmiCec,
            hdmiCecAvailable.statelessIcon,
            mapOf(
                "api_level" to Build.VERSION.SDK_INT
            )
        )
    }

    private suspend fun updateDisplayResolution(context: Context) {
        if (!isEnabled(context, displayResolution)) {
            return
        }

        val windowManager = context.getSystemService<WindowManager>()
        if (windowManager != null) {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)

            val resolution = "${metrics.widthPixels}x${metrics.heightPixels}"

            onSensorUpdated(
                context,
                displayResolution,
                resolution,
                displayResolution.statelessIcon,
                mapOf(
                    "width" to metrics.widthPixels,
                    "height" to metrics.heightPixels,
                    "density" to metrics.density,
                    "density_dpi" to metrics.densityDpi
                )
            )
        }
    }

    private suspend fun updateDisplayRefreshRate(context: Context) {
        if (!isEnabled(context, displayRefreshRate)) {
            return
        }

        val windowManager = context.getSystemService<WindowManager>()
        if (windowManager != null) {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            @Suppress("DEPRECATION")
            val refreshRate = display.refreshRate

            onSensorUpdated(
                context,
                displayRefreshRate,
                refreshRate,
                displayRefreshRate.statelessIcon,
                mapOf()
            )
        }
    }

    private suspend fun updateHdrCapabilities(context: Context) {
        if (!isEnabled(context, hdrCapabilities)) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val windowManager = context.getSystemService<WindowManager>()
            if (windowManager != null) {
                @Suppress("DEPRECATION")
                val display = windowManager.defaultDisplay

                val displayHdrCapabilities = display.hdrCapabilities

                val supportedHdrTypes = if (displayHdrCapabilities != null) {
                    displayHdrCapabilities.supportedHdrTypes.map { getHdrTypeName(it) }
                } else {
                    emptyList()
                }

                val state = if (supportedHdrTypes.isNotEmpty()) {
                    supportedHdrTypes.joinToString(", ")
                } else {
                    "None"
                }

                onSensorUpdated(
                    context,
                    hdrCapabilities,
                    state,
                    hdrCapabilities.statelessIcon,
                    mapOf(
                        "supported_types" to supportedHdrTypes,
                        "max_luminance" to (displayHdrCapabilities?.desiredMaxLuminance ?: 0f),
                        "max_average_luminance" to (displayHdrCapabilities?.desiredMaxAverageLuminance ?: 0f),
                        "min_luminance" to (displayHdrCapabilities?.desiredMinLuminance ?: 0f)
                    )
                )
            }
        }
    }

    private fun getHdrTypeName(type: Int): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            when (type) {
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    when (type) {
                        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                        else -> "Unknown ($type)"
                    }
                } else {
                    "Unknown ($type)"
                }
            }
        } else {
            "Unknown"
        }
    }
}
