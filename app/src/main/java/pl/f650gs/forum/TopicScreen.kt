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

/**
 * Loads the forum's edit form. Plain-text posts are edited in the reply box; posts with
 * photos, quotes, links or lists open the forum editor so nothing gets lost.
 */
internal fun MainActivity.startEdit(post: ForumPost) {
    repository.updateSessionCookies(CookieManager.getInstance().getCookie("http://f650gs.pl/"))
    io.execute {
        val result = runCatching { repository.editForm(post.editUrl) }
        main.post {
            val form = result.getOrNull()
            when {
                result.isFailure -> android.widget.Toast.makeText(this, "Nie udało się otworzyć edycji.", android.widget.Toast.LENGTH_SHORT).show()
                form == null -> android.widget.Toast.makeText(this, "Forum nie pozwala już edytować tego posta.", android.widget.Toast.LENGTH_LONG).show()
                else -> {
                    val draft = splitForEdit(form.originalHtml)
                    setQuote(null)
                    editing = form
                    editingPostId = post.id
                    editPrefixHtml = draft.prefix
                    quoteChip.text = "✎  Edytujesz swój post" + draft.keptLabel + "   ✕"
                    quoteChip.visibility = View.VISIBLE
                    chatInput.setText(editableDraft(draft))
                    chatInput.setSelection(chatInput.text.length)
                    chatInput.requestFocus()
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(chatInput, 0)
                }
            }
        }
    }
}

