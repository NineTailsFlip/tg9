package dev.tg9.media

import org.drinkless.tdlib.TdApi

/** The arithmetic behind [MediaActivity], apart from the screen so it can be tested. */

/** Where a picture goes on screen: drawn at [scale], its top-left corner at ([left], [top]), with
 *  the pan clamped to what's actually off screen ([panX], [panY], the values to keep). */
data class Placement(val scale: Float, val left: Float, val top: Float, val panX: Float, val panY: Float)

/** Fits a [drawableWidth]×[drawableHeight] picture into a [viewWidth]×[viewHeight] view, centred,
 *  [zoom] times bigger when [zoomed], moved by the pan but never past the picture's own edges.
 *  Null while either size is still unknown. */
fun place(
    drawableWidth: Float,
    drawableHeight: Float,
    viewWidth: Float,
    viewHeight: Float,
    zoomed: Boolean,
    zoom: Float,
    panX: Float,
    panY: Float,
): Placement? {
    if (viewWidth <= 0f || viewHeight <= 0f || drawableWidth <= 0f || drawableHeight <= 0f) return null
    val fit = minOf(viewWidth / drawableWidth, viewHeight / drawableHeight)
    val scale = if (zoomed) fit * zoom else fit
    val maxPanX = ((drawableWidth * scale - viewWidth) / 2).coerceAtLeast(0f)
    val maxPanY = ((drawableHeight * scale - viewHeight) / 2).coerceAtLeast(0f)
    val x = panX.coerceIn(-maxPanX, maxPanX)
    val y = panY.coerceIn(-maxPanY, maxPanY)
    return Placement(
        scale = scale,
        left = (viewWidth - drawableWidth * scale) / 2 + x,
        top = (viewHeight - drawableHeight * scale) / 2 + y,
        panX = x,
        panY = y,
    )
}

/** The power of two to decode a [width]×[height] picture down by, so its longer side stays at
 *  least [target]: enough detail to zoom into, never a full camera-resolution bitmap. */
fun decodeSampleSize(width: Int, height: Int, target: Int): Int {
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= target) sample *= 2
    return sample
}

/** Of [found], the nearest message older ([direction] < 0) or newer than [currentId]; whether
 *  TDLib counted the current one in doesn't matter. */
fun neighbour(found: Array<TdApi.Message>, currentId: Long, direction: Int): TdApi.Message? =
    if (direction < 0) {
        found.filter { it.id < currentId }.maxByOrNull { it.id }
    } else {
        found.filter { it.id > currentId }.minByOrNull { it.id }
    }
