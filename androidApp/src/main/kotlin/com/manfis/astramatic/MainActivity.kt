package com.manfis.astramatic

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.manfis.astramatic.player.PendingPlaylist
import com.manfis.astramatic.player.PlayerActivity
import com.manfis.astramatic.ui.LibraryApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    LibraryApp(onPlay = { playlist ->
                        PendingPlaylist.put(playlist)
                        startActivity(Intent(this, PlayerActivity::class.java))
                    })
                }
            }
        }
    }
}
