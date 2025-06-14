package io.homeassistant.companion.android.tv

import android.app.PictureInPictureParams
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.BaseActivity
import io.homeassistant.companion.android.webview.WebViewActivity
import android.content.Context
import android.content.Intent

@AndroidEntryPoint
class CameraPiPActivity : BaseActivity() {

    companion object {
        fun newInstance(context: Context, path: String?, serverId: Int): Intent {
            return WebViewActivity.newInstance(context, path, serverId).setClass(context, CameraPiPActivity::class.java)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val bounds = Rect(0, 0, 1920, 1080)
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(bounds.width(), bounds.height()))
                    .setSourceRectHint(bounds)
                    .build()
            )
        }
    }
}
