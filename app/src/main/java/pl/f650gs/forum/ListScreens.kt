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

internal fun MainActivity.renderHome(items: List<ActivityStory>) {
    // One card per topic, in order of its latest activity. The card shows the topic's earliest
    // entry in the stream (the first one you have not seen, as far as the stream tells), with
    // the later ones summarised; tapping opens the forum's first unread post.
    val topics = items.groupBy { topicPath(it.url) }.values
    content.removeAllViews()
    content.addView(hero("Ostatnie z forum", "Wątki i odpowiedzi od społeczności F650GS"))
    content.addView(sectionHeading("Najnowsza aktywność", "${topics.size} tematów"))
    if (items.isEmpty()) content.addView(emptyCard("Brak nowych wpisów", "Wróć później albo przejrzyj działy forum."))
    val me = accountName()
    topics.forEach { stories ->
        // Your own entries are not news to you: preview the earliest one by someone else.
        val shown = stories.lastOrNull { me.isBlank() || !it.author.equals(me, ignoreCase = true) } ?: stories.last()
        content.addView(activityCard(shown, stories - shown, firstUnreadUrl(stories.first().url), othersAreNewer = true))
    }
    content.addView(openSiteButton("Otwórz pełne forum") { openBrowser("http://f650gs.pl/") })
}

internal fun MainActivity.renderUnread(items: List<ActivityStory>) {
    val topics = items.distinctBy { topicPath(it.url) }
    content.removeAllViews()
    content.addView(hero("Nieprzeczytane tematy", "Wpisy z aktywności forum, których jeszcze nie widziałeś."))
    content.addView(sectionHeading("Do nadrobienia", "${topics.size} tematów"))
    if (topics.isEmpty()) content.addView(emptyCard("Wszystko przeczytane", "Gdy pojawią się nowe wpisy, znajdziesz je tutaj."))
    topics.forEach { story -> content.addView(activityCard(story, openUrl = firstUnreadUrl(story.url))) }
}

/** Groups entries by topic: its id, so links to page 2 or to a single post land in the same group. */
internal fun MainActivity.topicPath(url: String): String =
    Regex("""/topic/(\d+)""").find(url)?.value ?: runCatching { java.net.URI(url).path }.getOrNull() ?: url

/** The forum redirects `?do=getNewComment` to the first unread post (or the last post when all are read). */
internal fun MainActivity.firstUnreadUrl(url: String): String = runCatching {
    val uri = java.net.URI(url)
    "${uri.scheme}://${uri.host}${uri.rawPath}?do=getNewComment"
}.getOrDefault(url)

internal fun MainActivity.renderUnreadSignIn() {
    content.removeAllViews()
    content.addView(hero("Nieprzeczytane tematy", "Osobisty strumień pokazuje wpisy od ostatniej wizyty."))
    content.addView(emptyCard("Zaloguj się, aby zobaczyć swoje nieprzeczytane", "Ta lista pochodzi z konta forum i synchronizuje się z jego stanem przeczytania."))
    content.addView(openSiteButton("Zaloguj się") {
        openLogin()
    })
}

internal fun MainActivity.renderForumFeature(title: String, description: String, action: String, open: () -> Unit) {
    content.removeAllViews()
    content.addView(hero(title, description))
    content.addView(emptyCard("Działa przez konto forum", "Zaloguj się na f650gs.pl, aby korzystać z tej funkcji. Uprawnienia zależą od ustawień forum."))
    content.addView(openSiteButton(action, open))
}

internal fun MainActivity.renderForums(categories: List<ForumCategory>) {
    content.removeAllViews()
    content.addView(hero("Znajdź swój dział", "Porady, serwis i rozmowy o motocyklach BMW."))
    categories.forEach { category ->
        content.addView(sectionHeading(category.name))
        category.forums.forEach { forum -> content.addView(forumCard(forum)) }
    }
    content.addView(openSiteButton("Przeglądaj całe forum") { openBrowser("http://f650gs.pl/") })
}

