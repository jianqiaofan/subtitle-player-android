package com.jianqiaofan.subtitleplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jianqiaofan.subtitleplayer.ui.SubtitlePlayerApp
import com.jianqiaofan.subtitleplayer.ui.theme.SubtitlePlayerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SubtitlePlayerTheme {
                SubtitlePlayerApp()
            }
        }
    }
}
