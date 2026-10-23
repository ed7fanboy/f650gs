package pl.f650gs.forum

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.Editable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.widget.EditText
import android.widget.Toast
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import java.util.concurrent.Executors

/*
 * Photos in posts. The forum's server has no room for attachments, so — as members already do by
 * hand — photos live in the member's own Google Photos: the app copies each picked photo into its
 * "f650gs.pl zdjęcia" album, the member shares that album by link once, and the photo's public
 * address from the shared album page goes into the post as an image.
 *
 * In the text box a photo is a thumbnail standing in for one character, so it sits exactly where
 * it will appear in the post and moves and deletes like a letter.
 */

/** A photo (or other kept media) in the text being written; [html] is what goes into the post. */
internal class PostMediaSpan(drawable: Drawable, val html: String) : ImageSpan(drawable, ALIGN_BOTTOM)

/** A photo still on its way to Google Photos; becomes a [PostMediaSpan] once its address is known. */
internal class PendingPhotoSpan(drawable: Drawable) : ImageSpan(drawable, ALIGN_BOTTOM) {
    /** Base64url SHA-1 of the file once uploaded; the album page lists photos under it. */
    @Volatile var fingerprint: String? = null
}

/** The character a media span stands on. */
internal const val MEDIA_CHAR = '￼'

// One job at a time: uploads finish before the album page is read for their addresses.
private val photoIo = Executors.newSingleThreadExecutor()

private const val PHOTO_PICK_REQUEST = 4104
private const val PHOTO_AUTH_REQUEST = 4105
private const val ALBUM_TITLE = "f650gs.pl zdjęcia"
private const val KEY_ALBUM = "photos_album_id"
private const val KEY_ALBUM_URL = "photos_album_url"
private const val KEY_SHARE = "photos_share_url"
private const val THUMB_DP = 64

/** The text box's content as post HTML: paragraphs of text with the media in their places. */
internal fun postHtml(text: CharSequence): String {
    val spanned = text as? Spanned ?: return paragraphsHtml(text.toString())
    val html = StringBuilder()
    var from = 0
    spanned.getSpans(0, spanned.length, PostMediaSpan::class.java).sortedBy(spanned::getSpanStart).forEach { span ->
        val start = spanned.getSpanStart(span)
        html.append(paragraphsHtml(spanned.substring(from, start).replace(MEDIA_CHAR.toString(), ""))).append(span.html)
        from = spanned.getSpanEnd(span)
    }
    return html.append(paragraphsHtml(spanned.substring(from).replace(MEDIA_CHAR.toString(), ""))).toString()
}

/** A photo as the forum's editor writes it. */
internal fun photoHtml(url: String): String = "<p><img src=\"${android.text.Html.escapeHtml(url)}\" alt=\"\"></p>"

/** False (with a note) while a photo in [edit] is still on its way, so a post never goes out without it. */
internal fun MainActivity.photosReady(edit: EditText): Boolean {
    if (edit.text.getSpans(0, edit.text.length, PendingPhotoSpan::class.java).isEmpty()) return true
    toast("Poczekaj, zdjęcie jeszcze się wysyła.")
    return false
}

/** Media in the text box, shown as a thumbnail of [previewUrl] once loaded, or as a ▶ tile without one. */
internal fun MainActivity.mediaSpan(html: String, previewUrl: String?, onLoaded: (PostMediaSpan) -> Unit): PostMediaSpan {
    val span = PostMediaSpan(tileDrawable(if (previewUrl == null) "▶" else ""), html)
    if (previewUrl != null) Avatars.loadBitmap(previewUrl) { bitmap -> bitmap?.let { onLoaded(PostMediaSpan(thumbDrawable(it), html)) } }
    return span
}

/** Swaps [old] for [new] at the same place in [edit], if [old] is still there. */
internal fun replaceSpan(edit: EditText, old: Any, new: Any) {
    val text = edit.text
    val start = text.getSpanStart(old)
    if (start < 0) return
    val end = text.getSpanEnd(old)
    text.removeSpan(old)
    text.setSpan(new, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

/** Puts [span] at the cursor on a line of its own. */
private fun insertMedia(edit: EditText, span: Any) {
    val text = edit.text
    val at = edit.selectionStart.coerceIn(0, text.length)
    val before = if (at > 0 && text[at - 1] != '\n') "\n" else ""
    val after = if (at < text.length && text[at] == '\n') "" else "\n"
    val media = SpannableString(MEDIA_CHAR.toString()).apply { setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    text.insert(at, SpannableStringBuilder(before).append(media).append(after))
    edit.setSelection((at + before.length + 1 + after.length).coerceAtMost(edit.text.length))
}

private fun removeMedia(text: Editable, span: Any) {
    val start = text.getSpanStart(span)
    if (start < 0) return
    text.removeSpan(span)
    text.delete(start, start + 1)
}

private fun MainActivity.tileDrawable(label: String): Drawable {
    val size = dp(THUMB_DP)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEAL_LIGHT }
    canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), dp(10).toFloat(), dp(10).toFloat(), paint)
    if (label.isNotEmpty()) {
        paint.color = LINK
        paint.textSize = dp(22).toFloat()
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2, paint)
    }
    return BitmapDrawable(resources, bitmap).apply { setBounds(0, 0, size, size) }
}

