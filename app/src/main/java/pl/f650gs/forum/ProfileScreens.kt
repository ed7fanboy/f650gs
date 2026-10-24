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

/** A member's profile page itself (not its sub-pages such as edit or content). */
internal fun MainActivity.isProfileUrl(url: String): Boolean {
    val uri = runCatching { java.net.URI(ForumParser.withoutWarnReference(url)) }.getOrNull() ?: return false
    val host = uri.host?.lowercase().orEmpty()
    return (host == "f650gs.pl" || host.endsWith(".f650gs.pl")) && uri.rawQuery == null &&
        Regex("""/profile/\d+-[^/]*/?""").matches(uri.rawPath.orEmpty())
}

internal fun MainActivity.renderProfile(profile: MemberProfile) {
    content.removeAllViews()
    appBarTitle.text = profile.name
    val header = baseCard().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(18), dp(22), dp(18), dp(18)) }
    header.addView(initialBadge(profile.name, profile.photo), LinearLayout.LayoutParams(dp(88), dp(88)))
    header.addView(label(profile.name, 22f, INK, bold = true).apply { gravity = Gravity.CENTER }, margin(top = 12))
    val status = listOf(profile.group, if (profile.online) "● Teraz na forum" else "").filter { it.isNotBlank() }
    if (status.isNotEmpty()) header.addView(label(status.joinToString("   "), 13f, MUTED, bold = true).apply {
        gravity = Gravity.CENTER
        if (profile.online) text = android.text.SpannableString(text).apply {
            val start = text.indexOf('●')
            setSpan(android.text.style.ForegroundColorSpan(Color.rgb(46, 160, 67)), start, length, 0)
        }
    }, margin(top = 4))
    if (profile.canMessage) header.addView(pill("✉  Napisz wiadomość", ACCENT_FILL, WHITE) {
        navigate(Screen.Compose(profile.id, profile.name, profile.photo))
    }, margin(top = 16))
    content.addView(header)
    fun details(title: String, rows: List<Pair<String, String>>) {
        if (rows.isEmpty()) return
        content.addView(sectionHeading(title))
        val card = baseCard()
        rows.forEachIndexed { index, (name, value) ->
            card.addView(row {
                addView(label(name, 13f, MUTED, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(label(value, 14f, INK).apply {
                    gravity = Gravity.END
                    setLinkTextColor(LINK)
                    android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS)
                }, LinearLayout.LayoutParams(0, -2, 1.3f))
            }, margin(top = if (index == 0) 0 else 10))
        }
        content.addView(card)
    }
    details("Na forum", profile.stats)
    details("O użytkowniku", profile.fields)
    val topics = profile.activity.groupBy { topicPath(it.url) }.values
    content.addView(sectionHeading("Ostatnia aktywność", if (topics.isEmpty()) "" else "${topics.size} tematów").apply {
        (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
    })
    if (topics.isEmpty()) content.addView(emptyCard("Brak aktywności", "Ta osoba nie pisała ostatnio na forum."))
    topics.forEach { stories -> content.addView(activityCard(stories.first(), stories.drop(1))) }
}

internal fun MainActivity.renderCompose(screen: Screen.Compose) {
    content.removeAllViews()
    if (!signedIn) return renderForumFeature("Wiadomości prywatne", "Zaloguj się, aby pisać do innych członków forum.", "Zaloguj się") { openLogin() }
    val card = baseCard().apply { setPadding(dp(18), dp(18), dp(18), dp(18)) }
    fun field(hint: String, lines: Int) = formField(hint, lines)
    card.addView(label("DO", 11f, LINK, bold = true))
    val recipientBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    card.addView(recipientBox, margin(top = 6))
    val title = field("Temat", 1)
    val body = field("Treść wiadomości", 6)
    card.addView(label("TEMAT", 11f, LINK, bold = true), margin(top = 16))
    card.addView(title, margin(top = 6))
    card.addView(label("WIADOMOŚĆ", 11f, LINK, bold = true), margin(top = 16))
    card.addView(body, margin(top = 6))
    val error = label("", 13f, Color.rgb(220, 50, 47), bold = true).apply { visibility = View.GONE }
    card.addView(error, margin(top = 8))
    lateinit var submit: TextView
    submit = pill("Wyślij wiadomość", ACCENT_FILL, WHITE) {}
    card.addView(submit, margin(top = 10))
    content.addView(card)

    var form: ComposeForm? = null
    fun showError(message: String) { error.text = message; error.visibility = View.VISIBLE }
    // Loads the forum's form for the chosen member, which also confirms they can receive messages.
    fun choose(id: String, name: String, photo: String, changeable: Boolean) {
        form = null
        error.visibility = View.GONE
        recipientBox.removeAllViews()
        recipientBox.addView(row {
            addView(initialBadge(name, photo), LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(label(name, 16f, INK, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
            if (changeable) addView(label("Zmień", 13f, LINK, bold = true).apply {
                setPadding(dp(10), dp(8), 0, dp(8))
                setOnClickListener { renderCompose(Screen.Compose()) }
            })
        })
        val request = requestId.get()
        io.execute {
            val result = runCatching { repository.composeForm(id) }
            main.post {
                if (request != requestId.get() || currentScreen !is Screen.Compose) return@post
                result.onSuccess { form = it }.onFailure { showError(it.localizedMessage ?: "Nie możesz napisać do tej osoby.") }
            }
        }
        title.requestFocus()
    }
    if (screen.memberId.isNotBlank()) {
        choose(screen.memberId, screen.name, screen.photo, changeable = false)
    } else {
        val search = field("Wpisz nazwę (min. 3 litery)", 1)
        val hits = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        recipientBox.addView(search)
        recipientBox.addView(hits, margin(top = 4))
        memberSuggestions(search, hits) { member -> choose(member.id, member.name, member.photo, changeable = true) }
        search.requestFocus()
    }
    submit.setOnClickListener {
        val compose = form
        val subject = title.text.toString().trim()
        val text = body.text.toString().trim()
        when {
            compose == null -> return@setOnClickListener showError(if (error.visibility == View.VISIBLE) error.text.toString() else "Wybierz odbiorcę.")
            subject.isBlank() -> return@setOnClickListener showError("Wpisz temat.")
            text.isBlank() -> return@setOnClickListener showError("Wpisz treść wiadomości.")
        }
        hideKeyboard(body)
        submit.isEnabled = false
        submit.text = "Wysyłam…"
        error.visibility = View.GONE
        io.execute {
            val result = runCatching { repository.startConversation(compose!!, subject, text) }
            main.post {
                result.onSuccess { url ->
                    if (currentScreen !is Screen.Compose) return@post
                    // Replace the form with the new conversation, so Back returns to where it was opened from.
                    currentScreen = Screen.Conversation(subject, url)
                    renderCurrent()
                }.onFailure {
                    submit.isEnabled = true
                    submit.text = "Wyślij wiadomość"
                    showError(it.localizedMessage ?: "Nie udało się wysłać wiadomości.")
                }
            }
        }
    }
}

/** Forum members matching what is typed in [search] (3+ letters), listed in [hits]; tapping one calls [pick]. */
internal fun MainActivity.memberSuggestions(search: EditText, hits: LinearLayout, pick: (MemberHit) -> Unit) {
    var pending: Runnable? = null
    search.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(text: android.text.Editable?) {
            pending?.let(main::removeCallbacks)
            val query = text?.toString()?.trim().orEmpty()
            if (query.length < 3) { hits.removeAllViews(); return }
            pending = Runnable {
                io.execute {
                    val found = runCatching { repository.findMembers(query) }.getOrDefault(emptyList())
                    main.post {
                        if (search.text.toString().trim() != query || !search.isAttachedToWindow) return@post
                        hits.removeAllViews()
                        // The exact name is already in the box: nothing left to pick.
                        if (found.any { it.name == query } && found.size == 1) return@post
                        if (found.isEmpty()) hits.addView(label("Nikt o takiej nazwie", 13f, MUTED).apply { setPadding(dp(4), dp(10), 0, dp(6)) })
                        found.take(8).forEach { member ->
                            hits.addView(row {
                                setPadding(dp(4), dp(8), dp(4), dp(8))
                                addView(initialBadge(member.name, member.photo), LinearLayout.LayoutParams(dp(34), dp(34)))
                                addView(label(member.name, 15f, INK, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
                                setOnClickListener { pick(member) }
                            })
                        }
                    }
                }
            }.also { main.postDelayed(it, 300) }
        }
    })
}

