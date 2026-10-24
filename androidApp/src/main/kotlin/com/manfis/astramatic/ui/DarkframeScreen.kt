package com.manfis.astramatic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.manfis.astramatic.library.DarkframeClient
import com.manfis.astramatic.library.DarkframeServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Подключение к Darkframe как ещё одно устройство (как телефон в Darkframe → «Устройства»),
 * а для уже подключённого — переименование и удаление.
 */
@Composable
fun DarkframeScreen(
    initial: DarkframeServer?,
    onSave: (DarkframeServer) -> Unit,
    onDelete: (DarkframeServer) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun connect(text: String) {
        busy = true
        status = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val server = DarkframeClient.pair(text)
                    // Сразу проверяем, что библиотека открывается с выданным доступом.
                    DarkframeClient.collections(server)
                    server
                }
            }
            busy = false
            result
                .onSuccess { onSave(it.copy(name = name.ifBlank { it.name })) }
                .onFailure { status = it.message ?: it::class.simpleName }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().safeContentPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (initial == null) "Подключить Darkframe" else "Darkframe", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Название (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        if (initial == null) {
            Text(
                "На компьютере в Darkframe откройте «Панель управления → Устройства», включите доступ с телефона " +
                    "и нажмите «Подключить устройство». Отсканируйте QR-код или вставьте ссылку под ним.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(enabled = !busy, onClick = {
                val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                GmsBarcodeScanning.getClient(context, options).startScan()
                    .addOnSuccessListener { barcode -> barcode.rawValue?.let(::connect) }
                    .addOnFailureListener { status = "Сканер недоступен: ${it.message}. Вставьте ссылку вручную." }
            }) { Text("Сканировать QR-код") }

            OutlinedTextField(
                value = link, onValueChange = { link = it },
                label = { Text("…или ссылка") },
                placeholder = { Text("http://192.168.0.126:7860/pair?c=…") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(enabled = !busy && link.isNotBlank(), onClick = { connect(link) }) { Text("Подключить по ссылке") }
        } else {
            Text(initial.baseUrl, style = MaterialTheme.typography.bodySmall)
        }

        status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) CircularProgressIndicator()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (initial != null) {
                Button(onClick = { onSave(initial.copy(name = name.ifBlank { initial.name })) }) { Text("Сохранить") }
                TextButton(onClick = { onDelete(initial) }) { Text("Удалить") }
            }
            TextButton(onClick = onCancel) { Text("Отмена") }
        }
        if (initial != null) {
            Text(
                "Удаление здесь не отключает устройство в Darkframe — его можно убрать в «Устройствах» на компьютере.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
