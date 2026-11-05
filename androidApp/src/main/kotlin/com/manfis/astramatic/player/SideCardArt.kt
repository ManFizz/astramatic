package com.manfis.astramatic.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap

/** Картинка соседнего окна: превью, сверху куда ведёт, снизу имя файла. */
object SideCardArt {
    private const val WIDTH = 640
    private const val HEIGHT = 400
    private const val RADIUS = 28f
    private const val PADDING = 28f
    private const val BAND = 84f

    private fun label(card: SideCard) = when (card.kind) {
        SideKind.Previous -> "◀ Предыдущее"
        SideKind.Next -> "Следующее ▶"
        SideKind.Hub -> "🏠 Хаб"
    }

    suspend fun loadThumbnail(context: Context, card: SideCard): Bitmap? {
        val thumbnail = card.thumbnail ?: return null
        val request = ImageRequest.Builder(context).data(thumbnail).size(WIDTH).allowHardware(false).build()
        return (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
    }

    fun draw(card: SideCard, thumbnail: Bitmap?): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val outline = Path().apply {
            addRoundRect(RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()), RADIUS, RADIUS, Path.Direction.CW)
        }
        canvas.clipPath(outline)
        canvas.drawColor(Color.rgb(38, 38, 46))
        thumbnail?.let { canvas.drawBitmap(it, centerCrop(it), Rect(0, 0, WIDTH, HEIGHT), Paint(Paint.FILTER_BITMAP_FLAG)) }
        val shade = Paint().apply { color = Color.argb(150, 0, 0, 0) }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), BAND, shade)
        canvas.drawRect(0f, HEIGHT - BAND, WIDTH.toFloat(), HEIGHT.toFloat(), shade)
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f
        }
        canvas.drawText(label(card), PADDING, 56f, text)
        card.title?.let {
            val line = TextUtils.ellipsize(it, text, WIDTH - 2 * PADDING, TextUtils.TruncateAt.END)
            canvas.drawText(line, 0, line.length, PADDING, HEIGHT - 28f, text)
        }
        return bitmap
    }

    private fun centerCrop(source: Bitmap): Rect {
        val target = WIDTH.toFloat() / HEIGHT
        return if (source.width.toFloat() / source.height > target) {
            val width = (source.height * target).toInt()
            val left = (source.width - width) / 2
            Rect(left, 0, left + width, source.height)
        } else {
            val height = (source.width / target).toInt()
            val top = (source.height - height) / 2
            Rect(0, top, source.width, top + height)
        }
    }
}