internal fun MainActivity.renderNewTopic(screen: Screen.NewTopic) {
    content.removeAllViews()
    if (!signedIn) return renderForumFeature("Nowy temat", "Zaloguj się, aby zakładać tematy.", "Zaloguj się") { openLogin() }
    val card = baseCard().apply { setPadding(dp(18), dp(18), dp(18), dp(18)) }
    card.addView(label("DZIAŁ", 11f, LINK, bold = true))
    card.addView(label(screen.forum, 16f, INK, bold = true), margin(top = 6))
    val title = formField("Tytuł tematu", 1)
    val body = formField("Treść pierwszego wpisu", 8)
    card.addView(label("TYTUŁ", 11f, LINK, bold = true), margin(top = 16))
    card.addView(title, margin(top = 6))
    card.addView(label("WPIS", 11f, LINK, bold = true), margin(top = 16))
    card.addView(body, margin(top = 6))
    card.addView(pill("📷  Dodaj zdjęcie", TEAL_LIGHT, LINK) { pickPhoto(body) }.apply {
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) }
    })
    val error = label("", 13f, Color.rgb(220, 50, 47), bold = true).apply { visibility = View.GONE }
    card.addView(error, margin(top = 8))
    val submit = pill("Opublikuj temat", ACCENT_FILL, WHITE) {}
    card.addView(submit, margin(top = 10))
    content.addView(card)
    title.requestFocus()

    var form: NewTopicForm? = null
    fun showError(message: String) { error.text = message; error.visibility = View.VISIBLE }
    // Loads the forum's form up front, which also confirms you may post in this section.
    val request = requestId.get()
    io.execute {
        val result = runCatching { repository.newTopicForm(screen.url) }
        main.post {
            if (request != requestId.get() || currentScreen !is Screen.NewTopic) return@post
            result.onSuccess { form = it }.onFailure { showError(it.localizedMessage ?: "Nie możesz założyć tematu w tym dziale.") }
        }
    }
    submit.setOnClickListener {
        val topic = form
        val subject = title.text.toString().trim()
        val text = body.text.toString().trim()
        when {
            topic == null -> return@setOnClickListener showError(if (error.visibility == View.VISIBLE) error.text.toString() else "Formularz jeszcze się ładuje.")
            subject.isBlank() -> return@setOnClickListener showError("Wpisz tytuł.")
            text.isBlank() -> return@setOnClickListener showError("Wpisz treść wpisu albo dodaj zdjęcie.")
            !photosReady(body) -> return@setOnClickListener
        }
        hideKeyboard(body)
        submit.isEnabled = false
        submit.text = "Publikuję…"
        error.visibility = View.GONE
        val html = postHtml(body.text)
        io.execute {
            val result = runCatching { repository.createTopic(topic!!, subject, html) }
            main.post {
                result.onSuccess { url ->
                    if (currentScreen !is Screen.NewTopic) return@post
                    // Replace the form with the new topic, so Back returns to the section.
                    currentScreen = Screen.Topic(subject, url)
                    renderCurrent()
                }.onFailure {
                    submit.isEnabled = true
                    submit.text = "Opublikuj temat"
                    showError(it.localizedMessage ?: "Nie udało się opublikować tematu.")
                }
            }
        }
    }
}

