package pl.f650gs.forum

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Downloads forum member photos once per app run and keeps small copies in memory. */
object Avatars {
    private const val EDGE = 128
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    /** Where downloaded photos are kept between app runs (set from the app's cache folder); null keeps them in memory only. */
    @Volatile var diskDir: java.io.File? = null

    /** Shows the photo in [target] when it arrives; leaves the letter badge underneath otherwise. */
    fun load(url: String, target: ImageView) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        target.tag = url
        cache.get(url)?.let { target.setImageBitmap(it); return }
        if (url in failed) return
        pool.execute {
            val bitmap = runCatching { download(url) }.getOrNull()
            if (bitmap == null) failed.add(url) else cache.put(url, bitmap)
            main.post { if (target.tag == url && bitmap != null) target.setImageBitmap(bitmap) }
        }
    }

    /**
     * Hands the (downsized) image to [done] on the main thread; null when it can't be loaded.
     * Always later than the call, even for a cached image: callers refer to what they get back
     * (a span) from inside [done].
     */
    fun loadBitmap(url: String, done: (Bitmap?) -> Unit) {
        cache.get(url)?.let { cached -> main.post { done(cached) }; return }
        pool.execute {
            val bitmap = runCatching { download(url) }.getOrNull()
            if (bitmap != null) cache.put(url, bitmap)
            main.post { done(bitmap) }
        }
    }

    private fun download(url: String): Bitmap? {
        val file = diskDir?.let { java.io.File(it, sha1(url)) }
        val bytes = file?.takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }
            ?: fetch(url)?.also { fresh -> file?.let { runCatching { it.parentFile?.mkdirs(); it.writeBytes(fresh) } } }
            ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= EDGE && bounds.outHeight / (sample * 2) >= EDGE) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun sha1(text: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun fetch(url: String): ByteArray? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "F650GSForum/0.8 (Android; native reader)")
        }
        try {
            if (connection.responseCode != 200) return null
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
