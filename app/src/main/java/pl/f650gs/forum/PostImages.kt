package pl.f650gs.forum

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/*
 * The forum's activity stream drops images from post snippets without a trace, so a card cannot
 * tell a photo post from a text one. The app reads the post once from its topic page, remembers
 * how many images it has, and shows a generic picture tile on the card.
 */

// One look-up at a time, apart from the pool that loads screens.
private val imageChecks = Executors.newSingleThreadExecutor()

// Cards waiting for a look-up, by post key; touched on the main thread only.
private val waiting = mutableMapOf<String, MutableList<WeakReference<LinearLayout>>>()

private const val IMAGE_PREFS = "post_images"
private const val MAX_REMEMBERED = 2000

/** The comment id in a card's address, or the address itself for a topic's first post. */
private fun postKey(url: String) = Regex("""comment=(\d+)""").find(url)?.groupValues?.get(1) ?: url

/**
 * Shows the image marker in [holder] for the post at [url]: at once when already known, otherwise
 * after reading the post in the background, when [lookUp] allows it.
 */
internal fun MainActivity.markPostImages(holder: LinearLayout, url: String, lookUp: Boolean) {
    val prefs = getSharedPreferences(IMAGE_PREFS, Context.MODE_PRIVATE)
    val key = postKey(url)
    if (prefs.contains(key)) return showImageMarker(holder, prefs.getInt(key, 0))
    if (!lookUp) return
    val cards = waiting[key]
    if (cards != null) { cards += WeakReference(holder); return }
    waiting[key] = mutableListOf(WeakReference(holder))
    imageChecks.execute {
        val count = runCatching {
            val page = repository.topic(url)
            val post = page.posts.firstOrNull { it.id == page.focusPostId } ?: page.posts.firstOrNull()
            post?.let { ForumParser.imageCount(it.html) }
        }.getOrNull()
        if (count != null) {
            if (prefs.all.size > MAX_REMEMBERED) prefs.edit().clear().apply()
            prefs.edit().putInt(key, count).apply()
        }
        main.post {
            val holders = waiting.remove(key).orEmpty().mapNotNull { it.get() }
            if (count != null) holders.filter { it.isAttachedToWindow }.forEach { showImageMarker(it, count) }
        }
    }
}

/** A generic picture tile with the number of images, so it is clear the post has some. */
private fun MainActivity.showImageMarker(holder: LinearLayout, count: Int) {
    if (count <= 0 || holder.childCount > 0) return
    holder.addView(label("🖼️", 20f, LINK).apply {
        gravity = Gravity.CENTER
        background = rounded(TEAL_LIGHT, 10)
    }, LinearLayout.LayoutParams(dp(44), dp(44)))
    holder.addView(label(imagesLabel(count), 13f, MUTED, bold = true), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
    holder.visibility = android.view.View.VISIBLE
}

internal fun imagesLabel(count: Int) = when {
    count == 1 -> "1 zdjęcie"
    count % 10 in 2..4 && count % 100 !in 12..14 -> "$count zdjęcia"
    else -> "$count zdjęć"
}