internal fun MainActivity.renderForum(screen: Screen.Forum, page: ForumPage) {
    content.removeAllViews()
    // Sections opened from a link have no title yet: take it from the loaded page.
    if (screen.title.isBlank() && page.title.isNotBlank()) { currentScreen = screen.copy(title = page.title); updateChrome() }
    newTopicButton.visibility = if (signedIn && page.canPost) View.VISIBLE else View.GONE
    newTopicButton.setOnClickListener { navigate(Screen.NewTopic(page.title.ifBlank { screen.title }, screen.url)) }
    // Pages and sort orders replace the section in place, without a back step each.
    fun openPage(url: String) { currentScreen = Screen.Forum(page.title.ifBlank { screen.title }, url); renderCurrent() }
    if (page.subforums.isNotEmpty()) {
        content.addView(sectionHeading("Poddziały"))
        page.subforums.forEach { forum -> content.addView(forumCard(forum)) }
    }
    if (page.topics.isEmpty() && page.subforums.isNotEmpty()) return
    content.addView(sectionHeading(page.title, page.pager.label.ifBlank { "${page.topics.size} tematów" }))
    if (page.sorts.isNotEmpty()) content.addView(HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(row {
            page.sorts.forEachIndexed { index, sort ->
                addView(pill(sort.label, if (sort.selected) ACCENT_FILL else TEAL_LIGHT, if (sort.selected) WHITE else LINK) {
                    if (!sort.selected) openPage(sort.url)
                }, LinearLayout.LayoutParams(-2, -2).apply { if (index > 0) leftMargin = dp(8) })
            }
        })
        layoutParams = margin(bottom = 12)
    })
    content.addView(pagerRow(page.pager, ::openPage))
    if (page.markReadUrl.isNotBlank()) {
        content.addView(openSiteButton("✓  Oznacz dział jako przeczytany") {
            markForumRead(page.markReadUrl)
        })
    }
    if (page.topics.isEmpty()) content.addView(emptyCard("Nie znaleziono tematów", "Ten dział nie ma jeszcze tematów."))
    page.topics.forEach { topic -> content.addView(topicCard(topic)) }
    content.addView(pagerRow(page.pager, ::openPage))
    // Room so the floating "+" does not cover the last card.
    if (newTopicButton.visibility == View.VISIBLE) content.addView(View(this), LinearLayout.LayoutParams(-1, dp(64)))
}

internal fun MainActivity.renderSaved() {
    content.removeAllViews()
    content.addView(hero("Zapisane tematy", "Lista jest przechowywana tylko na tym urządzeniu."))
    if (bookmarks.isEmpty()) {
        content.addView(emptyCard("Tu pojawią się zapisane wątki", "Dotknij gwiazdki przy temacie, żeby zachować go na później."))
        return
    }
    bookmarks.forEach { (url, title) ->
        content.addView(savedCard(title, url))
    }
}

internal fun MainActivity.renderSearch(query: String, results: List<ActivityStory>) {
    content.removeAllViews()
    content.addView(searchBox(query))
    if (query.isBlank()) {
        content.addView(sectionHeading("Szukaj w dyskusjach"))
        content.addView(emptyCard("Wpisz słowo lub frazę", "Wyniki pochodzą z publicznego wyszukiwania forum."))
    } else {
        // One card per topic; it opens at the best-matching (first listed) post.
        val topics = results.groupBy { topicPath(it.url) }.values
        content.addView(sectionHeading("Wyniki", "${topics.size} tematów"))
        if (results.isEmpty()) content.addView(emptyCard("Brak wyników", "Spróbuj innej frazy."))
        topics.forEach { hits -> content.addView(activityCard(hits.first(), hits.drop(1))) }
    }
}

internal fun MainActivity.submitSearch(query: String) {
    val clean = query.trim()
    if (clean.isBlank()) return
    currentScreen = Screen.Search(clean)
    updateChrome()
    fetch("Szukam: $clean…", { it.search(clean) }) { renderSearch(clean, it) }
}

internal fun MainActivity.searchBox(query: String): View = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    val input = EditText(this@searchBox).apply {
        setSingleLine(true)
        hint = "Np. ABS, olej, Dakar…"
        setText(query)
        textSize = 15f
        setTextColor(INK)
        setHintTextColor(MUTED)
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        background = rounded(CARD, 16)
        setPadding(dp(14), dp(9), dp(14), dp(9))
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(this)
                submitSearch(text.toString())
                true
            } else false
        }
    }
    addView(input, LinearLayout.LayoutParams(0, dp(52), 1f))
    // A fresh search starts in the field with the keyboard open.
    if (query.isBlank()) input.post {
        input.requestFocus()
        (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.showSoftInput(input, 0)
    }
    val search = pill("Szukaj", ACCENT_FILL, WHITE) {
        hideKeyboard(input)
        submitSearch(input.text.toString())
    }
    addView(search, LinearLayout.LayoutParams(-2, dp(48)).apply { leftMargin = dp(8) })
}

