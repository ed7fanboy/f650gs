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

internal fun MainActivity.buildChatComposer(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(12), dp(8), dp(12), dp(8))
    setBackgroundColor(CARD)
    visibility = View.GONE
    quoteChip = label("", 12f, LINK, bold = true).apply {
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = rounded(TEAL_LIGHT, 14)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        visibility = View.GONE
        setOnClickListener { setQuote(null) }
    }
    addView(quoteChip, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(6) })
    addView(composerRow(), LinearLayout.LayoutParams(-1, -2))
}

internal fun MainActivity.setQuote(post: ForumPost?) {
    if (editing != null) chatInput.setText("")
    editing = null
    quotedPost = post
    quoteChip.text = post?.let { "❝  Cytujesz: ${it.author}   ✕" }.orEmpty()
    quoteChip.visibility = if (post == null) View.GONE else View.VISIBLE
    if (post != null) {
        chatInput.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(chatInput, 0)
    }
}

internal fun MainActivity.composerRow(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    photoButton = label("📷", 20f, LINK).apply {
        gravity = Gravity.CENTER
        contentDescription = "Dodaj zdjęcie"
        background = rounded(TEAL_LIGHT, 22)
        visibility = View.GONE
        setOnClickListener { pickPhoto(chatInput) }
    }
    addView(photoButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(8) })
    chatInput = EditText(this@composerRow).apply {
        hint = "Napisz wiadomość…"
        textSize = 15f
        setTextColor(INK)
        setHintTextColor(MUTED)
        maxLines = 4
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = EditorInfo.IME_ACTION_SEND
        background = rounded(CANVAS, 22)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendChat(); true } else false
        }
    }
    addView(chatInput, LinearLayout.LayoutParams(0, -2, 1f))
    addView(label("➤", 20f, WHITE, bold = true).apply {
        gravity = Gravity.CENTER
        contentDescription = "Wyślij"
        background = rounded(if (darkMode) Color.rgb(47, 90, 120) else Color.rgb(0, 102, 177), 22)
        setOnClickListener { sendChat() }
    }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(8) })
}

internal fun MainActivity.loadChat() {
    main.removeCallbacks(chatPoll)
    fetch("Ładuję Chatbox…", { repo ->
        val (member, csrf) = repo.chatSession()
        Triple(member, csrf, repo.chatMessages(0, csrf))
    }) { (member, csrf, batch) ->
        chatMemberId = member
        chatCsrf = csrf
        chatMessages.clear()
        chatMessages.addAll(batch.messages)
        chatLastId = batch.lastId
        renderChat()
        main.postDelayed(chatPoll, 10_000)
    }
}

internal fun MainActivity.renderChat() {
    content.removeAllViews()
    content.addView(pill("Wcześniejsze wiadomości", TEAL_LIGHT, LINK) { loadOlderChat() }.apply {
        layoutParams = margin(bottom = 12)
    })
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    content.addView(list)
    chatList = list
    if (chatMessages.isEmpty()) content.addView(emptyCard("Cisza na Chatboxie", "Napisz pierwszą wiadomość."))
    var previous: ChatMessage? = null
    chatMessages.forEach { message -> list.addView(chatBubble(message, previous)); previous = message }
    scrollChatToEnd()
}

internal fun MainActivity.pollChat() {
    val csrf = chatCsrf
    val since = chatLastId
    io.execute {
        val batch = runCatching { repository.chatMessages(since, csrf) }.getOrNull() ?: return@execute
        main.post { appendChat(batch) }
    }
}

internal fun MainActivity.appendChat(batch: ChatBatch) {
    val fresh = batch.messages.filter { message -> chatMessages.none { it.id == message.id } }
    if (batch.lastId > chatLastId) chatLastId = batch.lastId
    if (fresh.isEmpty()) return
    val atEnd = scroll.getChildAt(0).bottom - (scroll.height + scroll.scrollY) < dp(80)
    val list = chatList
    fresh.forEach { message ->
        list?.addView(chatBubble(message, chatMessages.lastOrNull()))
        chatMessages.add(message)
    }
    if (atEnd) scrollChatToEnd()
}

