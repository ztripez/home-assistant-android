package io.homeassistant.companion.android.tv.views

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.homeassistant.companion.android.common.R
import io.homeassistant.companion.android.common.data.integration.Entity
import io.homeassistant.companion.android.tv.CameraAreaViewModel
import io.homeassistant.companion.android.util.compose.HomeAssistantAppTheme

@Composable
fun CameraAreaView(viewModel: CameraAreaViewModel) {
    HomeAssistantAppTheme {
        if (viewModel.isLoading.value) {
            CircularProgressIndicator(modifier = Modifier.fillMaxSize().padding(32.dp))
        } else {
            val areas = viewModel.areas.value
            LazyColumn(contentPadding = PaddingValues(16.dp)) {
                areas.forEach { area ->
                    val cameras = viewModel.camerasByArea[area.areaId].orEmpty()
                    if (cameras.isNotEmpty()) {
                        item {
                            Text(
                                text = area.name,
                                style = MaterialTheme.typography.h6,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        items(cameras) { camera ->
                            CameraRow(camera, viewModel)
                        }
                    }
                }
                val noAreaCameras = viewModel.camerasByArea[null].orEmpty()
                if (noAreaCameras.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(id = R.string.no_area),
                            style = MaterialTheme.typography.h6,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(noAreaCameras) { camera ->
                        CameraRow(camera, viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraRow(camera: Entity, viewModel: CameraAreaViewModel) {
    val context = LocalContext.current
    Text(
        text = camera.friendlyName,
        modifier = Modifier
            .padding(start = 16.dp, bottom = 4.dp)
            .focusable()
            .clickable { viewModel.openCamera(context, camera) }
    )
}
