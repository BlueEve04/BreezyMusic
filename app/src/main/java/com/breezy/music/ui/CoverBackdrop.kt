package com.breezy.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.breezy.ui.theme.BreezyTheme
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * 封面取色 + 模糊背景。
 *
 * 模糊刻意**不用 `Modifier.blur`**：它是 RenderEffect，拖动/动画时每帧都要算一次全屏高斯，
 * 更麻烦的是"什么时候开始模糊"会露馅——动画中途打开它，观众会先看到一张没模糊的图再突然变糊，
 * 也就是"闪现一下没模糊的背景"。
 *
 * 这里改成**降采样模糊**：把封面缩到 8px 再放大到 96px，等于一次强低通。
 * 一次算好、按路径缓存，之后每帧只是把这张小位图铺开，零渲染副作用，也没有开关时机。
 */
object CoverPalette {

    /** 主色缓存（Dock 取色用） */
    private val colorCache = LruCache<String, Color>(24)

    /** 已柔化的小位图缓存（背景用，160px 见方，内存可忽略） */
    private val artCache = LruCache<String, Bitmap>(6)

    /** 封面强调色缓存（播放按钮、进度条用） */
    private val accentCache = LruCache<String, Color>(24)

    /** 封面主色调，用于给 Dock / 卡片上色 */
    fun muted(context: Context, path: String?): Color? {
        if (path.isNullOrBlank()) return null
        colorCache.get(path)?.let { return it }
        val bmp = decodeSmall(context, path) ?: return null
        val color = averageOf(bmp)
        colorCache.put(path, color)
        return color
    }

