package pl.f650gs.forum

import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.text.TextUtils
import android.view.View
import android.webkit.CookieManager
import android.widget.LinearLayout
import android.widget.TextView

/*
 * Forum pages without a screen of their own (account settings, ignored members, password reset,
 * …) are drawn from what is on them: notes, lists and IPS forms. Pages the parser cannot draw
 * (post editor, uploads, unknown fields) open in the WebView as before.
 */

internal fun MainActivity.openForumPage(url: String, title: String = "") =
    navigate(Screen.ForumPage(title, ForumParser.safeForumUrl(url)))

internal fun MainActivity.loadForumPage(screen: Screen.ForumPage) =
    fetch("Ładuję stronę forum…", { it.nativePage(screen.url) }) { page ->
        if (page != null) return@fetch renderForumPage(page)
        // Not drawable: the forum page itself takes this screen's place.
        if (history.isNotEmpty()) {
            currentScreen = history.removeLast()
            updateChrome()
            renderCurrent()
        }
        openForumWeb(screen.url, title = screen.title.ifBlank { "Forum" })
    }

internal fun MainActivity.renderForumPage(page: NativePage) {
    val screen = currentScreen as? Screen.ForumPage ?: return
    if (page.title.isNotBlank() && page.title != screen.title) {
        currentScreen = screen.copy(title = page.title)
        updateChrome()
    }
    content.removeAllViews()
    if (page.message.isNotBlank()) content.addView(notice(page.message, if (darkMode) Color.rgb(129, 199, 132) else Color.rgb(46, 125, 50)))
    if (page.error.isNotBlank()) content.addView(notice(page.error, ERROR_RED))
    page.blocks.forEach { block ->
        when (block) {
            is PageBlock.Heading -> content.addView(label(block.text.uppercase(), 11f, LINK, bold = true).apply {
                letterSpacing = 0.08f
            }, margin(top = 14, bottom = 8))
            is PageBlock.Text -> content.addView(label(block.text, 13f, MUTED), margin(top = 2, bottom = 8))
            is PageBlock.Links -> block.items.forEach { content.addView(pageLinkCard(it)) }
            is PageBlock.Form -> content.addView(formCard(block.form))
        }
    }
}

private val ERROR_RED = Color.rgb(220, 50, 47)

private fun MainActivity.notice(text: String, color: Int) = label(text, 14f, color, bold = true).apply {
    setPadding(dp(14), dp(10), dp(14), dp(10))
    background = rounded(CARD, 14)
    layoutParams = margin(bottom = 10)
}

private fun MainActivity.pageLinkCard(link: PageLink): View = baseCard().apply {
    addView(row {
        if (link.photo.isNotBlank()) addView(initialBadge(link.title, link.photo), LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(10) })
        val text = LinearLayout(this@pageLinkCard).apply { orientation = LinearLayout.VERTICAL }
        text.addView(label(link.title, 15f, INK, bold = true))
        if (link.detail.isNotBlank()) text.addView(label(link.detail, 13f, MUTED).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }, margin(top = 2))
        addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        if (link.url.isNotBlank()) addView(label("›", 22f, MUTED))
    })
    if (link.actions.isNotEmpty()) addView(row {
        link.actions.forEach { (name, url) ->
            addView(pill(name, TEAL_LIGHT, LINK) { confirmForumAction(name, url) }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }
    }, margin(top = 10))
    if (link.url.isNotBlank()) setOnClickListener { routePostLink(Uri.parse(link.url)) }
}

private fun MainActivity.confirmForumAction(name: String, url: String) {
    AlertDialog.Builder(this)
        .setTitle("$name?")
        .setPositiveButton(name) { _, _ ->
            io.execute {
                val result = runCatching { repository.forumAction(url) }
                main.post {
                    result.onFailure { showError(it) }
                    if (currentScreen is Screen.ForumPage) renderCurrent()
                }
            }
        }
        .setNegativeButton("Anuluj", null)
        .show()
}

