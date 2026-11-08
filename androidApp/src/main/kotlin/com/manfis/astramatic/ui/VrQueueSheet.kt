package com.manfis.astramatic.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.manfis.astramatic.player.PlaylistItem

/** Очередь — лентой превью: где мы и что дальше; нажатие включает файл. */
@Composable
internal fun QueueSheet(state: VrMenuState, actions: VrMenuActions) {
    val queue = state.queue
    val listState = rememberLazyListState()
    // Текущий — всегда в кадре, чуть правее левого края: видно и предыдущий.
    LaunchedEffect(state.queueIndex, queue.size) {
        if (queue.isNotEmpty()) listState.animateScrollToItem((state.queueIndex - 1).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Очередь", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(12.dp))
            Text(
                if (queue.isEmpty()) "" else "${state.queueIndex + 1} из ${queue.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(queue) { index, item ->
                QueueCard(item, current = index == state.queueIndex) { actions.playQueueItem(index) }
            }
        }
    }
}

@Composable
private fun QueueCard(item: PlaylistItem, current: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }
    val hovered by hoverState(interaction)
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .width(184.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(104.dp)
                .clip(shape)
                .background(if (hovered) CardHover else CardColor)
                .then(
                    when {
                        current -> Modifier.border(2.dp, accent, shape)
                        hovered -> Modifier.border(1.dp, Color(0x55FFFFFF), shape)
                        else -> Modifier
                    },
                ),
        ) {
            if (item.thumbnail != null) {
                AsyncImage(
                    model = item.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    if (item.isImage) Icons.Rounded.Image else Icons.Rounded.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).size(36.dp),
                )
            }
            if (current) {
                Surface(
                    color = accent,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                ) {
                    Text("Сейчас", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
                }
            }
        }
        Text(
            item.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
        )
    }
}
