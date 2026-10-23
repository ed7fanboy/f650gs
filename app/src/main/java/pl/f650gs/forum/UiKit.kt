package pl.f650gs.forum

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import pl.f650gs.forum.MainActivity.Companion.EXTRA_SELECT_TAB
import pl.f650gs.forum.MainActivity.Companion.SIGNATURE
import pl.f650gs.forum.MainActivity.Companion.HIDE_SIGNATURES_KEY
import pl.f650gs.forum.MainActivity.Companion.NAV_HIDDEN_KEY
import pl.f650gs.forum.MainActivity.Companion.NAV_ORDER_KEY
import pl.f650gs.forum.MainActivity.Companion.NOTIFICATION_PERMISSION_REQUEST
import pl.f650gs.forum.MainActivity.Companion.PREFS
import pl.f650gs.forum.MainActivity.Companion.SAVED_KEY
import pl.f650gs.forum.MainActivity.Companion.SIGNED_IN_KEY
import pl.f650gs.forum.MainActivity.Companion.STATE_CURRENT_SCREEN
import pl.f650gs.forum.MainActivity.Companion.STATE_SCREEN_HISTORY
import pl.f650gs.forum.MainActivity.Companion.STATE_SELECTED_TAB
import pl.f650gs.forum.MainActivity.Companion.THEME_KEY
import pl.f650gs.forum.MainActivity.Companion.WEB_ACTION_REQUEST

/** Forum post HTML as a native TextView: quotes and links kept, photos become tappable links. */
internal fun MainActivity.htmlText(html: String, color: Int): TextView {
    val document = org.jsoup.Jsoup.parseBodyFragment(stripSignatureHtml(html), "http://f650gs.pl/")
    document.select("img").forEach { image ->
        val source = image.absUrl("src")
        if (image.hasAttr("data-emoticon") || source.contains("/emoticons/")) {
            image.replaceWith(org.jsoup.nodes.TextNode(chatEmoticons[image.attr("alt")] ?: image.attr("alt")))
        } else {
            image.replaceWith(org.jsoup.nodes.Element("a").attr("href", "image:$source").text("🖼 Zdjęcie"))
        }
    }
    document.select(".ipsQuote_citation").remove()
    document.select("blockquote").forEach { quote ->
        quote.attr("data-ipsquote-username").takeIf { it.isNotBlank() }?.let { quote.prependElement("p").text("$it napisał(a):") }
    }
    @Suppress("DEPRECATION")
    val spanned = android.text.SpannableStringBuilder(android.text.Html.fromHtml(document.body().html()))
    // Forum editors leave empty paragraphs; keep at most one blank line between blocks.
    Regex("""\n[\s\u00A0]*\n(?:[\s\u00A0]*\n)+""").findAll(spanned.toString()).toList().asReversed().forEach { gap ->
        spanned.replace(gap.range.first, gap.range.last + 1, "\n\n")
    }
    while (spanned.isNotEmpty() && spanned.last().isWhitespace()) spanned.delete(spanned.length - 1, spanned.length)
    spanned.getSpans(0, spanned.length, android.text.style.URLSpan::class.java).forEach { span ->
        val start = spanned.getSpanStart(span)
        val end = spanned.getSpanEnd(span)
        val url = span.url
        spanned.removeSpan(span)
        spanned.setSpan(object : android.text.style.ClickableSpan() {
            override fun onClick(widget: View) {
                if (url.startsWith("image:")) {
                    startActivity(Intent(this@htmlText, ImagePreviewActivity::class.java)
                        .putExtra(ImagePreviewActivity.EXTRA_URL, url.removePrefix("image:"))
                        .putExtra(ImagePreviewActivity.EXTRA_DARK_MODE, darkMode)
                        .putExtra(ImagePreviewActivity.EXTRA_COOKIES, CookieManager.getInstance().getCookie("http://f650gs.pl/")))
                } else routePostLink(Uri.parse(url))
            }
        }, start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return label("", 15f, color).apply {
        text = spanned
        setLinkTextColor(if (color == WHITE) Color.rgb(209, 234, 255) else LINK)
        movementMethod = android.text.method.LinkMovementMethod.getInstance()
    }
}

internal fun MainActivity.hero(title: String, subtitle: String): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(18), dp(18), dp(18), dp(18))
    background = rounded(TEAL, 20)
    addView(label(title, 21f, WHITE, bold = true))
    addView(label(subtitle, 13f, Color.rgb(209, 224, 219)), margin(top = 6))
    layoutParams = margin(bottom = 22)
}

internal fun MainActivity.sectionHeading(title: String, trailing: String = ""): View = row {
    addView(label(title, 17f, INK, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
    if (trailing.isNotBlank()) addView(label(trailing, 11f, MUTED, bold = true))
}.apply { layoutParams = margin(top = 2, bottom = 10) }

internal fun MainActivity.sectionLabel(text: String): View = label(text, 13f, MUTED, bold = true).apply {
    gravity = Gravity.CENTER
    layoutParams = margin(top = 20, bottom = 10)
}

internal fun MainActivity.emptyCard(title: String, body: String): View = baseCard().apply {
    addView(label(title, 16f, INK, bold = true))
    addView(label(body, 13f, MUTED), margin(top = 6))
}

internal fun MainActivity.openSiteButton(title: String, click: () -> Unit): View = pill(title, ACCENT_FILL, WHITE, click).apply {
    layoutParams = margin(top = 12, bottom = 8)
}

internal fun MainActivity.baseCard(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(15), dp(14), dp(15), dp(14))
    background = rounded(CARD, 17)
    elevation = dp(1).toFloat()
    layoutParams = margin(bottom = 10)
}

internal fun MainActivity.avatarColors(name: String): Pair<Int, Int> =
    AVATAR_COLORS[Math.floorMod(name.trim().lowercase().hashCode(), AVATAR_COLORS.size)]

/** Coloured initial, covered by the member's forum photo once it has downloaded. */
internal fun MainActivity.initialBadge(name: String, photo: String = ""): View {
    val (background, foreground) = avatarColors(name)
    val letter = label(name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "F", 16f, foreground, bold = true).apply {
        gravity = Gravity.CENTER
        this.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(background) }
    }
    if (photo.isBlank()) return letter
    return FrameLayout(this).apply {
        addView(letter, FrameLayout.LayoutParams(-1, -1))
        addView(android.widget.ImageView(this@initialBadge).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            this.background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            Avatars.load(photo, this)
        }, FrameLayout.LayoutParams(-1, -1))
    }
}

