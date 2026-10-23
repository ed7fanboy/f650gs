package pl.f650gs.forum

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

/**
 * Full-window photo view: pinch or double-tap to zoom, drag with one finger to pan. Given the
 * post's photos ([EXTRA_URLS]), a sideways flick while not zoomed shows the next or previous one.
 * The download button saves the original file to Pictures/f650gs.pl.
 */
class ImagePreviewActivity : Activity() {
    companion object {
        const val EXTRA_URL = "image_url"
        const val EXTRA_DARK_MODE = "dark_mode"
        const val EXTRA_COOKIES = "cookies"
        const val EXTRA_URLS = "image_urls"
        const val EXTRA_INDEX = "image_index"
        private val TEAL = Color.rgb(0, 102, 177)
        private val LIME = Color.rgb(196, 216, 46)
        private const val MAX_DECODE_EDGE = 4096
        private const val SAVE_PERMISSION_REQUEST = 4201
    }

    private var darkMode = false
    @Volatile private var closed = false
    private val main = Handler(Looper.getMainLooper())
    private var urls: List<String> = emptyList()
    private var current = 0
    // The shown photo and its neighbours; others are dropped, as full photos are large.
    private val bitmaps = mutableMapOf<Int, Bitmap?>()
    private val loading = mutableSetOf<Int>()
    private var cookies = ""
    private lateinit var image: ZoomImageView
    private lateinit var title: TextView
    private lateinit var progress: ProgressBar
    private lateinit var failed: TextView

    private fun isWebImage(url: String) = runCatching { android.net.Uri.parse(url) }.getOrNull()
        ?.let { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() } == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkMode = intent.getBooleanExtra(EXTRA_DARK_MODE, false)
        val imageUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        urls = (intent.getStringArrayListExtra(EXTRA_URLS) ?: arrayListOf(imageUrl)).filter(::isWebImage)
        if (urls.isEmpty()) {
            finish()
            return
        }
        current = intent.getIntExtra(EXTRA_INDEX, urls.indexOf(imageUrl)).coerceIn(0, urls.lastIndex)

