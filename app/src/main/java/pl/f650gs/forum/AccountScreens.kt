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

internal const val ACCOUNT_PHOTO_KEY = "account_photo"
internal const val ACCOUNT_NAME_KEY = "account_name"

/** The signed-in member's screen: who you are, your forum lists, settings and logging out. */
internal fun MainActivity.loadAccount() {
    fetch("Ładuję konto…", { it.account() }) { account ->
        if (account == null) {
            // The forum session has ended (expired cookie or logout elsewhere).
            setSignedOut()
            android.widget.Toast.makeText(this, "Sesja forum wygasła — zaloguj się ponownie.", android.widget.Toast.LENGTH_LONG).show()
        } else renderAccount(account)
    }
}

internal fun MainActivity.renderAccount(account: Account) {
    rememberAccount(account)
    content.removeAllViews()
    content.addView(baseCard().apply {
        addView(row {
            addView(initialBadge(account.name, account.photo), LinearLayout.LayoutParams(dp(56), dp(56)))
            val names = LinearLayout(this@renderAccount).apply { orientation = LinearLayout.VERTICAL }
            names.addView(label(account.name, 19f, INK, bold = true))
            names.addView(label("Zalogowano na f650gs.pl · Twój profil", 12f, MUTED), margin(top = 2))
            addView(names, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
            if (account.profileUrl.isNotBlank()) addView(label("›", 22f, MUTED))
        })
        if (account.profileUrl.isNotBlank()) setOnClickListener { navigate(Screen.Profile(account.name, account.profileUrl)) }
    })
    fun section(title: String) = content.addView(label(title.uppercase(), 11f, LINK, bold = true).apply {
        letterSpacing = 0.08f
    }, margin(top = 14, bottom = 8))
    fun item(title: String, subtitle: String = "", color: Int = INK, icon: Int = 0, click: () -> Unit) = content.addView(baseCard().apply {
        addView(row {
            if (icon != 0) addView(android.widget.ImageView(this@renderAccount).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(if (color == INK) LINK else color)
                scaleType = android.widget.ImageView.ScaleType.CENTER
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(12) })
            val text = LinearLayout(this@renderAccount).apply { orientation = LinearLayout.VERTICAL }
            text.addView(label(title, 15f, color, bold = true))
            if (subtitle.isNotBlank()) text.addView(label(subtitle, 12f, MUTED), margin(top = 2))
            addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("›", 22f, MUTED))
        })
        setOnClickListener { click() }
    })
    // The profile is the card above; account settings and ignored members are on the settings screen's "Forum" tab.
    val lists = account.links.filterNot { (_, url) -> isForumSettingsUrl(url) || isProfileUrl(url) }
    if (lists.isNotEmpty()) section("Twoje na forum")
    lists.forEach { (title, url) ->
        val icon = when {
            "/followed" in url -> R.drawable.ic_visibility
            "/attachments" in url -> R.drawable.ic_attach
            else -> 0
        }
        item(title, icon = icon) { openForumPage(url, title) }
    }
    section("Ustawienia")
    item("Aplikacja", "Wygląd, stopki, powiadomienia na telefonie, dolny pasek", icon = R.drawable.ic_settings) { openSettings(forum = false) }
    item("Forum", "Powiadomienia forum, konto, ignorowani użytkownicy", icon = R.drawable.ic_settings) { openSettings(forum = true) }
    if (account.logoutUrl.isNotBlank()) {
        section("Sesja")
        item("Wyloguj się", color = if (darkMode) Color.rgb(255, 138, 128) else Color.rgb(198, 40, 40), icon = R.drawable.ic_logout) {
            AlertDialog.Builder(this)
                .setTitle("Wylogować się?")
                .setMessage("Zostaniesz wylogowany z forum w tej aplikacji.")
                .setPositiveButton("Wyloguj") { _, _ -> logout(account.logoutUrl) }
                .setNegativeButton("Anuluj", null)
                .show()
        }
    }
}

/** Keeps the member's photo (top bar's account button) and name (to tell your own entries in lists). */
internal fun MainActivity.rememberAccount(account: Account) {
    val prefs = getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    if (prefs.getString(ACCOUNT_PHOTO_KEY, "") == account.photo && prefs.getString(ACCOUNT_NAME_KEY, "") == account.name) return
    prefs.edit().putString(ACCOUNT_PHOTO_KEY, account.photo).putString(ACCOUNT_NAME_KEY, account.name).apply()
    updateChrome()
}

internal fun MainActivity.accountName(): String =
    if (signedIn) getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(ACCOUNT_NAME_KEY, "").orEmpty() else ""