/** A form card: its items in page order and the submit button; the forum's answer replaces the page. */
private fun MainActivity.formCard(form: ForumForm): View {
    val card = baseCard().apply { setPadding(dp(18), dp(8), dp(18), dp(18)) }
    val values = mutableListOf<() -> List<Pair<String, String>>>()
    val problems = mutableListOf<() -> String?>()
    var previousPassword: (() -> String)? = null
    form.items.forEach { item ->
        when (item) {
            is FormItem.Info -> {
                card.addView(label(item.label, 13f, MUTED, bold = true), margin(top = 14))
                card.addView(label(item.value, 16f, INK), margin(top = 4))
            }
            is FormItem.Input -> {
                val field = item.field
                val read = signUpInput(card, field, ERROR_RED)
                values += { if (field.kind != "checkbox") listOf(field.name to read()) else if (read() == "1") listOf(field.name to "1") else emptyList() }
                if (field.required) problems += { if (read().isBlank()) "Uzupełnij: ${field.label.ifBlank { "wymagane pole" }}." else null }
                if (field.kind == "password") {
                    val previous = previousPassword
                    if (previous != null && field.name.contains("confirm")) problems += { if (read() != previous()) "Hasła różnią się." else null }
                    previousPassword = read
                }
            }
            is FormItem.Choices -> {
                val state = mutableMapOf<FormToggle, Boolean>()
                choicesView(card, item, state)
                values += { state.filterValues { it }.keys.map { it.name to it.value } }
            }
        }
    }
    val error = label("", 13f, ERROR_RED, bold = true).apply { visibility = View.GONE }
    card.addView(error, margin(top = 10))
    lateinit var submit: TextView
    submit = pill(form.submit, ACCENT_FILL, WHITE) {
        val problem = problems.firstNotNullOfOrNull { it() }
        error.text = problem.orEmpty()
        error.visibility = if (problem == null) View.GONE else View.VISIBLE
        if (problem != null) return@pill
        hideKeyboard(submit)
        submit.isEnabled = false
        submit.text = "Wysyłam…"
        val sent = values.flatMap { it() }
        io.execute {
            val result = runCatching { repository.submitForm(form, sent) }
            main.post {
                if (currentScreen !is Screen.ForumPage) return@post
                result.onSuccess { outcome ->
                    if (signedIn && outcome.cookies.isNotEmpty()) keepSessionCookies(outcome.cookies)
                    if (outcome.saved) android.widget.Toast.makeText(this, "Zapisano", android.widget.Toast.LENGTH_SHORT).show()
                    when {
                        outcome.page != null -> renderForumPage(outcome.page)
                        outcome.saved -> renderCurrent()
                        else -> {
                            submit.isEnabled = true
                            submit.text = form.submit
                            error.text = "Forum nie przyjęło formularza."
                            error.visibility = View.VISIBLE
                        }
                    }
                }.onFailure {
                    submit.isEnabled = true
                    submit.text = form.submit
                    error.text = it.localizedMessage ?: "Nie udało się wysłać formularza."
                    error.visibility = View.VISIBLE
                }
            }
        }
    }
    card.addView(submit, margin(top = 14))
    return card
}

/** A group of boxes (switches), radio choices (one card of options) or a matrix row (one switch per column). */
private fun MainActivity.choicesView(card: LinearLayout, item: FormItem.Choices, state: MutableMap<FormToggle, Boolean>) {
    if (item.title.isNotBlank()) card.addView(label(item.title, 13f, MUTED, bold = true), margin(top = 14))
    item.rows.flatMap { it.toggles }.forEach { state[it] = it.checked }
    val radios = item.rows.flatMap { it.toggles }.filter { it.radio }
    if (radios.isNotEmpty()) {
        val group = android.widget.RadioGroup(this)
        item.rows.forEach { choice ->
            val toggle = choice.toggles.first()
            group.addView(android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                text = choice.title
                setTextColor(INK)
                textSize = 15f
                buttonTintList = android.content.res.ColorStateList.valueOf(LINK)
                isChecked = toggle.checked
                isEnabled = toggle.enabled
                setOnCheckedChangeListener { _, on -> if (on) radios.filter { it.name == toggle.name }.forEach { state[it] = it == toggle } }
            })
        }
        card.addView(group, margin(top = 4))
        return
    }
    item.rows.forEach { setting ->
        if (setting.toggles.size == 1) {
            card.addView(row {
                addView(label(setting.title, 15f, INK), LinearLayout.LayoutParams(0, -2, 1f))
                addView(formSwitch(setting.toggles.first(), state))
            }, margin(top = 6))
        } else {
            card.addView(label(setting.title, 15f, INK, bold = true), margin(top = 10))
            card.addView(row {
                setting.toggles.forEach { toggle ->
                    addView(label(if (toggle.label == "Powiadomienie") "W aplikacji" else toggle.label, 13f, MUTED, bold = true),
                        LinearLayout.LayoutParams(0, -2, 1f))
                    addView(formSwitch(toggle, state), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(12) })
                }
            }, margin(top = 4))
        }
    }
}

private fun MainActivity.formSwitch(toggle: FormToggle, state: MutableMap<FormToggle, Boolean>) =
    android.widget.Switch(this).apply {
        isChecked = toggle.checked
        isEnabled = toggle.enabled
        styleSwitch(this)
        setOnCheckedChangeListener { _, value -> state[toggle] = value }
    }

/** Renewed session cookies (after a password or e-mail change) go to the WebView too, so both stay signed in. */
private fun MainActivity.keepSessionCookies(cookies: Map<String, String>) {
    val manager = CookieManager.getInstance()
    cookies.forEach { (name, value) -> manager.setCookie("http://f650gs.pl/", "$name=$value; path=/; max-age=31536000") }
    manager.flush()
    repository.updateSessionCookies(manager.getCookie("http://f650gs.pl/"))
}
