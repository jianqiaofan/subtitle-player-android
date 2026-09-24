package com.jianqiaofan.subtitleplayer.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.jianqiaofan.subtitleplayer.domain.display.PreferredOrientation

fun Activity.applyPreferredOrientation(orientation: PreferredOrientation) {
    requestedOrientation = when (orientation) {
        PreferredOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        PreferredOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        PreferredOrientation.System -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}

fun PreferredOrientation.toggledFrom(devicePortrait: Boolean): PreferredOrientation =
    when (this) {
        PreferredOrientation.Landscape -> PreferredOrientation.Portrait
        PreferredOrientation.Portrait -> PreferredOrientation.Landscape
        PreferredOrientation.System ->
            if (devicePortrait) PreferredOrientation.Landscape else PreferredOrientation.Portrait
    }

@Composable
fun ApplyPreferredOrientation(orientation: PreferredOrientation) {
    val context = LocalContext.current
    LaunchedEffect(orientation) {
        (context as? Activity)?.applyPreferredOrientation(orientation)
    }
}

@Composable
fun rememberDevicePortrait(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
}
