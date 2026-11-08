package com.manfis.astramatic.settings

import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.manfis.astramatic.ui.SettingsScreen

/** Настройки с главного экрана телефона. Кнопки пульта назначаются в меню плеера — там приходят нажатия. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            com.manfis.astramatic.ui.AppTheme(vr = false) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeContentPadding()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            com.manfis.astramatic.ui.AppIconButton(
                                androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack,
                                "Назад",
                                ::finish,
                            )
                            Text("Настройки", style = MaterialTheme.typography.titleLarge)
                        }
                        HorizontalDivider()
                        SettingsScreen()
                    }
                }
            }
        }
    }
}
