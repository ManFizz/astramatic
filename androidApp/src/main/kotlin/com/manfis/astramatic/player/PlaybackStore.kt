package com.manfis.astramatic.player

import android.content.Context
import android.os.Handler
import com.manfis.astramatic.library.DarkframeClient
import com.manfis.astramatic.library.DarkframeStore
import com.manfis.astramatic.model.VideoFormat
import com.manfis.astramatic.settings.AppSettings
import java.util.concurrent.Executor

/**
 * Позиции, форматы и история на телефоне, а у видео из Darkframe позиция общая с Darkframe.
 * [io] — сеть не на главном потоке; [main] — главный поток.
 */
class PlaybackStore(
    context: Context,
    private val settings: AppSettings,
    private val io: Executor,
    private val main: Handler,
) : PlaybackMemory {
    private val positions = ResumePositions(context)
    private val formats = FormatMemory(context)
    private val history = History.get(context)
    private val darkframes = DarkframeStore(context)

    override fun savedFormat(item: PlaylistItem) = formats.get(item.uri)
    override fun saveFormat(item: PlaylistItem, format: VideoFormat) = formats.save(item.uri, format)
    override fun clearFormat(item: PlaylistItem) = formats.clear(item.uri)

    override fun opened(item: PlaylistItem) {
        if (settings.keepHistory) history.opened(item)
    }

    override fun savePosition(item: PlaylistItem, positionMs: Long, durationMs: Long) {
        positions.save(item.uri, positionMs, durationMs)
        if (settings.keepHistory && durationMs > 0) history.progress(item.uri, positionMs, durationMs)
        val ref = item.darkframe ?: return
        val server = darkframes.get(ref.serverId) ?: return
        if (durationMs <= 0) return
        io.execute { runCatching { DarkframeClient.savePosition(server, ref.itemId, positionMs, durationMs) } }
    }

    /** У видео из Darkframe позиция общая с Darkframe, у остальных — своя, на телефоне. */
    override fun resume(item: PlaylistItem, seek: (Long) -> Unit) {
        val ref = item.darkframe
        if (ref == null) {
            positions.get(item.uri)?.let(seek)
            return
        }
        val server = darkframes.get(ref.serverId) ?: return
        io.execute {
            val position = runCatching { DarkframeClient.position(server, ref.itemId) }.getOrNull() ?: return@execute
            main.post { seek(position) }
        }
    }
}
