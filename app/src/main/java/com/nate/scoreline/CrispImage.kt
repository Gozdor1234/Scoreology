package com.nate.scoreline

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Sharp small images on any screen.
 *
 * Logos come from the web at about 500 px and are shown at 20 to 60 dp. On a lower-density
 * screen (a tablet) that means shrinking them 10x or more, and Android's quick shrink skips
 * pixels, which leaves jagged edges. Instead the full image is decoded and halved step by
 * step with smoothing, then fitted to the exact pixel size on screen, which averages every
 * pixel like a proper resize. High-density phones get the same treatment at their larger size.
 */
class SmoothDownscale(private val targetPx: Int, private val fill: Boolean) : Transformation {
    override val cacheKey: String = "smooth-$targetPx-$fill"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap = downscale(input, targetPx, fill)

    companion object {
        /** [fill]: the short side reaches the target (for cropped photos); otherwise the long side (logos). */
        fun downscale(input: Bitmap, targetPx: Int, fill: Boolean): Bitmap {
            val side = { b: Bitmap -> if (fill) min(b.width, b.height) else max(b.width, b.height) }
            var b = input
            while (side(b) / 2 >= targetPx && b.width >= 2 && b.height >= 2) {
                b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
            }
            val s = side(b)
            if (s > targetPx) {
                val k = targetPx.toFloat() / s
                b = Bitmap.createScaledBitmap(b, max(1, (b.width * k).roundToInt()), max(1, (b.height * k).roundToInt()), true)
            }
            return b
        }
    }
}

/** Image request that decodes the full image and smoothly shrinks it to [size] on this screen. */
@Composable
fun rememberCrispRequest(url: String, size: Dp, fill: Boolean = false, configure: ImageRequest.Builder.() -> Unit = {}): ImageRequest {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { ceil(size.toPx()).toInt().coerceAtLeast(1) }
    return remember(url, px, fill) {
        ImageRequest.Builder(ctx)
            .data(url)
            .size(Size.ORIGINAL)
            .transformations(SmoothDownscale(px, fill))
            .apply(configure)
            .build()
    }
}