/** [source] cropped to a rounded square thumbnail. */
private fun MainActivity.thumbDrawable(source: Bitmap): Drawable {
    val size = dp(THUMB_DP)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.clipPath(Path().apply {
        addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), dp(10).toFloat(), dp(10).toFloat(), Path.Direction.CW)
    })
    val edge = minOf(source.width, source.height)
    val crop = Rect((source.width - edge) / 2, (source.height - edge) / 2, (source.width + edge) / 2, (source.height + edge) / 2)
    canvas.drawBitmap(source, crop, Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG))
    return BitmapDrawable(resources, bitmap).apply { setBounds(0, 0, size, size) }
}

/** Starts the photo flow for [target]: the system photo picker first, several photos at once. */
internal fun MainActivity.pickPhoto(target: EditText) {
    photoTarget = target
    val intent = if (android.os.Build.VERSION.SDK_INT >= 33) {
        Intent(MediaStore.ACTION_PICK_IMAGES).putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 10)
    } else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    @Suppress("DEPRECATION")
    runCatching { startActivityForResult(intent, PHOTO_PICK_REQUEST) }
        .onFailure { toast("Nie udało się otworzyć galerii.") }
}

/** True when [requestCode] belonged to the photo flow. */
internal fun MainActivity.handlePhotoResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
    if (requestCode != PHOTO_PICK_REQUEST && requestCode != PHOTO_AUTH_REQUEST) return false
    if (resultCode != android.app.Activity.RESULT_OK) { pickedPhotos = emptyList(); return true }
    when (requestCode) {
        PHOTO_PICK_REQUEST -> {
            // Several photos come as clip data, a single one as the plain data URI.
            val clip = data?.clipData
            pickedPhotos = if (clip != null) List(clip.itemCount) { clip.getItemAt(it).uri } else listOfNotNull(data?.data)
            if (pickedPhotos.isNotEmpty()) authorizePhotos()
        }
        PHOTO_AUTH_REQUEST -> runCatching { Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data) }
            .onSuccess { result -> result.accessToken?.let(::uploadPhotos) ?: toast("Google nie wydał dostępu do Zdjęć.") }
            .onFailure { toast("Logowanie do Google: ${it.localizedMessage}") }
    }
    return true
}

/**
 * A Google token for the app's own album; the first time Google asks for the account and consent.
 * One permission only: adding new photos and creating the app's album (it cannot see other photos).
 */
private fun MainActivity.authorizePhotos() {
    val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(GooglePhotosApi.SCOPE_APPEND)))
        .build()
    Identity.getAuthorizationClient(this).authorize(request)
        .addOnSuccessListener { result ->
            val pending = result.pendingIntent
            if (result.hasResolution() && pending != null) {
                @Suppress("DEPRECATION")
                startIntentSenderForResult(pending.intentSender, PHOTO_AUTH_REQUEST, null, 0, 0, 0, null)
            } else result.accessToken?.let(::uploadPhotos) ?: toast("Google nie wydał dostępu do Zdjęć.")
        }
        .addOnFailureListener { toast("Logowanie do Google: ${it.localizedMessage}") }
}

