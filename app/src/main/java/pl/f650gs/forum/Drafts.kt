package pl.f650gs.forum

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import org.json.JSONArray
import org.json.JSONObject

/*
 * Composer drafts on disk, so they survive the app being closed. Photos and films are kept as the
 * HTML they will be posted as, mentions as member id and name; both become spans again on loading.
 */

private const val DRAFTS_KEY = "composer_drafts"

/** The picture to show for media [html] in the text box: the image itself or the film's thumbnail; null for a ▶ tile. */
internal fun draftPreview(html: String): String? {
    Regex("""youtube\.com/embed/([A-Za-z0-9_-]{11})""").find(html)?.let { return "https://img.youtube.com/vi/${it.groupValues[1]}/hqdefault.jpg" }
    val src = Regex("""<img[^>]*\ssrc="([^"]+)"""").find(html)?.groupValues?.get(1)?.replace("&amp;", "&") ?: return null
    // Google Photos addresses take their size after "=": a small one is enough for the thumbnail.
    return if (src.contains("googleusercontent.com/")) src.substringBefore('=') + "=w300-h300" else src
}

private fun encodeDraft(text: Spanned): JSONObject {
    val media = JSONArray()
    text.getSpans(0, text.length, PostMediaSpan::class.java).forEach { span ->
        media.put(JSONObject().put("at", text.getSpanStart(span)).put("html", span.html))
    }
    val mentions = JSONArray()
    text.getSpans(0, text.length, MentionSpan::class.java).forEach { span ->
        mentions.put(JSONObject().put("start", text.getSpanStart(span)).put("end", text.getSpanEnd(span)).put("id", span.id).put("name", span.name))
    }
    return JSONObject().put("text", text.toString()).put("media", media).put("mentions", mentions)
}

private fun MainActivity.decodeDraft(json: JSONObject): CharSequence {
    val text = SpannableStringBuilder(json.optString("text"))
    val media = json.optJSONArray("media") ?: JSONArray()
    val placed = mutableSetOf<Int>()
    for (i in 0 until media.length()) {
        val item = media.getJSONObject(i)
        val at = item.optInt("at", -1)
        if (at !in text.indices || text[at] != MEDIA_CHAR) continue
        val html = item.optString("html")
        lateinit var span: PostMediaSpan
        span = mediaSpan(html, draftPreview(html)) { loaded -> replaceSpan(chatInput, span, loaded) }
        text.setSpan(span, at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        placed += at
    }
    val mentions = json.optJSONArray("mentions") ?: JSONArray()
    for (i in 0 until mentions.length()) {
        val item = mentions.getJSONObject(i)
        val start = item.optInt("start", -1)
        val end = item.optInt("end", -1)
        if (start < 0 || end > text.length || start >= end) continue
        text.setSpan(MentionSpan(LINK, item.optString("id"), item.optString("name")), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    // Photos that were still uploading when the draft was saved are gone; drop their places.
    for (at in text.indices.reversed()) if (text[at] == MEDIA_CHAR && at !in placed) text.delete(at, at + 1)
    return text
}

/** The saved draft for [key], or null. */
internal fun MainActivity.storedDraft(key: String): CharSequence? = runCatching {
    val all = JSONObject(getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getString(DRAFTS_KEY, null) ?: return null)
    all.optJSONObject(key)?.let { decodeDraft(it) }
}.getOrNull()

/** Saves [draft] for [key], or removes it when null. */
internal fun MainActivity.persistDraft(key: String, draft: CharSequence?) {
    val prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
    val all = runCatching { JSONObject(prefs.getString(DRAFTS_KEY, null) ?: "{}") }.getOrDefault(JSONObject())
    if (draft is Spanned && draft.isNotBlank()) all.put(key, encodeDraft(draft)) else all.remove(key)
    prefs.edit().putString(DRAFTS_KEY, all.toString()).apply()
}