    private fun averageOf(bmp: Bitmap): Color {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        val stepX = (bmp.width / 8).coerceAtLeast(1)
        val stepY = (bmp.height / 8).coerceAtLeast(1)
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                if (android.graphics.Color.alpha(c) > 16) {
                    r += android.graphics.Color.red(c)
                    g += android.graphics.Color.green(c)
                    b += android.graphics.Color.blue(c)
                    n++
                }
                x += stepX
            }
            y += stepY
        }
        if (n == 0) return Color.Gray
        return Color(red = r / n / 255f, green = g / n / 255f, blue = b / n / 255f)
    }

    /**
     * 背景用的"已经模糊好"的封面：8px → 96px 双线性放大。
     * 结果按路径缓存，切歌才重算；同一首歌反复上拉下拉都用同一张。
     */
    fun blurredArtwork(context: Context, path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        artCache.get(path)?.let { return it }
        val small = decodeSmall(context, path) ?: return null
        // 先把封面缩到 160px：再大也没意义，屏幕上是铺满的色块，细节最终都要被糊掉。
        val base = Bitmap.createScaledBitmap(small, 160, 160, true)
        if (small !== base && !small.isRecycled) small.recycle()
        val soft = boxBlurBitmap(base, radius = 9, passes = 3)
        if (base !== soft && !base.isRecycled) base.recycle()
        artCache.put(path, soft)
        return soft
    }

    /**
     * 三次盒式模糊逼近高斯（可分离：先横后竖，每趟用滑动窗口累加，复杂度与半径无关）。
     *
     * 之前的做法是 8px 放大到 96px，本质上只做了一次极粗的双线性插值：
     * 出来的色块边界有明显方向性、明暗过渡是直线，观感"像塑料"。
     * 真正的模糊要在**原始像素**上做低通，再放大由 GPU 双线性收尾，才像镜头虚化。
     */
    private fun boxBlurBitmap(src: Bitmap, radius: Int, passes: Int): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var buf = px
        repeat(passes) {
            buf = boxBlurH(buf, w, h, radius)
            buf = boxBlurV(buf, w, h, radius)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(buf, 0, w, 0, 0, w, h)
        return out
    }

    private fun boxBlurH(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val dst = IntArray(src.size)
        val div = r * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            var a = 0; var rr = 0; var gg = 0; var bb = 0
            for (i in -r..r) {
                val c = src[row + i.coerceIn(0, w - 1)]
                a += c ushr 24 and 0xFF
                rr += c shr 16 and 0xFF
                gg += c shr 8 and 0xFF
                bb += c and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = (a / div shl 24) or (rr / div shl 16) or (gg / div shl 8) or (bb / div)
                val out = src[row + (x - r).coerceIn(0, w - 1)]
                val inn = src[row + (x + r + 1).coerceIn(0, w - 1)]
                a += (inn ushr 24 and 0xFF) - (out ushr 24 and 0xFF)
                rr += (inn shr 16 and 0xFF) - (out shr 16 and 0xFF)
                gg += (inn shr 8 and 0xFF) - (out shr 8 and 0xFF)
                bb += (inn and 0xFF) - (out and 0xFF)
            }
        }
        return dst
    }

    private fun boxBlurV(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val dst = IntArray(src.size)
        val div = r * 2 + 1
        for (x in 0 until w) {
            var a = 0; var rr = 0; var gg = 0; var bb = 0
            for (i in -r..r) {
                val c = src[i.coerceIn(0, h - 1) * w + x]
                a += c ushr 24 and 0xFF
                rr += c shr 16 and 0xFF
                gg += c shr 8 and 0xFF
                bb += c and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = (a / div shl 24) or (rr / div shl 16) or (gg / div shl 8) or (bb / div)
                val out = src[(y - r).coerceIn(0, h - 1) * w + x]
                val inn = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                a += (inn ushr 24 and 0xFF) - (out ushr 24 and 0xFF)
                rr += (inn shr 16 and 0xFF) - (out shr 16 and 0xFF)
                gg += (inn shr 8 and 0xFF) - (out shr 8 and 0xFF)
                bb += (inn and 0xFF) - (out and 0xFF)
            }
        }
        return dst
    }

    /** 从封面里取一个"能用"的强调色：平均色往往偏灰，这里提饱和度再压明度。 */
    fun accent(context: Context, path: String?): Color? {
        if (path.isNullOrBlank()) return null
        accentCache.get(path)?.let { return it }
        val bmp = decodeSmall(context, path) ?: return null
        val avg = averageOf(bmp)
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(
            android.graphics.Color.rgb(
                (avg.red * 255).toInt().coerceIn(0, 255),
                (avg.green * 255).toInt().coerceIn(0, 255),
                (avg.blue * 255).toInt().coerceIn(0, 255)
            ),
            hsv
        )
        hsv[1] = hsv[1].coerceAtLeast(0.42f).coerceAtMost(0.92f)
        hsv[2] = hsv[2].coerceIn(0.55f, 0.80f)
        val boosted = Color(android.graphics.Color.HSVToColor(hsv))
        accentCache.put(path, boosted)
        return boosted
    }

    /**
     * 封面来源顺序：内嵌封面（ID3/FLAC 图片块）→ 文件本身。
     * 音乐文件不是图片，直接 BitmapFactory 解一定失败——这一步是背景曾经"永远是纯色"的原因。
     */
    internal fun decodeSmall(context: Context, path: String): Bitmap? {
        embeddedArtwork(context, path)?.let { return it }
        return runCatching { decodeFileOrStream(context, path) }.getOrNull()
    }

    private fun embeddedArtwork(context: Context, path: String): Bitmap? = runCatching {
        val mmr = android.media.MediaMetadataRetriever()
        try {
            if (path.startsWith("content://")) {
                mmr.setDataSource(context, Uri.parse(path))
            } else {
                mmr.setDataSource(path)
            }
            val bytes = mmr.embeddedPicture ?: return@runCatching null
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } finally {
            runCatching { mmr.release() }
        }
    }.getOrNull()

    private fun decodeFileOrStream(context: Context, path: String): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openCover(context, path)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = if (bounds.outWidth > 0) (bounds.outWidth / 32).coerceAtLeast(1) else 8
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        openCover(context, path)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    private fun openCover(context: Context, path: String): InputStream? = runCatching {
        if (path.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(path))
        } else {
            FileInputStream(File(path))
        }
    }.getOrNull()
}

/**
 * 铺满父容器的"模糊封面"背景。三页共用同一层，所以它不参与翻页缩放。
 *
 * @param alpha 整体透明度，用来跟随展开进度淡入
 * @param scrim 背景色遮罩强度；越大越"退后"，文字越好读
 */
@Composable
fun CoverBlurLayer(
    path: String?,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    scrim: Float = 0.55f,
    shape: Shape = RectangleShape,
) {
    val context = LocalContext.current
    val bmp = remember(path) { CoverPalette.blurredArtwork(context, path) }
    val bg = BreezyTheme.colors.background

    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.coerceIn(0f, 1f) }
            .clip(shape)
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 1f
            )
        } else {
            Box(Modifier.fillMaxSize().background(bg))
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            bg.copy(alpha = scrim),
                            bg.copy(alpha = (scrim + 0.22f).coerceAtMost(1f))
                        )
                    )
                )
        )
    }
}