internal fun MainActivity.cssColor(color: Int) = String.format("#%06X", color and 0xFFFFFF)

internal fun MainActivity.action(icon: Int, description: String, click: () -> Unit) = android.widget.ImageView(this).apply {
    setImageResource(icon)
    scaleType = android.widget.ImageView.ScaleType.CENTER
    contentDescription = description
    background = android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), null, rounded(Color.WHITE, 21),
    )
    setOnClickListener { click() }
    isFocusable = false
}

internal fun MainActivity.pill(text: String, backgroundColor: Int, foregroundColor: Int, click: () -> Unit): TextView = label(text, 13f, foregroundColor, bold = true).apply {
    gravity = Gravity.CENTER
    setPadding(dp(16), dp(11), dp(16), dp(11))
    background = rounded(backgroundColor, 24)
    setOnClickListener { click() }
}

internal fun MainActivity.label(text: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
    includeFontPadding = true
}

internal fun MainActivity.row(block: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    block()
}

internal fun MainActivity.rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radius).toFloat()
}

internal fun MainActivity.margin(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2).apply {
    if (top > 0) topMargin = dp(top)
    if (bottom > 0) bottomMargin = dp(bottom)
}

internal fun MainActivity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun MainActivity.showError(error: Throwable) {
    content.removeAllViews()
    content.addView(hero("Nie udało się połączyć", "Sprawdź internet i spróbuj ponownie."))
    content.addView(emptyCard("Połączenie z forum nie powiodło się", error.localizedMessage?.take(160).orEmpty()))
    content.addView(openSiteButton("Spróbuj ponownie") { renderCurrent() })
}

internal fun MainActivity.openBrowser(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/** A text field for native forms: one line, or a multi-line box of at least [lines] lines. */
internal fun MainActivity.formField(hint: String, lines: Int): EditText = EditText(this).apply {
    this.hint = hint
    textSize = 16f
    setTextColor(INK)
    setHintTextColor(MUTED)
    background = rounded(CANVAS, 14)
    setPadding(dp(16), dp(13), dp(16), dp(13))
    if (lines == 1) {
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    } else {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        minLines = lines
        gravity = Gravity.TOP or Gravity.START
    }
}

internal fun MainActivity.openForumWeb(url: String, loginFlow: Boolean = false, title: String? = null) {
    val intent = Intent(this, ForumWebActivity::class.java)
        .putExtra(ForumWebActivity.EXTRA_URL, url)
        .putExtra(ForumWebActivity.EXTRA_LOGIN_FLOW, loginFlow)
        .putExtra(ForumWebActivity.EXTRA_DARK_MODE, darkMode)
        .putExtra(ForumWebActivity.EXTRA_TITLE, title)
    @Suppress("DEPRECATION")
    startActivityForResult(intent, WEB_ACTION_REQUEST)
}

internal fun MainActivity.hideKeyboard(view: View) {
    (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(view.windowToken, 0)
}

internal fun MainActivity.styleSwitch(switch: android.widget.Switch) {
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    switch.thumbTintList = android.content.res.ColorStateList(states, intArrayOf(LINK, MUTED))
    switch.trackTintList = android.content.res.ColorStateList(states, intArrayOf(ACCENT_FILL, TEAL_LIGHT))
}

/** Plain-text previews: app footers (when enabled) and Tapatalk emoji placeholders like "[emoji41]". */
internal fun MainActivity.stripSignatureText(text: String): String =
    (if (hideSignatures) text.replace(SIGNATURE, "") else text).replace(Regex("""\[emoji\d+]"""), "").trim()

/**
 * Removes app footers from post HTML (when enabled) and the empty paragraphs and line breaks
 * forum editors leave behind, which otherwise show up as large gaps.
 */
internal fun MainActivity.stripSignatureHtml(html: String, always: Boolean = false): String {
    val body = org.jsoup.Jsoup.parseBodyFragment(html).body()
    if ((always || hideSignatures) && SIGNATURE.containsMatchIn(html)) {
        body.select("*").flatMap { it.textNodes() }.forEach { node ->
            if (SIGNATURE.containsMatchIn(node.wholeText)) node.text(node.wholeText.replace(SIGNATURE, ""))
        }
    }
    fun blank(element: org.jsoup.nodes.Element) =
        element.text().replace('\u00A0', ' ').isBlank() && element.select("img,a[href],iframe,hr").isEmpty()
    body.select("p, div").filter(::blank).forEach { it.remove() }
    // Line breaks: none at the start or end of a block, at most one in a row.
    body.select("br").forEach { br ->
        val previous = br.previousSibling()?.let { if (it is org.jsoup.nodes.TextNode && it.isBlank) it.previousSibling() else it }
        val next = br.nextSibling()?.let { if (it is org.jsoup.nodes.TextNode && it.isBlank) it.nextSibling() else it }
        if (previous == null || next == null || (previous as? org.jsoup.nodes.Element)?.normalName() == "br") br.remove()
    }
    return body.html()
}