internal fun MainActivity.sendTopicReply() {
    editing?.let { return sendEdit(it) }
    val form = topicPage?.replyForm ?: return
    val text = chatInput.text.toString().trim()
    if (text.isBlank() || !photosReady(chatInput)) return
    val body = postHtml(chatInput.text)
    // Quoting an app footer ("Wysłane z … Tapatalka") is never useful, whatever the display setting.
    val quote = quotedPost?.let { it.copy(html = stripSignatureHtml(it.html, always = true)) }
    if (!beginSending()) return
    io.execute {
        val result = runCatching { repository.sendReply(form, body, quote) }
        main.post {
            endSending()
            result.onSuccess {
                chatInput.setText("")
                setComposerFull(false)
                setQuote(null)
                hideKeyboard(chatInput)
                // `getLastComment` lands on the newest post, i.e. the reply just sent.
                topicPage?.let { page -> openTopicPage(lastCommentUrl(page.url)) }
            }.onFailure {
                android.widget.Toast.makeText(this, it.localizedMessage ?: "Nie udało się wysłać odpowiedzi.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** One photo or embed of an edited post: its HTML, and an image to show for it in the text box. */
internal data class DraftMedia(val html: String, val previewUrl: String?)

/** [text] has a [MEDIA_CHAR] where each of [media] stands. */
internal data class EditDraft(val prefix: String, val text: String, val media: List<DraftMedia>, val keptLabel: String)

/**
 * Splits a post for editing in the reply box: quotes (kept, placed first) and the author's own
 * text (editable; links become their address, emoticons their code) with photos and other
 * embedded media left in their places, to be moved or deleted like letters.
 */
internal fun MainActivity.splitForEdit(html: String): EditDraft {
    val body = org.jsoup.Jsoup.parseBodyFragment(html, "http://f650gs.pl/").body()
    val quotes = body.select("blockquote").filter { it.parents().none { parent -> parent.normalName() == "blockquote" } }
    quotes.forEach { it.remove() }
    val media = body.select("img:not([data-emoticon]):not([src*=/emoticons/]), iframe, video, embed").map { element ->
        // Attachments are thumbnails wrapped in a link to the full image; keep the pair.
        val kept = element.parent()?.takeIf { it.normalName() == "a" } ?: element
        val preview = element.takeIf { it.normalName() == "img" }?.absUrl("src")?.takeIf { it.isNotBlank() }
        DraftMedia("<p>${kept.outerHtml()}</p>", preview).also { kept.replaceWith(org.jsoup.nodes.TextNode("\n$MEDIA_CHAR\n")) }
    }
    body.select("img").forEach { it.replaceWith(org.jsoup.nodes.TextNode(it.attr("alt"))) }
    body.select("a[href]").forEach { link ->
        val href = link.absUrl("href")
        link.replaceWith(org.jsoup.nodes.TextNode(if (link.text().isBlank() || link.text() == href) href else "${link.text()} $href"))
    }
    body.select("br").forEach { it.replaceWith(org.jsoup.nodes.TextNode("\n")) }
    body.select("p, div, li").forEach { it.appendText("\n\n") }
    val text = body.wholeText().replace('\u00A0', ' ').lines().joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n").trim()
    return EditDraft(
        prefix = quotes.joinToString("") { it.outerHtml() },
        text = text,
        media = media,
        keptLabel = when (quotes.size) { 0 -> ""; 1 -> " · cytat bez zmian"; else -> " · ${quotes.size} cytaty bez zmian" },
    )
}

/** The draft's text with each media character turned into its thumbnail. */
internal fun MainActivity.editableDraft(draft: EditDraft): CharSequence {
    val text = android.text.SpannableStringBuilder(draft.text)
    var from = 0
    draft.media.forEach { item ->
        val at = text.indexOf(MEDIA_CHAR, from).takeIf { it >= 0 } ?: return@forEach
        lateinit var span: PostMediaSpan
        span = mediaSpan(item.html, item.previewUrl) { loaded -> replaceSpan(chatInput, span, loaded) }
        text.setSpan(span, at, at + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        from = at + 1
    }
    return text
}

internal fun MainActivity.sendEdit(form: EditForm) {
    val text = chatInput.text.toString().trim()
    if (text.isBlank() || !photosReady(chatInput)) return
    val body = postHtml(chatInput.text)
    val postId = editingPostId
    if (!beginSending()) return
    io.execute {
        val result = runCatching { repository.sendReply(form.form, body, prefixHtml = editPrefixHtml) }
        main.post {
            endSending()
            result.onSuccess {
                setQuote(null)
                chatInput.setText("")
                setComposerFull(false)
                hideKeyboard(chatInput)
                android.widget.Toast.makeText(this, "Post zaktualizowany", android.widget.Toast.LENGTH_SHORT).show()
                topicPage?.let { page -> openTopicPage("${page.url.substringBefore('?')}?do=findComment&comment=$postId") }
            }.onFailure {
                android.widget.Toast.makeText(this, it.localizedMessage ?: "Nie udało się zapisać zmian.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}

internal fun MainActivity.lastCommentUrl(url: String): String = runCatching {
    val uri = java.net.URI(url)
    "${uri.scheme}://${uri.host}${uri.rawPath.replace(Regex("page/\\d+/?$"), "")}?do=getLastComment"
}.getOrDefault(url)

internal fun MainActivity.renderTopic(page: TopicPage) {
    topicPage = page
    // Topics opened from a link have no title yet: take it from the loaded page.
    (currentScreen as? Screen.Topic)?.takeIf { it.title.isBlank() }?.let { currentScreen = it.copy(title = page.title); updateChrome() }
    val scrollToEnd = scrollToEndAfterLoad
    scrollToEndAfterLoad = false
    content.removeAllViews()
    content.addView(hero(page.title, page.forum.ifBlank { "Forum F650GS" }))
    if (signedIn && page.topicModeration != null) content.addView(row {
        setPadding(0, 0, 0, dp(4))
        addView(pill("Moderuj temat…", TEAL_LIGHT, LINK) { chooseTopicAction(page) })
    })
    content.addView(sectionHeading("Wpisy", page.pager.label.ifBlank { "${page.posts.size} pokazanych" }))
    content.addView(pagerRow(page))
    // The lower pager waits for the posts to take their height, or both pagers sit together while they load.
    val bottomPager = pagerRow(page)
    if (page.posts.isEmpty()) content.addView(emptyCard("Nie udało się odczytać wpisów", "Pełna dyskusja jest dostępna na stronie forum."))
    else {
        bottomPager.visibility = View.GONE
        content.addView(topicPostsView(page.posts, if (scrollToEnd) -1 else page.focusPostId, signedIn && page.replyForm != null,
            page.pager.firstNumber, moderate = page.moderation != null) { bottomPager.visibility = View.VISIBLE }.also { topicPostsWeb = it })
    }
    content.addView(bottomPager)
    // Native reply box when the forum offers a reply form; the forum page otherwise (locked topic, guest).
    chatComposer.visibility = if (signedIn && page.replyForm != null) View.VISIBLE else View.GONE
    if (chatComposer.visibility != View.VISIBLE && signedIn) {
        content.addView(openSiteButton("Odpowiedz na stronie forum") { openForumWeb(page.url, title = "Odpowiedź w temacie") })
    }
}

/** The bar above the reply box while posts are ticked: how many, an action menu, and clearing the ticks. */
internal fun MainActivity.buildModBar(): LinearLayout = row {
    gravity = Gravity.CENTER_VERTICAL
    setBackgroundColor(CARD)
    setPadding(dp(16), dp(8), dp(12), dp(8))
    visibility = View.GONE
    modBarLabel = label("", 14f, INK, bold = true)
    addView(modBarLabel, LinearLayout.LayoutParams(0, -2, 1f))
    addView(pill("Moderuj…", ACCENT_FILL, WHITE) { chooseModAction() })
    addView(pill("✕", TEAL_LIGHT, LINK) {
        modSelected.clear()
        topicPostsWeb?.evaluateJavascript("document.querySelectorAll('.modBox').forEach(function(b){b.checked=false})", null)
        updateModBar()
    }.apply { contentDescription = "Odznacz wszystkie" }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
}

/** The ticked posts that are on the topic page now shown (ticks from a page being replaced do not count). */
internal fun MainActivity.selectedPosts(): List<ForumPost> =
    topicPage?.takeIf { currentScreen is Screen.Topic }?.posts?.filter { it.id in modSelected }.orEmpty()

internal fun MainActivity.updateModBar() {
    val count = if (topicPage?.moderation == null) 0 else selectedPosts().size
    modBarLabel.text = "Zaznaczone: $count"
    modBar.visibility = if (count == 0) View.GONE else View.VISIBLE
}

/** "1 wpis", "3 wpisy", "5 wpisów". */
internal fun postsCount(count: Int): String = when {
    count == 1 -> "1 wpis"
    count % 10 in 2..4 && count % 100 !in 12..14 -> "$count wpisy"
    else -> "$count wpisów"
}

/**
 * The actions every ticked post allows, as the forum names them. Splitting is left to the forum's
 * page: it asks for a new topic's title and section.
 */
internal fun MainActivity.chooseModAction() {
    val page = topicPage ?: return
    val form = page.moderation ?: return
    val selected = selectedPosts()
    if (selected.isEmpty()) return updateModBar()
    val actions = form.actions.filter { (value, _) ->
        value != "split" && selected.all { value in it.modActions } && (value != "merge" || selected.size > 1)
    }
    if (actions.isEmpty()) {
        android.widget.Toast.makeText(this, "Dla zaznaczonych wpisów nie ma wspólnej akcji.", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    AlertDialog.Builder(this)
        .setTitle(postsCount(selected.size))
        .setItems(actions.map { it.second }.toTypedArray()) { _, which -> confirmModAction(page, form, actions[which], selected) }
        .show()
}

internal fun MainActivity.confirmModAction(page: TopicPage, form: ModerationForm, action: Pair<String, String>, posts: List<ForumPost>) {
    val message = when (action.first) {
        "delete" -> "Usunąć zaznaczone (${postsCount(posts.size)})? Tego nie da się cofnąć."
        "merge" -> "Połączyć zaznaczone (${postsCount(posts.size)}) w jeden? Treść trafi do najstarszego z nich, pozostałe znikną."
        else -> null
    }
    if (message == null) return runModAction(page, form, action, posts)
    AlertDialog.Builder(this)
        .setMessage(message)
        .setPositiveButton(action.second) { _, _ -> runModAction(page, form, action, posts) }
        .setNegativeButton("Anuluj", null)
        .show()
}

internal fun MainActivity.runModAction(page: TopicPage, form: ModerationForm, action: Pair<String, String>, posts: List<ForumPost>) {
    repository.updateSessionCookies(CookieManager.getInstance().getCookie("http://f650gs.pl/"))
    io.execute {
        val result = runCatching { repository.moderate(form, action.first, posts.map { it.id }) }
        main.post {
            result.onSuccess {
                android.widget.Toast.makeText(this, "Gotowe: ${action.second.lowercase()}", android.widget.Toast.LENGTH_SHORT).show()
                // Only while that topic is still open; a page emptied by a delete may be gone, so go to the topic's end.
                if (currentScreen is Screen.Topic && topicPage?.url == page.url) {
                    val emptied = action.first == "delete" && posts.size == page.posts.size
                    openTopicPage(if (emptied) lastCommentUrl(page.url) else page.url)
                }
            }.onFailure {
                android.widget.Toast.makeText(this, it.localizedMessage ?: "Nie udało się.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** Section the quick "to the dump" move sends topics to. */
private const val DUMP_FORUM = "Wysypisko"

/** The topic's staff menu as the forum offers it (labels follow the topic's state), plus the quick move to the dump. */
internal fun MainActivity.chooseTopicAction(page: TopicPage) {
    val mod = page.topicModeration ?: return
    val items = buildList<Triple<String, Int, () -> Unit>> {
        if (mod.url("move").isNotBlank() && !page.forum.equals(DUMP_FORUM, ignoreCase = true)) {
            add(Triple("Na wysypisko", R.drawable.ic_mod_dump) { moveTopic(page, mod.url("move"), toDump = true) })
        }
        mod.actions.forEach { action ->
            val run: () -> Unit = when (action.key) {
                "move" -> { { moveTopic(page, action.url, toDump = false) } }
                "merge" -> { { mergeTopic(page, action.url) } }
                "hide" -> { { hideTopic(page, action.url) } }
                "delete" -> { {
                    AlertDialog.Builder(this@chooseTopicAction)
                        .setMessage("Usunąć cały temat „${page.title}” ze wszystkimi wpisami? Tego nie da się cofnąć.")
                        .setPositiveButton(action.label) { _, _ -> runTopicAction(page, "Temat usunięty", leave = true) { repository.forumAction(action.url) } }
                        .setNegativeButton("Anuluj", null)
                        .show()
                } }
                else -> { { runTopicAction(page, "Gotowe: ${action.label.lowercase()}") { repository.forumAction(action.url) } } }
            }
            val label = if (action.key in setOf("move", "merge", "hide")) "${action.label}…" else action.label
            add(Triple(label, topicActionIcon(action.key), run))
        }
    }
    val adapter = object : android.widget.ArrayAdapter<Triple<String, Int, () -> Unit>>(this, android.R.layout.select_dialog_item, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getView(position, convertView, parent) as TextView).apply {
                val (label, icon, _) = items[position]
                text = label
                val drawable = getDrawable(icon)?.mutate()?.apply { setTint(currentTextColor) }
                setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
                compoundDrawablePadding = dp(20)
            }
    }
    AlertDialog.Builder(this)
        .setTitle("Temat")
        .setAdapter(adapter) { _, which -> items[which].third() }
        .show()
}

internal fun topicActionIcon(key: String): Int = when (key) {
    "feature" -> R.drawable.ic_mod_feature
    "unfeature" -> R.drawable.ic_mod_unfeature
    "pin" -> R.drawable.ic_mod_pin
    "unpin" -> R.drawable.ic_mod_unpin
    "hide" -> R.drawable.ic_mod_hide
    "unhide" -> R.drawable.ic_visibility
    "lock" -> R.drawable.ic_mod_lock
    "unlock" -> R.drawable.ic_mod_unlock
    "move" -> R.drawable.ic_mod_move
    "merge" -> R.drawable.ic_mod_merge
    else -> R.drawable.ic_mod_delete
}

/** A dialog with one text field; [send] gets the trimmed text. */
internal fun MainActivity.topicTextDialog(title: String, message: String, hint: String, value: String, button: String, send: (String) -> Unit) {
    val input = formField(hint, 1).apply { setText(value) }
    val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
    AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setView(box)
        .setPositiveButton(button) { _, _ -> send(input.text.toString().trim()) }
        .setNegativeButton("Anuluj", null)
        .show()
}

internal fun MainActivity.hideTopic(page: TopicPage, url: String) =
    topicTextDialog("Ukryj temat", "Ukryty temat widzą tylko moderatorzy.", "Powód (nieobowiązkowy)", "", "Ukryj") { reason ->
        runTopicAction(page, "Temat ukryty") { repository.sendTopicForm(url, mapOf("hide_reason" to reason)) }
    }

/**
 * Merges another topic into this one, as the forum's "Połącz": its posts move here and this topic keeps its title.
 * Takes the other topic's address or number; a topic link on the clipboard is filled in.
 */
internal fun MainActivity.mergeTopic(page: TopicPage, url: String) {
    val clip = runCatching {
        (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()
    }.getOrNull().orEmpty().trim()
    val ownId = Regex("""/topic/(\d+)""").find(page.url)?.groupValues?.get(1)
    val suggested = clip.takeIf { Regex("""f650gs\.pl/topic/(\d+)""").find(it)?.groupValues?.get(1).let { id -> id != null && id != ownId } }.orEmpty()
    topicTextDialog("Połącz z tematem", "Wpisy z podanego tematu trafią tutaj, a tamten temat zniknie. Ten zachowa tytuł i adres.",
        "Adres lub numer tematu", suggested, "Połącz") { text ->
        val id = Regex("""^(\d+)$""").find(text)?.groupValues?.get(1) ?: Regex("""f650gs\.pl/topic/(\d+)""").find(text)?.groupValues?.get(1)
        when {
            id == null -> android.widget.Toast.makeText(this, "To nie jest adres tematu z f650gs.pl.", android.widget.Toast.LENGTH_LONG).show()
            id == ownId -> android.widget.Toast.makeText(this, "Nie można połączyć tematu z nim samym.", android.widget.Toast.LENGTH_LONG).show()
            else -> runTopicAction(page, "Tematy połączone") {
                val address = if (text == id) "http://f650gs.pl/topic/$id/" else text.substringBefore('#')
                repository.sendTopicForm(url, mapOf("merge_with" to address, "move_keep_link" to "0"))
            }
        }
    }
}

/** Reads the forum's move form, then moves the topic to the dump or to a section picked from its list. */
internal fun MainActivity.moveTopic(page: TopicPage, moveUrl: String, toDump: Boolean) {
    repository.updateSessionCookies(CookieManager.getInstance().getCookie("http://f650gs.pl/"))
    io.execute {
        val result = runCatching { repository.moveForm(moveUrl) }
        main.post {
            result.onFailure {
                android.widget.Toast.makeText(this, it.localizedMessage ?: "Nie udało się.", android.widget.Toast.LENGTH_LONG).show()
            }.onSuccess { form ->
                if (toDump) {
                    val dump = form.targets.firstOrNull { it.title.equals(DUMP_FORUM, ignoreCase = true) }
                        ?: return@onSuccess android.widget.Toast.makeText(this, "Forum nie ma działu $DUMP_FORUM.", android.widget.Toast.LENGTH_LONG).show()
                    runTopicAction(page, "Przeniesiono: $DUMP_FORUM") { repository.moveTopic(form, dump.id) }
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Przenieś do")
                        .setItems(form.targets.map { "\u2003".repeat((it.depth - 1).coerceAtLeast(0)).plus(it.title) }.toTypedArray()) { _, which ->
                            val target = form.targets[which]
                            runTopicAction(page, "Przeniesiono: ${target.title}") { repository.moveTopic(form, target.id) }
                        }
                        .setNegativeButton("Anuluj", null)
                        .show()
                }
            }
        }
    }
}

/** Runs a topic action, then shows the topic again (or, with [leave], goes back from a topic that is gone). */
internal fun MainActivity.runTopicAction(page: TopicPage, done: String, leave: Boolean = false, work: () -> Unit) {
    repository.updateSessionCookies(CookieManager.getInstance().getCookie("http://f650gs.pl/"))
    io.execute {
        val result = runCatching(work)
        main.post {
            result.onSuccess {
                android.widget.Toast.makeText(this, done, android.widget.Toast.LENGTH_SHORT).show()
                if (currentScreen is Screen.Topic && topicPage?.url == page.url) {
                    if (leave) goBack() else openTopicPage(page.url)
                }
            }.onFailure {
                android.widget.Toast.makeText(this, it.localizedMessage ?: "Nie udało się.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}

internal fun MainActivity.pagerRow(page: TopicPage): View = pagerRow(page.pager) { openTopicPage(it) }

/** First / previous / next / last page buttons; an empty link greys its button out. */
internal fun MainActivity.pagerRow(pager: Pager, open: (String) -> Unit): View = row {
    if (pager.prevUrl.isBlank() && pager.nextUrl.isBlank()) return@row
    fun button(text: String, url: String, params: LinearLayout.LayoutParams, description: String = text) =
        addView(pill(text, if (url.isBlank()) CANVAS else TEAL_LIGHT, if (url.isBlank()) MUTED else LINK) {
            if (url.isNotBlank()) open(url)
        }.apply {
            contentDescription = description
            maxLines = 1
            setPadding(dp(4), paddingTop, dp(4), paddingBottom)
        }, params)
    // Phones narrower than 360 dp (e.g. Galaxy S9 at its default zoom) get short labels on one line.
    val narrow = resources.configuration.screenWidthDp < 360
    val edge = { LinearLayout.LayoutParams(dp(if (narrow) 44 else 52), -2) }
    val middle = { LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) } }
    button("«", pager.firstUrl, edge(), "Pierwsza strona")
    button(if (narrow) "‹ Poprz." else "‹ Poprzednia", pager.prevUrl, middle(), "Poprzednia strona")
    button(if (narrow) "Nast. ›" else "Następna ›", pager.nextUrl, middle(), "Następna strona")
    button("»", pager.lastUrl, edge().apply { leftMargin = dp(8) }, "Ostatnia strona")
}.apply { layoutParams = margin(bottom = 10) }

/** Moves within the same topic without adding a back step per page. */
internal fun MainActivity.openTopicPage(url: String, toEnd: Boolean = false) {
    val screen = currentScreen as? Screen.Topic ?: return
    scrollToEndAfterLoad = toEnd
    currentScreen = Screen.Topic(screen.title, url)
    renderCurrent()
}

internal fun MainActivity.scrollTopicToEnd() {
    val page = topicPage
    if (page != null && page.pager.lastUrl.isNotBlank()) openTopicPage(page.pager.lastUrl, toEnd = true)
    else scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
}

/** [focusPostId] > 0 scrolls to that post once laid out; -1 scrolls to the last post. */
internal fun MainActivity.topicPostsView(
    posts: List<ForumPost>,
    focusPostId: Long = 0,
    canQuote: Boolean = false,
    firstNumber: Int = 1,
    moderate: Boolean = false,
    onLaidOut: () -> Unit = {},
): WebView {
    val html = buildString {
        append("<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        append("<style>")
        append("html,body{margin:0;padding:0;background:")
        append(if (darkMode) "#11191c" else "#f1f5f8")
        append(";color:").append(if (darkMode) "#e8efed" else "#1b2b30")
        append(";font:16px/1.55 sans-serif;overflow:hidden}body{padding:0 1px;box-sizing:border-box}.post{background:")
        append(if (darkMode) "#1d292d" else "#fff")
        append(";border:1px solid ").append(if (darkMode) "#36484b" else "#dbe4e8")
        append(";border-radius:15px;padding:14px 15px;margin:0 0 10px;box-sizing:border-box}.postHeader{display:flex;align-items:center;gap:8px;color:")
        append(if (darkMode) "#b9c8c9" else "#586c71")
        append(";font-size:13px;font-weight:600;border-bottom:1px solid ").append(if (darkMode) "#36484b" else "#dbe4e8")
        append(";padding-bottom:9px}.postNumber{margin-left:auto;color:").append(if (darkMode) "#b9c8c9" else "#64777b")
        append("}.postBody{padding-top:10px;overflow-wrap:anywhere}.postBody p{margin:0 0 12px}.postBody p:last-child{margin-bottom:0}")
        // Photos fill the post's width; very tall ones stop at three quarters of the screen.
        val photoMaxHeight = (resources.displayMetrics.heightPixels / resources.displayMetrics.density * 0.75f).toInt()
        append(".postBody img{display:block;width:100%;max-width:100%;max-height:").append(photoMaxHeight)
        append("px;height:auto;object-fit:contain;vertical-align:middle;border-radius:8px;cursor:zoom-in}.postBody img.small{width:auto}")
        append(".postBody img[data-emoticon],.postBody img[src*=\"/emoticons/\"],.postBody img[src*=\"tapatalk-cdn.com/emoji\"]{display:inline;width:auto;max-width:28px;max-height:28px;border-radius:0;cursor:default}.forumImageFallback{display:inline-block;margin:4px 0;padding:10px 14px;border:1px solid ")
        append(if (darkMode) "#516367" else "#cbd7df")
        append(";border-radius:8px;background:")
        append(if (darkMode) "#26383b" else "#eaf3fb")
        append(";color:")
        append(if (darkMode) "#b8d4d0" else "#245d82")
        append(";font-weight:700;text-decoration:none}")
        append(".forumYoutubeLink{display:inline-block;margin:6px 0;padding:10px 14px;border-radius:10px;background:")
        append(if (darkMode) "#263f50" else "#e1effa")
        append(";color:").append(if (darkMode) "#c7e6fb" else "#005a9c")
        append("!important;font-weight:800;text-decoration:none!important;border:1px solid ")
        append(if (darkMode) "#42647a" else "#a8cce5").append("}")
        // A film with its thumbnail: a full-width card, the picture 16:9 with a play mark, the title under it.
        append(".forumYoutubeLink[data-yt]{display:block;padding:0;overflow:hidden}")
        append(".forumYoutubeThumb{position:relative;display:block;aspect-ratio:16/9;background:#000}")
        append(".postBody .forumYoutubeThumb img{display:block;width:100%;height:100%;max-height:none;object-fit:cover;margin:0;border-radius:0}")
        append(".forumYoutubeThumb b{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);width:60px;height:42px;line-height:42px;border-radius:12px;background:rgba(220,0,0,.88);font-size:0}")
        // A drawn triangle: the "▶" character turns into a coloured emoji on Android.
        append(".forumYoutubeThumb b::after{content:'';position:absolute;left:24px;top:12px;border-style:solid;border-width:9px 0 9px 15px;border-color:transparent transparent transparent #fff}")
        append(".forumYoutubeTitle{display:block;padding:10px 14px}")
        append("blockquote.ipsQuote,blockquote:not(.ipsQuote){margin:12px 0;padding:10px 12px;background:")
        append(if (darkMode) "#26383b" else "#eaf3fb")
        append(";border-left:4px solid ").append(if (darkMode) "#8abbb5" else "#367eae")
        append(";border-radius:7px;color:").append(if (darkMode) "#e8efed" else "#263b3e")
        append("}.ipsQuote_citation{display:none}blockquote.ipsQuote:before{content:attr(data-ipsquote-username) ' napisał(a):';display:block;font-size:12px;font-weight:700;color:")
        append(if (darkMode) "#b8d4d0" else "#245d82")
        append(";margin-bottom:7px}blockquote p{margin:0 0 8px}blockquote p:last-child{margin-bottom:0}a{color:")
        append(if (darkMode) "#a6cae0" else "#0066b1")
        append(";text-decoration:underline}pre,code{white-space:pre-wrap;overflow-wrap:anywhere}table{max-width:100%;border-collapse:collapse}td,th{border:1px solid ")
        append(if (darkMode) "#36484b" else "#cbd7df")
        append(";padding:5px}hr{border:0;border-top:1px solid ").append(if (darkMode) "#36484b" else "#dbe4e8").append("}")
        append("a.who{display:contents;color:inherit;text-decoration:none}")
        append(".modBox{flex:none;width:22px;height:22px;margin:0 0 0 4px;accent-color:").append(if (darkMode) "#2f5a78" else "#0066b1").append("}")
        append(".avatar{position:relative;flex:none;width:34px;height:34px;border-radius:50%;display:inline-flex;align-items:center;justify-content:center;font-weight:800;overflow:hidden}")
        append(".avatar img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;max-height:none!important;border-radius:0!important}")
        append(".postFooter{display:flex;align-items:center;gap:10px;margin-top:12px;padding-top:9px;border-top:1px solid ")
        append(if (darkMode) "#36484b" else "#dbe4e8")
        append("}.likeButton{display:inline-flex;align-items:center;gap:6px;padding:7px 13px;border-radius:18px;border:1px solid ")
        append(if (darkMode) "#42647a" else "#a8cce5")
        append(";background:transparent;color:").append(if (darkMode) "#c7e6fb" else "#005a9c")
        append(";font:700 13px sans-serif}.likeButton.liked{background:").append(if (darkMode) "#2f5a78" else "#0066b1")
        append(";border-color:transparent;color:#fff}.likeButton:disabled{opacity:.55}.likedBy{flex:1;min-width:0;font-size:12px;color:")
        append(if (darkMode) "#b9c8c9" else "#586c71").append("}")
        append("</style></head><body>")
        posts.forEachIndexed { index, post ->
            append("<article class=\"post\" id=\"c").append(post.id).append("\"><header class=\"postHeader\">")
            if (post.authorUrl.isNotBlank()) append("<a class=\"who\" href=\"").append(android.text.Html.escapeHtml(post.authorUrl)).append("\">")
            append(postAvatarHtml(post)).append("<span>")
            append("<b style=\"color:").append(cssColor(avatarColors(post.author).second)).append("\">")
            append(android.text.Html.escapeHtml(post.author)).append("</b>")
            if (post.date.isNotBlank()) append(" · ").append(android.text.Html.escapeHtml(post.date))
            append("</span>")
            if (post.authorUrl.isNotBlank()) append("</a>")
            append("<span class=\"postNumber\">#").append(firstNumber + index).append("</span>")
            if (moderate && post.modActions.isNotEmpty()) {
                append("<input type=\"checkbox\" class=\"modBox\" aria-label=\"Zaznacz wpis\" onchange=\"NativePosts.select(")
                append(index).append(",this.checked)\">")
            }
            append("</header>")
            append("<div class=\"postBody\">").append(stripSignatureHtml(post.html)).append("</div>")
            append(likeFooterHtml(index, post, canQuote))
            append("</article>")
        }
        append("</body></html>")
    }
    return WebView(this).apply {
        setBackgroundColor(CANVAS)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        isNestedScrollingEnabled = true
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        // Forum posts often contain legacy HTTP images from third-party hosts.
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = false
        settings.textZoom = 100
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                routePostLink(request.url)
                return true
            }

            @Deprecated("Deprecated in Android")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                routePostLink(Uri.parse(url))
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript("(function(){var report=function(){NativePosts.update(Math.max(document.body.scrollHeight,document.documentElement.scrollHeight))};document.querySelectorAll('a.forumYoutubeLink[data-yt]').forEach(function(a){var title=a.querySelector('.forumYoutubeTitle');var thumb=a.querySelector('img');if(thumb)thumb.addEventListener('error',function(){thumb.parentNode.style.display='none';report()});if(!title||title.textContent!=='Otwórz film na YouTube')return;fetch('https://www.youtube.com/oembed?format=json&url='+encodeURIComponent('https://www.youtube.com/watch?v='+a.getAttribute('data-yt'))).then(function(r){return r.json()}).then(function(j){if(j&&j.title){title.textContent=j.title;report()}}).catch(function(){})});document.querySelectorAll('.postBody img:not([data-ytthumb])').forEach(function(i){var url=i.currentSrc||i.src;var fallback=function(){if(!i.parentNode)return;var link=document.createElement('a');link.className='forumImageFallback';link.href='#';link.textContent='Otwórz zdjęcie';link.onclick=function(e){e.preventDefault();NativePosts.openImage(url);return false};i.parentNode.replaceChild(link,i);report()};i.addEventListener('load',function(){if(i.naturalWidth>0&&i.naturalWidth<200)i.classList.add('small');report()});i.addEventListener('error',fallback);if(i.complete&&i.naturalWidth===0)fallback();i.onclick=function(){var body=i.closest('.postBody');var all=Array.prototype.filter.call(body?body.querySelectorAll('img:not([data-ytthumb])'):[i],function(x){return !x.hasAttribute('data-emoticon')&&!/\\/emoticons\\/|tapatalk-cdn\\.com\\/emoji/.test(x.src)&&!x.classList.contains('small')&&x.naturalWidth!==0}).map(function(x){return x.currentSrc||x.src});if(all.indexOf(url)<0)all=[url];NativePosts.openGallery(JSON.stringify(all),all.indexOf(url));return false}});if(window.ResizeObserver)new ResizeObserver(report).observe(document.body);report();" +
                    focusScript(focusPostId) + "})()", null)
            }
        }
        addJavascriptInterface(PostContentHeightBridge(this, posts.toMutableList(), onLaidOut), "NativePosts")
        layoutParams = margin(top = 0, bottom = 8)
        loadDataWithBaseURL("http://f650gs.pl/", html, "text/html", "UTF-8", null)
    }
}

internal fun MainActivity.postAvatarHtml(post: ForumPost): String {
    val (background, foreground) = avatarColors(post.author)
    val initial = android.text.Html.escapeHtml(post.author.trim().take(1).uppercase())
    val photo = if (post.photo.isNotBlank()) "<img src=\"${android.text.Html.escapeHtml(post.photo)}\" alt=\"\" onerror=\"this.remove()\">" else ""
    return "<span class=\"avatar\" style=\"background:${cssColor(background)};color:${cssColor(foreground)}\">$initial$photo</span>"
}

internal fun MainActivity.likeFooterHtml(index: Int, post: ForumPost, canQuote: Boolean): String {
    val canReact = signedIn && post.likeUrl.isNotBlank()
    if (!canReact && post.likeCount == 0 && !canQuote && post.editUrl.isBlank()) return ""
    val escape = android.text.Html::escapeHtml
    return buildString {
        append("<footer class=\"postFooter\">")
        if (canReact) {
            append("<button class=\"likeButton").append(if (post.likedByMe) " liked" else "")
            append("\" id=\"like").append(index).append("\" onclick=\"this.disabled=true;NativePosts.toggleLike(").append(index).append(")\">")
        } else {
            append("<span class=\"likeButton\">")
        }
        append("👍 <span class=\"likeLabel\">").append(likeLabel(post)).append("</span>")
        append(if (canReact) "</button>" else "</span>")
        append("<span class=\"likedBy\" id=\"likedBy").append(index).append("\">").append(escape(post.likedBy)).append("</span>")
        if (canQuote) append("<button class=\"likeButton\" onclick=\"NativePosts.quote(").append(index).append(")\">❝ Cytuj</button>")
        if (canQuote && post.editUrl.isNotBlank()) append("<button class=\"likeButton\" onclick=\"NativePosts.edit(").append(index).append(")\">✎ Edytuj</button>")
        append("</footer>")
    }
}

internal fun MainActivity.likeLabel(post: ForumPost): String = when {
    post.likedByMe -> "Lubisz to · ${post.likeCount}"
    post.likeCount > 0 -> "Lubię to · ${post.likeCount}"
    else -> "Lubię to"
}

internal fun MainActivity.focusScript(focusPostId: Long): String {
    if (focusPostId == 0L) return ""
    val target = if (focusPostId < 0) "var a=document.querySelectorAll('article.post');var e=a[a.length-1];"
        else "var e=document.getElementById('c$focusPostId');if(e)e.style.boxShadow='0 0 0 2px ${cssColor(ACCENT_NEW)}';"
    // Re-aim once images above the post have loaded and changed its offset.
    return "var focus=function(){$target if(e)NativePosts.scrollToCss(e.offsetTop)};setTimeout(focus,150);window.addEventListener('load',function(){setTimeout(focus,100)});"
}

internal fun MainActivity.routePostLink(uri: Uri) {
    if (uri.scheme !in setOf("http", "https")) return
    if (uri.toString().startsWith(MENTION_LOOKUP)) return openMention(uri.getQueryParameter("name").orEmpty())
    if (isProfileUrl(uri.toString())) return navigate(Screen.Profile("", ForumParser.withoutWarnReference(uri.toString())))
    if (uri.host?.lowercase()?.let { it == "f650gs.pl" || it.endsWith(".f650gs.pl") } == true) {
        val path = uri.path.orEmpty()
        val action = uri.getQueryParameter("do")
        when {
            // Topic links, including "go to post" (findComment) and unread/last-post jumps.
            Regex("""/topic/\d+[^/]*(/page/\d+)?/?""").matches(path) &&
                action in setOf(null, "findComment", "getNewComment", "getLastComment") ->
                navigate(Screen.Topic("", ForumParser.safeForumUrl(uri.toString())))
            Regex("""/forum/\d+[^/]*/?""").matches(path) && uri.query == null ->
                navigate(Screen.Forum("", ForumParser.safeForumUrl(uri.toString())))
            else -> openForumPage(uri.toString())
        }
    } else {
        openBrowser(uri.toString())
    }
}
