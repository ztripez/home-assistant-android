package io.homeassistant.companion.android.tv.notifications

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.data.servers.UrlState
import io.homeassistant.companion.android.common.notifications.DeviceCommandData
import io.homeassistant.companion.android.common.notifications.NotificationData
import io.homeassistant.companion.android.common.notifications.clearNotification
import io.homeassistant.companion.android.common.notifications.getGroupNotificationBuilder
import io.homeassistant.companion.android.common.notifications.handleChannel
import io.homeassistant.companion.android.common.notifications.handleColor
import io.homeassistant.companion.android.common.notifications.handleDeleteIntent
import io.homeassistant.companion.android.common.notifications.handleSmallIcon
import io.homeassistant.companion.android.common.notifications.handleText
import io.homeassistant.companion.android.common.util.cancelGroupIfNeeded
import io.homeassistant.companion.android.common.util.getActiveNotification
import io.homeassistant.companion.android.database.AppDatabase
import io.homeassistant.companion.android.database.notification.NotificationItem
import io.homeassistant.companion.android.tv.home.HomeActivity
import io.homeassistant.companion.android.tv.sensors.SensorReceiver
import io.homeassistant.companion.android.util.UrlUtil
import java.net.URL
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

class MessagingManager @Inject constructor(
    @ApplicationContext val context: Context,
    private val serverManager: ServerManager,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val THIS_SERVER_ID = "server_id"
        private const val IMAGE_URL = "image"
        private const val ICON_URL = "icon_url"
        private const val COMMAND_SCREEN_ON = "command_screen_on"
    }

    private val mainScope: CoroutineScope = CoroutineScope(Dispatchers.Main + Job())

    fun handleMessage(notificationData: Map<String, String>, source: String) {
        mainScope.launch {
            val notificationDao = AppDatabase.getInstance(context).notificationDao()
            val now = System.currentTimeMillis()

            val jsonData = notificationData as Map<String, String>?
            val jsonObject = jsonData?.let { JSONObject(it) }
            val serverId = jsonData?.get(NotificationData.WEBHOOK_ID)?.let {
                serverManager.getServer(webhookId = it)?.id
            } ?: ServerManager.SERVER_ID_ACTIVE
            val notificationRow =
                NotificationItem(0, now, notificationData[NotificationData.MESSAGE].toString(), jsonObject.toString(), source, serverId)
            notificationDao.add(notificationRow)
            if (serverManager.getServer(serverId) == null) {
                Timber.w("Received notification but no server for it, discarding")
                return@launch
            }

            val allowCommands = serverManager.integrationRepository(serverId).isTrusted()
            val dataWithServerId = notificationData + mapOf(THIS_SERVER_ID to serverId.toString())

            val message = notificationData[NotificationData.MESSAGE]
            when {
                message == NotificationData.CLEAR_NOTIFICATION && !notificationData["tag"].isNullOrBlank() -> {
                    clearNotification(context, notificationData["tag"]!!)
                }
                message == DeviceCommandData.COMMAND_UPDATE_SENSORS -> SensorReceiver.updateAllSensors(context)
                message == COMMAND_SCREEN_ON && allowCommands -> {
                    handleScreenOn()
                }
                else -> sendNotification(dataWithServerId, now)
            }
        }
    }

    private fun handleScreenOn() {
        val powerManager = context.getSystemService<PowerManager>()
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "HomeAssistant::NotificationScreenOnWakeLock"
        )
        wakeLock?.acquire(30 * 1000L) // 30 seconds
        wakeLock?.release()
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendNotification(data: Map<String, String>, received: Long? = null) {
        val notificationManagerCompat = NotificationManagerCompat.from(context)

        val tag = data["tag"]
        val messageId = tag?.hashCode() ?: received?.toInt() ?: System.currentTimeMillis().toInt()

        var group = data["group"]
        var groupId = 0
        var previousGroup = ""
        var previousGroupId = 0
        if (!group.isNullOrBlank()) {
            group = NotificationData.GROUP_PREFIX + group
            groupId = group.hashCode()
        } else {
            val notification = notificationManagerCompat.getActiveNotification(tag, messageId)
            if (notification != null && notification.isGroup) {
                previousGroup = NotificationData.GROUP_PREFIX + notification.tag
                previousGroupId = previousGroup.hashCode()
            }
        }

        val channelId = handleChannel(context, notificationManagerCompat, data)

        val notificationBuilder = NotificationCompat.Builder(context, channelId)

        handleSmallIcon(context, notificationBuilder, data)

        handleText(notificationBuilder, data)

        handleColor(context, notificationBuilder, data)

        handleGroup(notificationBuilder, group, data[NotificationData.ALERT_ONCE].toBoolean())

        handleSticky(notificationBuilder, data)

        handleContentIntent(notificationBuilder)

        handleLargeIcon(notificationBuilder, data)

        handleImage(notificationBuilder, data)

        handleDeleteIntent(context, notificationBuilder, data, messageId, group, groupId, null)

        notificationManagerCompat.apply {
            Timber.d("Show notification with tag \"$tag\" and id \"$messageId\"")
            notify(tag, messageId, notificationBuilder.build())
            if (!group.isNullOrBlank()) {
                Timber.d("Show group notification with tag \"$group\" and id \"$groupId\"")
                notify(group, groupId, getGroupNotificationBuilder(context, channelId, group, data).build())
            } else {
                if (previousGroup.isNotBlank()) {
                    Timber.d(
                        "Remove group notification with tag \"$previousGroup\" and id \"$previousGroupId\""
                    )
                    notificationManagerCompat.cancelGroupIfNeeded(previousGroup, previousGroupId)
                }
            }
        }
    }

    private fun handleContentIntent(builder: NotificationCompat.Builder) {
        val intent = HomeActivity.newInstance(context).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(pendingIntent)
    }

    private fun handleGroup(builder: NotificationCompat.Builder, group: String?, alertOnce: Boolean?) {
        if (!group.isNullOrBlank()) {
            builder.setGroup(group)
            if (alertOnce == true) {
                builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            }
        }
    }

    private fun handleSticky(builder: NotificationCompat.Builder, data: Map<String, String>) {
        val sticky = data["sticky"]?.toBoolean() ?: false
        builder.setAutoCancel(!sticky)
    }

    private suspend fun handleLargeIcon(builder: NotificationCompat.Builder, data: Map<String, String>) {
        val iconUrl = data[ICON_URL]
        if (!iconUrl.isNullOrBlank()) {
            val serverId = data[THIS_SERVER_ID]?.toIntOrNull() ?: return
            val bitmap = getImageBitmap(serverId, iconUrl.trim().replace(" ", "%20"))
            if (bitmap != null) {
                builder.setLargeIcon(bitmap)
            }
        }
    }

    private suspend fun handleImage(builder: NotificationCompat.Builder, data: Map<String, String>) {
        val imageUrl = data[IMAGE_URL]
        if (!imageUrl.isNullOrBlank()) {
            val serverId = data[THIS_SERVER_ID]?.toIntOrNull() ?: return
            val bitmap = getImageBitmap(serverId, imageUrl.trim().replace(" ", "%20"))
            if (bitmap != null) {
                builder.setLargeIcon(bitmap)
                builder.setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(bitmap)
                        .bigLargeIcon(null as Bitmap?)
                )
            }
        }
    }

    private suspend fun getImageBitmap(serverId: Int, imageUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val urlState = serverManager.connectionStateProvider(serverId).urlFlow().first()
            if (urlState !is UrlState.HasUrl) {
                Timber.w("Not fetching image since URL is unavailable")
                return@withContext null
            }

            val url: URL? = if (UrlUtil.isAbsoluteUrl(imageUrl)) {
                URL(imageUrl)
            } else {
                UrlUtil.handle(urlState.url, imageUrl)
            }

            if (url == null) {
                return@withContext null
            }

            val requiresAuth = !UrlUtil.isAbsoluteUrl(imageUrl)
            val request = Request.Builder().apply {
                url(url)
                if (requiresAuth) {
                    addHeader("Authorization", serverManager.authenticationRepository(serverId).buildBearerToken())
                }
            }.build()

            val response = okHttpClient.newCall(request).execute()
            val bitmap = BitmapFactory.decodeStream(response.body?.byteStream())
            response.close()
            bitmap
        } catch (e: Exception) {
            Timber.e(e, "Couldn't download image for notification")
            null
        }
    }
}