private fun MainActivity.uploadPhotos(token: String) {
    val photos = pickedPhotos
    val target = photoTarget ?: return
    pickedPhotos = emptyList()
    val prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
    photos.forEach { photo ->
        val span = PendingPhotoSpan(tileDrawable("⏳"))
        insertMedia(target, span)
        photoIo.execute {
            val result = runCatching {
                val api = GooglePhotosApi(token)
                val albumId = prefs.getString(KEY_ALBUM, null) ?: api.createAlbum(ALBUM_TITLE).also {
                    prefs.edit().putString(KEY_ALBUM, it.id).putString(KEY_ALBUM_URL, it.productUrl).apply()
                }.id
                val name = contentResolver.query(photo, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "f650gs-${System.currentTimeMillis()}.jpg"
                val bytes = contentResolver.openInputStream(photo)!!.use { it.readBytes() }
                api.upload(albumId, name, contentResolver.getType(photo) ?: "image/jpeg", bytes)
                fingerprint(bytes)
            }
            result.onSuccess { span.fingerprint = it }
            result.onFailure {
                main.post {
                    removeMedia(target.text, span)
                    toast("Nie udało się wysłać zdjęcia: ${it.localizedMessage}")
                }
            }
        }
    }
    // Queued after the uploads, so it runs once they are all done.
    photoIo.execute {
        main.post { if (prefs.getString(KEY_SHARE, null) == null) showShareAlbumDialog() else findPhotoAddresses(target) }
    }
}

/** Base64url SHA-1 of a file, the fingerprint Google Photos shows for it on the album page. */
private fun fingerprint(bytes: ByteArray): String = android.util.Base64.encodeToString(
    java.security.MessageDigest.getInstance("SHA-1").digest(bytes),
    android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
)

/** Reads the shared album page until every uploaded photo in [target] is on it, found by its fingerprint. */
private fun MainActivity.findPhotoAddresses(target: EditText) {
    val share = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getString(KEY_SHARE, null) ?: return
    val waiting = target.text.getSpans(0, target.text.length, PendingPhotoSpan::class.java).filter { it.fingerprint != null }
    if (waiting.isEmpty()) return
    val wanted = waiting.mapNotNull { it.fingerprint }.toSet()
    photoIo.execute {
        var found = emptyMap<String, String>()
        var problem: Throwable? = null
        // Google needs a few seconds before new photos appear on the public page.
        for (attempt in 0 until 10) {
            runCatching { SharedAlbumPage.photosByFingerprint(SharedAlbumPage.fetch(share)) }
                .onSuccess { page -> found = page.filterKeys { it in wanted }; problem = null }
                .onFailure { problem = it }
            if (found.size == wanted.size) break
            Thread.sleep(3_000)
        }
        main.post {
            waiting.forEach { span ->
                val url = found[span.fingerprint]?.let(SharedAlbumPage::forumUrl)
                if (url == null) { removeMedia(target.text, span); return@forEach }
                val html = photoHtml(url)
                lateinit var placed: PostMediaSpan
                placed = mediaSpan(html, url.substringBefore('=') + "=w300-h300") { loaded -> replaceSpan(target, placed, loaded) }
                replaceSpan(target, span, placed)
            }
            if (found.size < wanted.size) toast(problem?.let { "Strona albumu: ${it.localizedMessage}" }
                ?: "Nie wszystkie zdjęcia są już na stronie albumu (${found.size} z ${wanted.size}). Dodaj brakujące ponownie za chwilę.")
        }
    }
}

/**
 * The one step Google no longer lets apps do: sharing the album by link. The member does it in
 * Google Photos; when they come back, the copied link is picked up from the clipboard.
 */
private fun MainActivity.showShareAlbumDialog() {
    val albumUrl = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getString(KEY_ALBUM_URL, null) ?: return
    AlertDialog.Builder(this)
        .setTitle("Jeszcze jeden krok, tylko raz")
        .setMessage(
            "Zdjęcia są już w albumie „$ALBUM_TITLE” w Twoich Zdjęciach Google. Żeby inni widzieli je na forum, " +
                "album musi mieć link:\n\n1. Otwórz album.\n2. Stuknij Udostępnij → Utwórz link → Kopiuj link.\n3. Wróć tutaj — " +
                "aplikacja sama weźmie link ze schowka.\n\nKolejne zdjęcia pójdą już bez tego kroku."
        )
        .setPositiveButton("Otwórz album") { _, _ ->
            awaitingShareLink = true
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(albumUrl)))
        }
        .setNegativeButton("Anuluj") { _, _ ->
            photoTarget?.let { edit -> edit.text.getSpans(0, edit.text.length, PendingPhotoSpan::class.java).forEach { removeMedia(edit.text, it) } }
        }
        .setCancelable(false)
        .show()
}

/** Called when the app is back in focus after the member went to share the album. */
internal fun MainActivity.takeAlbumLinkFromClipboard() {
    if (!awaitingShareLink) return
    val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
    if (!text.startsWith("https://photos.app.goo.gl/") && !text.startsWith("https://photos.google.com/share/")) {
        toast("W schowku nie ma linku do albumu. Skopiuj go w Zdjęciach Google i wróć.")
        return
    }
    awaitingShareLink = false
    getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SHARE, text).apply()
    toast("Album udostępniony. Szukam zdjęć…")
    photoTarget?.let(::findPhotoAddresses)
}

internal fun MainActivity.toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
