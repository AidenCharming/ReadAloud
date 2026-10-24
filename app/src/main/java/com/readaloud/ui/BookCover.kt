package com.readaloud.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private val palette = listOf(
    Color(0xFF1F4E54) to Color(0xFF3E7C83),
    Color(0xFF7A3E2B) to Color(0xFFB5651D),
    Color(0xFF3B3A66) to Color(0xFF6B5B95),
    Color(0xFF2F5233) to Color(0xFF5E8C61),
    Color(0xFF5C2A3E) to Color(0xFF9C4A68),
    Color(0xFF33414E) to Color(0xFF5F7A91),
)

/** Cover art: the downloaded cover when there is one, otherwise a calm gradient with the title's initial. */
@Composable
fun BookCover(title: String, url: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, url) { value = if (url.isBlank()) null else CoverCache.load(ctx, url) }
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.shadow(3.dp, shape).clip(shape)) {
        val img = bitmap
        if (img != null) {
            Image(img.asImageBitmap(), contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val (a, b) = palette[(title.hashCode() and 0x7fffffff) % palette.size]
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(a, b))), contentAlignment = Alignment.Center) {
                Text(
                    title.trim().take(1).uppercase().ifEmpty { "?" },
                    color = Color.White.copy(alpha = 0.9f),
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp,
                )
                Text(
                    title,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 6.dp, vertical = 8.dp),
                )
            }
        }
        // A thin spine highlight makes the flat image read as a book
        Box(Modifier.align(Alignment.CenterStart).padding(start = 3.dp).width(1.5.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.18f)))
    }
}

/** Small on-disk cache so covers appear instantly after the first load and work offline. */
object CoverCache {
    private val memory = object : android.util.LruCache<String, Bitmap>(24) {}

    private fun file(ctx: Context, url: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(ctx.cacheDir, "covers").apply { mkdirs() }, "$hash.img")
    }

    suspend fun load(ctx: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
        memory.get(url)?.let { return@withContext it }
        val f = file(ctx, url)
        if (!f.exists() || f.length() == 0L) {
            runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.instanceFollowRedirects = true
                if (conn.responseCode != 200) { conn.disconnect(); return@runCatching }
                val tmp = File(f.path + ".part")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                conn.disconnect()
                tmp.renameTo(f)
            }
        }
        val bmp = f.takeIf { it.exists() && it.length() > 0 }?.let { BitmapFactory.decodeFile(it.path) }
        // Open Library answers a missing cover with a 1x1 placeholder; treat that as no cover
        bmp?.takeIf { it.width > 8 && it.height > 8 }?.also { memory.put(url, it) }
    }
}