/** [others] are the topic's other entries in the list: earlier ones, or later ones when [othersAreNewer]. */
internal fun MainActivity.activityCard(
    item: ActivityStory,
    others: List<ActivityStory> = emptyList(),
    openUrl: String = item.url,
    othersAreNewer: Boolean = false,
): View {
    val card = baseCard()
    card.addView(label(item.title.ifBlank { "Temat forum" }, 18f, INK, bold = true))
    card.addView(row {
        val author = row {
            addView(initialBadge(item.author, item.photo), LinearLayout.LayoutParams(dp(42), dp(42)))
            val heading = LinearLayout(this@activityCard).apply { orientation = LinearLayout.VERTICAL }
            heading.addView(label(item.author.ifBlank { "Forum" }, 13f, avatarColors(item.author).second, bold = true))
            heading.addView(label(item.time.ifBlank { "Ostatnia aktywność" }, 11f, MUTED))
            addView(heading, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
            if (item.authorUrl.isNotBlank() && currentScreen !is Screen.Profile) setOnClickListener { navigate(Screen.Profile(item.author, item.authorUrl)) }
        }
        addView(author, LinearLayout.LayoutParams(0, -2, 1f))
        addView(starButton(item.url, item.title))
    }, margin(top = 8))
    if (item.context.isNotBlank()) card.addView(label(item.context, 12f, MUTED), margin(top = 5))
    val snippet = stripSignatureText(item.snippet)
    if (snippet.isNotBlank()) card.addView(label(snippet.take(180), 14f, INK), margin(top = 9))
    // Filled in when the post turns out to have pictures (the snippet never shows them).
    val images = row { visibility = View.GONE }
    card.addView(images, margin(top = 9))
    markPostImages(images, item.url, lookUp = currentScreen == Screen.Home || currentScreen == Screen.Unread)
    if (others.isNotEmpty()) {
        val names = others.map { it.author }.filter { it.isNotBlank() }.distinct().take(3).joinToString(", ")
        val count = others.size
        val few = count % 10 in 2..4 && count % 100 !in 12..14
        val more = when {
            currentScreen is Screen.Search -> when {
                count == 1 -> "+1 trafienie w tym temacie"
                few -> "+$count trafienia w tym temacie"
                else -> "+$count trafień w tym temacie"
            }
            othersAreNewer -> when { count == 1 -> "+1 nowszy wpis"; few -> "+$count nowsze wpisy"; else -> "+$count nowszych wpisów" }
            else -> when { count == 1 -> "+1 wcześniejszy wpis"; few -> "+$count wcześniejsze wpisy"; else -> "+$count wcześniejszych wpisów" }
        }
        card.addView(label(if (names.isBlank()) more else "$more · $names", 12f, ACCENT_NEW, bold = true), margin(top = 9))
    }
    if (item.replies.isNotBlank()) card.addView(label(item.replies, 11f, MUTED, bold = true), margin(top = 10))
    card.setOnClickListener { navigate(Screen.Topic(item.title, openUrl)) }
    return card
}

internal fun MainActivity.forumCard(forum: ForumSummary): View {
    val card = baseCard()
    card.addView(row {
        addView(initialBadge(forum.title), LinearLayout.LayoutParams(dp(42), dp(42)))
        val titleColumn = LinearLayout(this@forumCard).apply { orientation = LinearLayout.VERTICAL }
        titleColumn.addView(label(forum.title, 16f, INK, bold = true))
        if (forum.description.isNotBlank()) titleColumn.addView(label(forum.description, 12f, MUTED), margin(top = 3))
        addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        addView(label("›", 24f, LINK, bold = true))
    })
    if (forum.latestTopic.isNotBlank()) {
        card.addView(label("◷  ${forum.latestTopic}", 13f, LINK, bold = true), margin(top = 12))
        val meta = listOf(forum.latestAuthor, forum.latestTime, forum.postCount.takeIf { it.isNotBlank() }?.let { "$it postów" })
            .filterNotNull().filter { it.isNotBlank() }.joinToString("  ·  ")
        if (meta.isNotBlank()) card.addView(label(meta, 11f, MUTED), margin(top = 4))
    }
    if (forum.markReadUrl.isNotBlank()) {
        card.addView(pill("✓  Oznacz dział jako przeczytany", TEAL_LIGHT, TEAL) {
            markForumRead(forum.markReadUrl)
        }, margin(top = 10))
    }
    card.setOnClickListener { navigate(Screen.Forum(forum.title, forum.url)) }
    return card
}

internal fun MainActivity.markForumRead(url: String) {
    fetch("Oznaczam dział jako przeczytany…", { it.markForumRead(url) }) {
        android.widget.Toast.makeText(this, "Dział oznaczony jako przeczytany", android.widget.Toast.LENGTH_SHORT).show()
        renderCurrent()
    }
}

internal fun MainActivity.topicCard(topic: TopicSummary): View {
    val card = baseCard()
    if (topic.isUnread) {
        val unreadCard = if (darkMode) Color.rgb(34, 51, 54) else Color.rgb(233, 245, 241)
        card.background = rounded(unreadCard, 17)
    }
    card.addView(row {
        addView(initialBadge(topic.author.ifBlank { topic.title }), LinearLayout.LayoutParams(dp(42), dp(42)))
        val text = LinearLayout(this@topicCard).apply { orientation = LinearLayout.VERTICAL }
        if (topic.isUnread) {
            text.addView(label("●  NIEPRZECZYTANY", 10f, ACCENT_NEW, bold = true))
        }
        text.addView(label(topic.title, if (topic.isUnread) 16f else 15f, INK, bold = true), if (topic.isUnread) margin(top = 3) else LinearLayout.LayoutParams(-2, -2))
        val meta = listOf(topic.author, topic.date).filter { it.isNotBlank() }.joinToString("  ·  ")
        if (meta.isNotBlank()) text.addView(label(meta, 11f, MUTED), margin(top = 4))
        addView(text, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        addView(starButton(topic.url, topic.title))
    })
    if (topic.replies.isNotBlank()) card.addView(label("↩  ${topic.replies} odpowiedzi", 11f, LINK, bold = true), margin(top = 10))
    card.setOnClickListener { navigate(Screen.Topic(topic.title, topic.url)) }
    return card
}

internal fun MainActivity.postCard(number: Int, post: ForumPost): View = baseCard().apply {
    addView(row {
        addView(initialBadge(post.author, post.photo), LinearLayout.LayoutParams(dp(38), dp(38)))
        val meta = LinearLayout(this@postCard).apply { orientation = LinearLayout.VERTICAL }
        meta.addView(label(post.author, 14f, INK, bold = true))
        meta.addView(label(post.date.ifBlank { "Wpis #$number" }, 11f, MUTED), margin(top = 2))
        addView(meta, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        addView(label("#$number", 11f, MUTED, bold = true))
    })
    addView(label(post.text, 14f, INK), margin(top = 12))
}

internal fun MainActivity.savedCard(title: String, url: String): View = baseCard().apply {
    addView(row {
        addView(label("☆", 22f, LINK, bold = true))
        addView(label(title, 15f, INK, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        addView(label("×", 20f, MUTED).apply {
            setOnClickListener { bookmarks.remove(url); persistBookmarks(); renderSaved() }
        })
    })
    setOnClickListener { navigate(Screen.Topic(title, url)) }
}

internal fun MainActivity.starButton(url: String, title: String): TextView = label(if (bookmarks.containsKey(url)) "★" else "☆", 23f, LINK).apply {
    gravity = Gravity.CENTER
    contentDescription = if (bookmarks.containsKey(url)) "Usuń z zapisanych" else "Zapisz temat"
    setOnClickListener {
        if (bookmarks.containsKey(url)) bookmarks.remove(url) else bookmarks[url] = title
        persistBookmarks()
        text = if (bookmarks.containsKey(url)) "★" else "☆"
    }
}

internal fun MainActivity.loadBookmarks() {
    val json = getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(SAVED_KEY, null) ?: return
    runCatching {
        val saved = JSONObject(json)
        saved.keys().forEach { key -> bookmarks[key] = saved.optString(key) }
    }
}

internal fun MainActivity.persistBookmarks() {
    val json = JSONObject()
    bookmarks.forEach { (url, title) -> json.put(url, title) }
    getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString(SAVED_KEY, json.toString()).apply()
}