        val background = if (darkMode) Color.rgb(17, 25, 28) else Color.rgb(241, 245, 248)
        val foreground = if (darkMode) Color.rgb(232, 239, 237) else Color.rgb(27, 43, 48)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(16), dp(4))
            setBackgroundColor(if (darkMode) Color.rgb(15, 39, 43) else TEAL)
        }
        toolbar.addView(TextView(this).apply {
            text = "‹"
            textSize = 32f
            setTextColor(LIME)
            gravity = Gravity.CENTER
            contentDescription = "Wróć do tematu"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(42), dp(44)))
        title = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
        }
        toolbar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_download)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Pobierz zdjęcie"
            setOnClickListener { saveCurrent() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(toolbar)

        val stage = FrameLayout(this)
        image = ZoomImageView(this).apply { onSwipe = { step -> show(current + step, step) } }
        stage.addView(image, FrameLayout.LayoutParams(-1, -1))
        progress = ProgressBar(this).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(LIME)
        }
        stage.addView(progress, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        failed = TextView(this).apply {
            text = "Nie udało się pobrać zdjęcia.\nLink może być nieaktualny."
            textSize = 16f
            setTextColor(foreground)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        stage.addView(failed, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.requestApplyInsets()

        cookies = intent.getStringExtra(EXTRA_COOKIES).orEmpty()
        show(current, 0)
    }

    /** Shows photo [index], sliding in from the side of [direction] (0: no slide). */
    private fun show(index: Int, direction: Int) {
        if (index !in urls.indices) return
        current = index
        title.text = if (urls.size > 1) "Zdjęcie ${index + 1} z ${urls.size}" else "Zdjęcie"
        bitmaps.keys.filter { it !in index - 1..index + 1 }.forEach { bitmaps.remove(it) }
        val swap = {
            failed.visibility = View.GONE
            val bitmap = bitmaps[index]
            if (bitmaps.containsKey(index)) {
                progress.visibility = View.GONE
                image.setImageBitmap(bitmap)
                if (bitmap == null) failed.visibility = View.VISIBLE
            } else {
                image.setImageDrawable(null)
                progress.visibility = View.VISIBLE
            }
        }
        if (direction == 0) swap() else {
            val width = image.width.toFloat().coerceAtLeast(1f)
            image.animate().translationX(-direction * width).setDuration(140).withEndAction {
                swap()
                image.translationX = direction * width
                image.animate().translationX(0f).setDuration(160).start()
            }.start()
        }
        (listOf(index) + listOf(index + 1, index - 1)).filter { it in urls.indices }.forEach(::load)
    }

    private fun load(index: Int) {
        if (bitmaps.containsKey(index) || !loading.add(index)) return
        val url = urls[index]
        val forumHost = android.net.Uri.parse(url).host.orEmpty().let { it == "f650gs.pl" || it.endsWith(".f650gs.pl") }
        Thread {
            val bitmap = runCatching { download(url, if (forumHost) cookies else "") }.getOrNull()
            main.post {
                loading.remove(index)
                if (closed || index !in current - 1..current + 1) return@post
                bitmaps[index] = bitmap
                if (index == current) {
                    progress.visibility = View.GONE
                    if (bitmap == null) failed.visibility = View.VISIBLE else image.setImageBitmap(bitmap)
                }
            }
        }.start()
    }

    /** Saves the shown photo's original file through the system's download manager. */
    private fun saveCurrent() {
        if (android.os.Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), SAVE_PERMISSION_REQUEST)
            return
        }
        val uri = android.net.Uri.parse(urls.getOrNull(current) ?: return)
        val name = photoFileName(uri)
        val request = android.app.DownloadManager.Request(uri)
            .setTitle(name)
            .setDescription("f650gs.pl")
            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_PICTURES, "f650gs.pl/$name")
            .addRequestHeader("User-Agent", "F650GSForum/0.7 (Android; native reader)")
        val host = uri.host.orEmpty()
        // Photos on the forum itself may be visible to members only.
        if ((host == "f650gs.pl" || host.endsWith(".f650gs.pl")) && cookies.isNotBlank()) request.addRequestHeader("Cookie", cookies)
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT < 29) request.allowScanningByMediaScanner()
        val saved = runCatching { getSystemService(android.app.DownloadManager::class.java).enqueue(request) }.isSuccess
        android.widget.Toast.makeText(this, if (saved) "Zapisuję w Obrazy/f650gs.pl" else "Nie udało się zapisać zdjęcia.", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** The file's own name when it has a picture extension, else a dated one (Google Photos addresses have none). */
    private fun photoFileName(uri: android.net.Uri): String {
        val last = uri.lastPathSegment.orEmpty().substringAfterLast('/')
        if (Regex("""(?i).+\.(jpe?g|png|gif|webp|heic)$""").matches(last)) return "f650gs-" + last.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())
        return "f650gs-$stamp-${current + 1}.jpg"
    }

    @Deprecated("Deprecated in Android")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != SAVE_PERMISSION_REQUEST) return
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) saveCurrent()
        else android.widget.Toast.makeText(this, "Bez zgody na pamięć nie da się zapisać zdjęcia.", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun download(url: String, cookies: String): Bitmap? {
        var location = url
        repeat(5) {
            val connection = (URL(location).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "F650GSForum/0.7 (Android; native reader)")
                if (cookies.isNotBlank()) setRequestProperty("Cookie", cookies)
            }
            try {
                // Follow redirects manually so an http → https hop on image hosts works.
                if (connection.responseCode in 300..399) {
                    location = URL(URL(location), connection.getHeaderField("Location") ?: return null).toString()
                    return@repeat
                }
                if (connection.responseCode != 200) return null
                val bytes = connection.inputStream.use { it.readBytes() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > MAX_DECODE_EDGE || bounds.outHeight / sample > MAX_DECODE_EDGE) sample *= 2
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        closed = true
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android; retained for API 24 back navigation")
    override fun onBackPressed() = finish()
}

/** ImageView with fit-to-screen base scale, pinch and double-tap zoom, and one-finger panning. */
private class ZoomImageView(context: Context) : ImageView(context) {
    private val zoom = Matrix()
    private var baseScale = 1f
    private var scale = 1f
    private val maxScale = 6f
    /** Called with +1 (next) or -1 (previous) on a sideways flick while not zoomed in. */
    var onSwipe: ((Int) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val target = (scale * detector.scaleFactor).coerceIn(1f, maxScale)
            val factor = target / scale
            scale = target
            zoom.postScale(factor, factor, detector.focusX, detector.focusY)
            constrain()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            zoom.postTranslate(-distanceX, -distanceY)
            constrain()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (scale > 1.05f || kotlin.math.abs(velocityX) < 800f || kotlin.math.abs(velocityX) < kotlin.math.abs(velocityY) * 1.5f) return false
            onSwipe?.invoke(if (velocityX < 0) 1 else -1)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (scale > 1.05f) 1f else 2.5f
            val factor = target / scale
            scale = target
            zoom.postScale(factor, factor, e.x, e.y)
            constrain()
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        post { resetToFit() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetToFit()
    }

    private fun resetToFit() {
        val drawable = drawable ?: return
        if (width == 0 || height == 0) return
        val dw = drawable.intrinsicWidth.toFloat()
        val dh = drawable.intrinsicHeight.toFloat()
        baseScale = minOf(width / dw, height / dh)
        scale = 1f
        zoom.reset()
        zoom.postScale(baseScale, baseScale)
        zoom.postTranslate((width - dw * baseScale) / 2f, (height - dh * baseScale) / 2f)
        setImageMatrix(zoom)
    }

    /** Keeps the photo centred when smaller than the view and its edges inside the view when larger. */
    private fun constrain() {
        val drawable = drawable ?: return
        val bounds = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        zoom.mapRect(bounds)
        val dx = when {
            bounds.width() <= width -> (width - bounds.width()) / 2f - bounds.left
            bounds.left > 0 -> -bounds.left
            bounds.right < width -> width - bounds.right
            else -> 0f
        }
        val dy = when {
            bounds.height() <= height -> (height - bounds.height()) / 2f - bounds.top
            bounds.top > 0 -> -bounds.top
            bounds.bottom < height -> height - bounds.bottom
            else -> 0f
        }
        zoom.postTranslate(dx, dy)
        setImageMatrix(zoom)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }
}
