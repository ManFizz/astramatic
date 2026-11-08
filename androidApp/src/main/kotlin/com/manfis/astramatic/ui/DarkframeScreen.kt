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
import com.manfis.astramatic.library.DesktopClient
import com.manfis.astramatic.library.DesktopServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Подключение к Darkframe как ещё одно устройство (как телефон в Darkframe → «Устройства»). */
@Composable
fun DarkframeScreen(
    initial: DarkframeServer?,
    onSave: (DarkframeServer) -> Unit,
    onDelete: (DarkframeServer) -> Unit,
    onCancel: () -> Unit,
) = PairScreen(
    title = "Darkframe",
    instructions = "На компьютере в Darkframe откройте «Панель управления → Устройства», включите доступ с телефона " +
        "и нажмите «Подключить устройство». Отсканируйте QR-код или вставьте ссылку под ним.",
    placeholder = "http://192.168.0.126:7860/pair?c=…",
    deleteNote = "Удаление здесь не отключает устройство в Darkframe — его можно убрать в «Устройствах» на компьютере.",
    initial = initial,
    initialName = initial?.name,
    address = initial?.baseUrl,
    pair = { link ->
        DarkframeClient.pair(link).also {
            // Сразу проверяем, что библиотека открывается с выданным доступом.
            DarkframeClient.collections(it)
        }
    },
    rename = { server, name -> server.copy(name = name.ifBlank { server.name }) },
    onSave = onSave,
    onDelete = onDelete,
    onCancel = onCancel,
)

/** Подключение к AstraMatic на ПК по QR-коду из его окна. */
@Composable
fun DesktopScreen(
    initial: DesktopServer?,
    onSave: (DesktopServer) -> Unit,
    onDelete: (DesktopServer) -> Unit,
    onCancel: () -> Unit,
) = PairScreen(
    title = "Компьютер (AstraMatic)",
    instructions = "Запустите AstraMatic на компьютере, добавьте в нём папки с видео и отсканируйте QR-код из окна. " +
        "Телефон и компьютер должны быть в одной сети.",
    placeholder = "http://192.168.0.126:47860/pair?key=…",
    deleteNote = "Чтобы отключить все телефоны сразу, на ПК есть кнопка «Новый ключ».",
    initial = initial,
    initialName = initial?.name,
    address = initial?.baseUrl,
    pair = DesktopClient::pair,
    rename = { server, name -> server.copy(name = name.ifBlank { server.name }) },
    onSave = onSave,
    onDelete = onDelete,
    onCancel = onCancel,
)

/** Общий экран: подключение по QR-коду или ссылке, для уже подключённого — переименование и удаление. */
@Composable
private fun <T> PairScreen(
    title: String,
    instructions: String,
    placeholder: String,
    deleteNote: String,
    initial: T?,
    initialName: String?,
    address: String?,
    pair: (String) -> T,
    rename: (T, String) -> T,
    onSave: (T) -> Unit,
    onDelete: (T) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(initialName ?: "") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun connect(text: String) {
        busy = true
        status = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { pair(text) } }
            busy = false
            result
                .onSuccess { onSave(rename(it, name)) }
                .onFailure { status = it.message ?: it::class.simpleName }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().safeContentPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (initial == null) "Подключить: $title" else title, style = MaterialTheme.typography.headlineSmall)

        AppTextField(name, { name = it }, label = "Название (необязательно)", modifier = Modifier.fillMaxWidth())

        if (initial == null) {
            Text(instructions, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // В шлеме камера закрыта: только ссылка.
            if (LocalVrKeyboard.current == null) androidx.compose.material3.Button(enabled = !busy, onClick = {
                val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                GmsBarcodeScanning.getClient(context, options).startScan()
                    .addOnSuccessListener { barcode -> barcode.rawValue?.let(::connect) }
                    .addOnFailureListener { status = "Сканер недоступен: ${it.message}. Вставьте ссылку вручную." }
            }) { Text("Сканировать QR-код") }

            AppTextField(
                link, { link = it },
                label = "Ссылка",
                placeholder = placeholder,
                modifier = Modifier.fillMaxWidth(),
                onDone = { if (!busy && link.isNotBlank()) connect(link) },
            )
            AppButton("Подключить по ссылке", { connect(link) }, style = ButtonStyle.Filled, enabled = !busy && link.isNotBlank())
        } else if (address != null) {
            Text(address, style = MaterialTheme.typography.bodySmall)
        }

        status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) CircularProgressIndicator()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (initial != null) {
                AppButton("Сохранить", { onSave(rename(initial, name)) }, style = ButtonStyle.Filled)
                AppButton("Удалить", { onDelete(initial) }, style = ButtonStyle.Ghost)
            }
            AppButton("Отмена", onCancel, style = ButtonStyle.Ghost)
        }
        if (initial != null) Hint(deleteNote)
    }
}