internal fun MainActivity.logout(url: String) {
    io.execute {
        runCatching { repository.logout(url) }
        main.post {
            // Drop the WebView session too, so the native screens and forum pages agree.
            CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
            repository.updateSessionCookies(null)
            setSignedOut()
            android.widget.Toast.makeText(this, "Wylogowano", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

internal fun MainActivity.setSignedOut() {
    signedIn = false
    listCache.clear()
    getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(SIGNED_IN_KEY, false).apply()
    chatCsrf = ""
    counters = Counters(0, 0, 0)
    setBadge(notificationsBadge, 0)
    getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().remove(ACCOUNT_PHOTO_KEY).remove(ACCOUNT_NAME_KEY).apply()
    populateNav(bottomNav)
    openTab(Tab.HOME)
}

internal fun MainActivity.openLogin() {
    if (currentScreen != Screen.Login) navigate(Screen.Login)
}

internal fun MainActivity.renderLogin() {
    content.removeAllViews()
    val card = baseCard().apply { setPadding(dp(18), dp(20), dp(18), dp(18)) }
    card.addView(label("Zaloguj się na f650gs.pl", 20f, INK, bold = true))
    card.addView(label("Forum działa przez nieszyfrowane HTTP — login i hasło nie są chronione w drodze.", 12f, MUTED), margin(top = 6))
    fun field(hint: String, password: Boolean) = EditText(this).apply {
        this.hint = hint
        textSize = 16f
        setTextColor(INK)
        setHintTextColor(MUTED)
        setSingleLine(true)
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
        background = rounded(CANVAS, 14)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }
    val user = field("Nazwa użytkownika", false)
    val password = field("Hasło", true).apply { imeOptions = EditorInfo.IME_ACTION_DONE }
    card.addView(user, margin(top = 18))
    card.addView(password, margin(top = 10))
    val remember = android.widget.CheckBox(this).apply {
        text = "Zapamiętaj mnie"
        isChecked = true
        setTextColor(INK)
        buttonTintList = android.content.res.ColorStateList.valueOf(LINK)
    }
    card.addView(remember, margin(top = 8))
    val error = label("", 13f, Color.rgb(220, 50, 47), bold = true).apply { visibility = View.GONE }
    card.addView(error, margin(top = 4))
    lateinit var submit: TextView
    val doLogin = {
        val name = user.text.toString().trim()
        val pass = password.text.toString()
        if (name.isNotBlank() && pass.isNotBlank()) {
            hideKeyboard(password)
            submit.isEnabled = false
            submit.text = "Loguję…"
            io.execute {
                val result = runCatching { repository.login(name, pass, remember.isChecked) }
                main.post {
                    result.onSuccess { cookies -> finishLogin(cookies, remember.isChecked) }.onFailure {
                        submit.isEnabled = true
                        submit.text = "Zaloguj się"
                        error.text = it.localizedMessage ?: "Logowanie nie powiodło się."
                        error.visibility = View.VISIBLE
                    }
                }
            }
        }
    }
    submit = pill("Zaloguj się", ACCENT_FILL, WHITE) { doLogin() }
    password.setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_DONE) { doLogin(); true } else false }
    card.addView(submit, margin(top = 14))
    card.addView(row {
        addView(label("Załóż konto", 13f, LINK, bold = true).apply {
            setPadding(0, dp(12), 0, dp(4))
            setOnClickListener { navigate(Screen.SignUp) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(label("Nie pamiętasz hasła?", 13f, LINK, bold = true).apply {
            setPadding(0, dp(12), 0, dp(4))
            setOnClickListener { openForumPage("http://f650gs.pl/lostpassword/", "Przypomnienie hasła") }
        })
    })
    content.addView(card)
    // Without an account there is no account screen; the app's own settings are reachable from here.
    content.addView(label("Ustawienia aplikacji", 13f, LINK, bold = true).apply {
        setPadding(dp(4), dp(14), 0, dp(4))
        setOnClickListener { openSettings(forum = false) }
    })
}

/** Hands the forum session to the WebView so forum pages (editor, profile) are signed in too. */
internal fun MainActivity.finishLogin(cookies: Map<String, String>, remember: Boolean) {
    val manager = CookieManager.getInstance()
    cookies.forEach { (name, value) ->
        manager.setCookie("http://f650gs.pl/", "$name=$value; path=/" + if (remember) "; max-age=31536000" else "")
    }
    manager.flush()
    repository.updateSessionCookies(manager.getCookie("http://f650gs.pl/"))
    signedIn = true
    listCache.clear()
    getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(SIGNED_IN_KEY, true).apply()
    android.widget.Toast.makeText(this, "Zalogowano", android.widget.Toast.LENGTH_SHORT).show()
    refreshCounters(force = true)
    openTab(Tab.HOME)
}

private fun isForumSettingsUrl(url: String) = Regex("""^http://(www\.)?f650gs\.pl/(settings|ignore)/""").containsMatchIn(url)

internal fun MainActivity.openSettings(forum: Boolean) {
    forumSettingsTab = forum && signedIn
    if (currentScreen == Screen.Settings) renderCurrent() else navigate(Screen.Settings)
}

internal fun MainActivity.renderSettings() {
    content.removeAllViews()
    if (signedIn) content.addView(row {
        listOf(false to "Aplikacja", true to "Forum").forEachIndexed { index, (forum, name) ->
            val selected = forumSettingsTab == forum
            addView(pill(name, if (selected) ACCENT_FILL else CARD, if (selected) WHITE else INK) {
                if (!selected) openSettings(forum)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) leftMargin = dp(8) })
        }
    }, margin(top = 2, bottom = 4))
    if (signedIn && forumSettingsTab) return renderForumSettings()
    fun section(title: String) = content.addView(label(title.uppercase(), 11f, LINK, bold = true).apply {
        letterSpacing = 0.08f
    }, margin(top = 14, bottom = 8))
    fun switchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) =
        content.addView(baseCard().apply {
            addView(row {
                val text = LinearLayout(this@renderSettings).apply { orientation = LinearLayout.VERTICAL }
                text.addView(label(title, 15f, INK, bold = true))
                if (subtitle.isNotBlank()) text.addView(label(subtitle, 12f, MUTED), margin(top = 2))
                addView(text, LinearLayout.LayoutParams(0, -2, 1f))
                addView(android.widget.Switch(this@renderSettings).apply {
                    isChecked = checked
                    isEnabled = enabled
                    styleSwitch(this)
                    setOnCheckedChangeListener { _, value -> change(value) }
                })
            })
        })
    val prefs = getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    section("Wygląd")
    content.addView(row {
        listOf("system" to "Systemowy", "light" to "Jasny", "dark" to "Ciemny").forEachIndexed { index, (mode, name) ->
            val selected = themeMode == mode
            addView(pill(name, if (selected) ACCENT_FILL else CARD, if (selected) WHITE else INK) {
                if (!selected) {
                    prefs.edit().putString(THEME_KEY, mode).apply()
                    recreate()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) leftMargin = dp(8) })
        }
    })

    section("Treść")
    switchRow("Ukrywaj stopki", "Np. „Wysłane z mojego … przy użyciu Tapatalka”", hideSignatures) { value ->
        hideSignatures = value
        prefs.edit().putBoolean(HIDE_SIGNATURES_KEY, value).apply()
    }

    section("Powiadomienia systemowe")
    content.addView(label("Powiadomienia tej aplikacji na telefonie. To, co forum zgłasza w dzwonku i e-mailem, ustawisz w zakładce Forum.", 12f, MUTED),
        margin(bottom = 8))
    fun notifyRow(key: String, title: String, subtitle: String) = switchRow(title, subtitle, prefs.getBoolean(key, false), signedIn) { value ->
        prefs.edit().putBoolean(key, value).apply()
        if (value) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
            refreshCounters(force = true)
        }
        ForumCheckJob.sync(this)
    }
    notifyRow(ForumCheckJob.NOTIFY_MESSAGES, "Wiadomości prywatne", "Nowa wiadomość w skrzynce")
    notifyRow(ForumCheckJob.NOTIFY_FORUM, "Powiadomienia forum", "Odpowiedzi, cytaty, polubienia, obserwowane tematy")
    notifyRow(ForumCheckJob.NOTIFY_TOPICS, "Nowe nieprzeczytane tematy", "Każdy nowy temat z wpisami do nadrobienia")
    content.addView(label(
        if (signedIn) "Sprawdzane w tle mniej więcej co 15 minut (minimum Androida)." else "Zaloguj się, aby włączyć powiadomienia.",
        12f, MUTED,
    ), margin(top = 2, bottom = 4))

    section("Dolny pasek")
    val order = tabOrder().toMutableList()
    val hidden = hiddenTabs().toMutableSet()
    val tabRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    content.addView(tabRows)
    order.forEach { tab ->
        tabRows.addView(baseCard().apply {
            tag = tab
            setPadding(dp(4), dp(8), dp(8), dp(8))
            val card = this
            addView(row {
                addView(android.widget.ImageView(this@renderSettings).apply {
                    setImageResource(R.drawable.ic_drag_handle)
                    imageTintList = android.content.res.ColorStateList.valueOf(MUTED)
                    scaleType = android.widget.ImageView.ScaleType.CENTER
                    contentDescription = "Przeciągnij, aby zmienić kolejność"
                    setOnTouchListener(RowDrag(tabRows, card) {
                        order.clear()
                        order.addAll((0 until tabRows.childCount).map { tabRows.getChildAt(it).tag as Tab })
                        saveTabs(order, hidden)
                    })
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(android.widget.ImageView(this@renderSettings).apply {
                    setImageResource(tabIcon(tab))
                    imageTintList = android.content.res.ColorStateList.valueOf(if (tab in hidden) MUTED else LINK)
                }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { leftMargin = dp(4) })
                addView(label(tabTitle(tab), 15f, if (tab in hidden) MUTED else INK, bold = true),
                    LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
                addView(android.widget.Switch(this@renderSettings).apply {
                    isChecked = tab !in hidden
                    isEnabled = tab != Tab.HOME
                    styleSwitch(this)
                    setOnCheckedChangeListener { _, visible ->
                        if (visible) hidden.remove(tab) else hidden.add(tab)
                        saveTabs(order, hidden)
                        renderSettings()
                    }
                })
            })
        })
    }
    content.addView(label("Przeciągnij za ≡, aby zmienić kolejność. Od góry = od lewej. Ostatnie są zawsze widoczne.", 12f, MUTED), margin(top = 2))

    section("O aplikacji")
    content.addView(label("f650gs.pl ${packageManager.getPackageInfo(packageName, 0).versionName}", 13f, MUTED), margin(bottom = 20))
}

/**
 * Touch handler for a row's drag handle: [row] follows the finger inside [list] and trades places
 * with each neighbour it passes halfway; [dropped] runs when the finger lifts.
 */
private class RowDrag(private val list: LinearLayout, private val row: View, private val dropped: () -> Unit) : View.OnTouchListener {
    private var lastY = 0f
    private var restingElevation = 0f

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouch(handle: View, event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                // Keep the settings page from scrolling while a row is held.
                handle.parent.requestDisallowInterceptTouchEvent(true)
                lastY = event.rawY
                row.animate().cancel()
                restingElevation = row.elevation
                row.elevation = restingElevation + 8 * row.resources.displayMetrics.density
                row.scaleX = 1.02f
                row.scaleY = 1.02f
                handle.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                row.translationY += event.rawY - lastY
                lastY = event.rawY
                // Positions are stale until the last swap is laid out.
                if (list.isLayoutRequested) return true
                val index = list.indexOfChild(row)
                val below = list.getChildAt(index + 1)
                val above = if (index > 0) list.getChildAt(index - 1) else null
                if (below != null && row.translationY > (below.bottom - row.bottom) / 2f) {
                    row.translationY -= below.bottom - row.bottom
                    below.translationY = (below.top - row.top).toFloat()
                    list.removeView(below)
                    list.addView(below, index)
                    below.animate().translationY(0f).setDuration(150).start()
                } else if (above != null && row.translationY < -(row.top - above.top) / 2f) {
                    row.translationY += row.top - above.top
                    above.translationY = -(row.bottom - above.bottom).toFloat()
                    list.removeView(above)
                    list.addView(above, index)
                    above.animate().translationY(0f).setDuration(150).start()
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                row.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(150)
                    .withEndAction { row.elevation = restingElevation }.start()
                dropped()
            }
        }
        return true
    }
}

/** The "Forum" tab: settings stored in the member's forum account, not on this phone. */
private fun MainActivity.renderForumSettings() {
    content.addView(label("Zapisywane na koncie forum; działają też na stronie w przeglądarce i w innych aplikacjach.", 12f, MUTED),
        margin(top = 6, bottom = 4))
    fun link(title: String, subtitle: String, open: () -> Unit) = content.addView(baseCard().apply {
        addView(row {
            val text = LinearLayout(this@renderForumSettings).apply { orientation = LinearLayout.VERTICAL }
            text.addView(label(title, 15f, INK, bold = true))
            text.addView(label(subtitle, 12f, MUTED), margin(top = 2))
            addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("›", 22f, MUTED))
        })
        setOnClickListener { open() }
    })
    link("Powiadomienia", "Co trafia do dzwonka i na e-mail: cytaty, wzmianki, reakcje, obserwowane tematy") {
        navigate(Screen.NotificationSettings)
    }
    link("Konto", "Nazwa użytkownika, adres e-mail, hasło") {
        openForumPage("http://f650gs.pl/settings/", "Ustawienia konta")
    }
    link("Ignorowani użytkownicy", "Czyich wpisów i wiadomości nie pokazywać") {
        openForumPage("http://f650gs.pl/ignore/", "Ignorowani użytkownicy")
    }
}