internal fun MainActivity.loadOlderChat() {
    val first = chatMessages.firstOrNull()?.id ?: return
    val csrf = chatCsrf
    io.execute {
        val result = runCatching { repository.chatMessages(first, csrf, older = true) }
        main.post {
            if (currentScreen != Screen.Chatbox) return@post
            val older = result.getOrNull()?.messages.orEmpty().filter { message -> chatMessages.none { it.id == message.id } }
            if (older.isEmpty()) {
                android.widget.Toast.makeText(this, "Brak starszych wiadomości", android.widget.Toast.LENGTH_SHORT).show()
                return@post
            }
            chatMessages.addAll(0, older)
            val keepFrom = scroll.getChildAt(0).height - scroll.scrollY
            renderChat()
            scroll.post { scroll.scrollTo(0, scroll.getChildAt(0).height - keepFrom) }
        }
    }
}

internal fun MainActivity.sendChat() {
    if (currentScreen is Screen.Conversation) return sendPrivateMessage()
    if (currentScreen is Screen.Topic) return sendTopicReply()
    val text = chatInput.text.toString().trim()
    if (text.isBlank() || chatCsrf.isBlank()) return
    chatInput.isEnabled = false
    val csrf = chatCsrf
    val since = chatLastId
    io.execute {
        val result = runCatching {
            repository.sendChat(text, csrf)
            repository.chatMessages(since, csrf)
        }
        main.post {
            chatInput.isEnabled = true
            result.onSuccess { batch ->
                chatInput.setText("")
                appendChat(batch)
                scrollChatToEnd()
            }.onFailure {
                android.widget.Toast.makeText(this, it.localizedMessage ?: "Nie udało się wysłać wiadomości.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}

internal fun MainActivity.scrollChatToEnd() {
    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
}

internal fun MainActivity.chatBubble(message: ChatMessage, previous: ChatMessage?): View {
    val mine = chatMemberId.isNotBlank() && message.memberId == chatMemberId
    // Consecutive messages from the same person within 5 minutes are grouped under one name.
    val grouped = previous != null && previous.memberId == message.memberId && message.timestamp - previous.timestamp < 300
    val text = label(chatText(message.text), 15f, if (mine) WHITE else INK).apply {
        setTextIsSelectable(true)
        android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS)
    }
    return messageBubble(mine, grouped, message.author, text, chatTime(message.timestamp), message.photoUrl, message.memberId)
}

internal fun MainActivity.messageBubble(mine: Boolean, grouped: Boolean, author: String, body: TextView, time: String, photo: String = "", memberId: String = ""): View {
    val openProfile = View.OnClickListener {
        if (memberId.isNotBlank()) navigate(Screen.Profile(author, repository.memberUrl(memberId)))
    }
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = if (mine) Gravity.END else Gravity.START
    }
    if (!grouped && !mine) column.addView(label(author, 12f, avatarColors(author).second, bold = true).apply {
        setPadding(dp(12), 0, dp(12), dp(3))
        setOnClickListener(openProfile)
    })
    val bubbleColor = when {
        mine -> if (darkMode) Color.rgb(47, 90, 120) else Color.rgb(0, 102, 177)
        else -> CARD
    }
    val bubble = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(13), dp(8), dp(13), dp(7))
        background = rounded(bubbleColor, 18)
        elevation = if (mine) 0f else dp(1).toFloat()
    }
    bubble.addView(body)
    bubble.addView(label(time, 10f, if (mine) Color.rgb(209, 224, 235) else MUTED).apply {
        gravity = Gravity.END
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
    column.addView(bubble, LinearLayout.LayoutParams(-2, -2))
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = if (mine) Gravity.END else Gravity.START
        setPadding(if (mine) dp(48) else 0, if (grouped) dp(3) else dp(10), if (mine) 0 else dp(48), 0)
        if (!mine) {
            val badge = if (grouped) View(this@messageBubble) else initialBadge(author, photo).apply { setOnClickListener(openProfile) }
            addView(badge, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(8); gravity = Gravity.BOTTOM })
        }
        addView(column, LinearLayout.LayoutParams(-2, -2))
    }
}

internal fun MainActivity.renderMessages(items: List<Conversation>) {
    content.removeAllViews()
    content.addView(row {
        addView(label("Rozmowy", 17f, INK, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        addView(pill("✎  Nowa wiadomość", ACCENT_FILL, WHITE) {
            navigate(Screen.Compose())
        })
    }.apply { layoutParams = margin(top = 2, bottom = 12) })
    if (items.isEmpty()) content.addView(emptyCard("Brak rozmów", "Twoja skrzynka jest pusta."))
    items.forEach { item ->
        val card = baseCard()
        if (item.unread) card.background = rounded(if (darkMode) Color.rgb(34, 51, 54) else Color.rgb(233, 245, 241), 17)
        card.addView(row {
            // The list shows the starter's photo; when that is you, keep the other person's initial.
            val other = item.participants.removePrefix("Ty i ").removeSuffix(" i ty").ifBlank { item.title }
            val photo = if (item.photoOwner.equals(other, ignoreCase = true)) item.photo else ""
            addView(initialBadge(other, photo), LinearLayout.LayoutParams(dp(42), dp(42)))
            val text = LinearLayout(this@renderMessages).apply { orientation = LinearLayout.VERTICAL }
            if (item.unread) text.addView(label("●  NOWA", 10f, ACCENT_NEW, bold = true))
            text.addView(label(item.title, 15f, INK, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            if (item.participants.isNotBlank()) text.addView(label(item.participants, 12f, LINK, bold = true), margin(top = 2))
            if (stripSignatureText(item.snippet).isNotBlank()) text.addView(label(stripSignatureText(item.snippet), 13f, MUTED).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, margin(top = 4))
            addView(text, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
            val meta = LinearLayout(this@renderMessages).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
            if (item.count.isNotBlank()) meta.addView(label(item.count, 11f, WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = rounded(if (item.unread) ACCENT_NEW else MUTED, 10)
            })
            if (item.time.isNotBlank()) meta.addView(label(item.time.substringBefore(' '), 10f, MUTED), margin(top = 4))
            addView(meta, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        })
        card.setOnClickListener { navigate(Screen.Conversation(item.title, item.url)) }
        content.addView(card)
    }
}

internal fun MainActivity.renderConversation(page: ConversationPage) {
    content.removeAllViews()
    conversation = page
    if (page.olderUrl.isNotBlank()) content.addView(pill("Starsze wiadomości", TEAL_LIGHT, LINK) { loadOlderConversation() }.apply {
        layoutParams = margin(bottom = 12)
    })
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    content.addView(list)
    var previous: PrivateMessage? = null
    page.messages.forEach { message -> list.addView(privateBubble(page, message, previous)); previous = message }
    if (page.messages.isEmpty()) content.addView(emptyCard("Nie udało się odczytać rozmowy", "Otwórz ją na stronie forum."))
    if (page.replyForm == null) {
        chatComposer.visibility = View.GONE
        content.addView(openSiteButton("Odpowiedz na stronie forum") { openForumWeb(page.url, title = "Wiadomość") })
    }
    scrollChatToEnd()
}

internal fun MainActivity.privateBubble(page: ConversationPage, message: PrivateMessage, previous: PrivateMessage?): View {
    val mine = page.myMemberId.isNotBlank() && message.authorId == page.myMemberId
    return messageBubble(mine, previous?.authorId == message.authorId, message.author, htmlText(message.html, if (mine) WHITE else INK), message.date, message.photo, message.authorId)
}

internal fun MainActivity.loadOlderConversation() {
    val page = conversation ?: return
    io.execute {
        val result = runCatching { repository.conversation(page.olderUrl, newestPage = false) }
        main.post {
            if (conversation !== page) return@post
            result.onSuccess { older ->
                val keepFrom = scroll.getChildAt(0).height - scroll.scrollY
                renderConversation(page.copy(messages = older.messages + page.messages, olderUrl = older.olderUrl))
                scroll.post { scroll.scrollTo(0, scroll.getChildAt(0).height - keepFrom) }
            }.onFailure { showError(it) }
        }
    }
}

internal fun MainActivity.sendPrivateMessage() {
    val page = conversation ?: return
    val text = chatInput.text.toString().trim()
    if (text.isBlank() || !photosReady(chatInput)) return
    val body = postHtml(chatInput.text)
    chatInput.isEnabled = false
    io.execute {
        val result = runCatching {
            repository.sendReply(page.replyForm ?: error("Forum nie udostępnia formularza odpowiedzi."), body)
            repository.conversation(page.url)
        }
        main.post {
            chatInput.isEnabled = true
            result.onSuccess { updated ->
                chatInput.setText("")
                if (currentScreen is Screen.Conversation) renderConversation(updated)
            }.onFailure {
                android.widget.Toast.makeText(this, it.localizedMessage ?: "Nie udało się wysłać wiadomości.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}

internal val chatEmoticons = mapOf(
    ":)" to "🙂", ":-)" to "🙂", ";)" to "😉", ";-)" to "😉", ":D" to "😄", ":-D" to "😄",
    ":(" to "🙁", ":-(" to "🙁", ":P" to "😛", ":p" to "😛", ":-P" to "😛", ":o" to "😮", ":O" to "😮",
    "B)" to "😎", ":lol:" to "😆", ":default_cool:" to "😎", ":default_happy:" to "😊", ":default_angry:" to "😠",
    ":default_huh:" to "🤨", ":default_dry:" to "😑", ":default_blink:" to "😳", ":default_icon_beer:" to "🍺",
    ":default_icon_beer1:" to "🍻", ":default_icon_cry:" to "😢", ":default_icon_e_biggrin:" to "😁",
    ":default_icon_e_confused:" to "😕", ":default_icon_cool:" to "😎",
    // ASCII the app sends for emoji (Emoji.kt).
    ":cry:" to "😢", ":angry:" to "😠", ":huh:" to "🤨", ":wub:" to "😍", ":rolleyes:" to "🙄", "xD" to "😆",
    "(y)" to "👍", "(n)" to "👎", "(piwo)" to "🍺", "(piwa)" to "🍻", "(moto)" to "🏍️",
)

internal fun MainActivity.chatText(raw: String): String {
    @Suppress("DEPRECATION")
    val decoded = android.text.Html.fromHtml(raw).toString().trim()
    return decoded.split(' ').joinToString(" ") { word -> chatEmoticons[word] ?: word }
}

internal fun MainActivity.chatTime(seconds: Long): String {
    if (seconds <= 0) return ""
    val time = java.util.Calendar.getInstance().apply { timeInMillis = seconds * 1000 }
    val now = java.util.Calendar.getInstance()
    val clock = String.format(java.util.Locale.ROOT, "%d:%02d", time.get(java.util.Calendar.HOUR_OF_DAY), time.get(java.util.Calendar.MINUTE))
    val sameYear = time.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)
    val today = sameYear && time.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
    val months = arrayOf("sty", "lut", "mar", "kwi", "maj", "cze", "lip", "sie", "wrz", "paź", "lis", "gru")
    val date = "${time.get(java.util.Calendar.DAY_OF_MONTH)} ${months[time.get(java.util.Calendar.MONTH)]}" +
        if (sameYear) "" else " ${time.get(java.util.Calendar.YEAR)}"
    return if (today) "dziś $clock" else "$date, $clock"
}
