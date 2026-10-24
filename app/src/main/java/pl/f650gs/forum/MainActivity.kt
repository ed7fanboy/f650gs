package pl.f650gs.forum

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.net.Uri
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
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.window.OnBackInvokedCallback
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {

    companion object {
        const val EXTRA_SELECT_TAB = "select_tab"
        internal const val PREFS = "forum_client"
        internal const val SAVED_KEY = "saved_topics"
        internal const val SIGNED_IN_KEY = "signed_in"
        internal const val THEME_KEY = "theme_mode"
        internal const val HIDE_SIGNATURES_KEY = "hide_signatures"
        internal const val NAV_ORDER_KEY = "nav_order"
        internal const val NAV_HIDDEN_KEY = "nav_hidden"
        internal const val CHAT_SEEN_KEY = "chat_seen_id"
        internal const val NOTIFICATION_PERMISSION_REQUEST = 4103
        // Mobile-app footers appended to posts ("Wysłane z mojego SM-S931B przy użyciu Tapatalka" and the like).
        internal val SIGNATURE = Regex("""(?i)(wysłane|wysłano|sent)\s+(z|ze|from)\s[^\n]{0,80}?tapatalk\w*[.!]?|wytapatalkowano[^\n.!]{0,60}[.!]?""")
        internal const val WEB_ACTION_REQUEST = 4102
        internal const val STATE_SELECTED_TAB = "selected_tab"
        internal const val STATE_CURRENT_SCREEN = "current_screen"
        internal const val STATE_SCREEN_HISTORY = "screen_history"
    }

    internal var darkMode = false
    internal var themeMode = "system"
    internal var hideSignatures = true
    /** Which half of the settings screen is open: the app's own options or the forum account's. */
    internal var forumSettingsTab = false
    internal val INK get() = if (darkMode) Color.rgb(232, 239, 237) else Color.rgb(27, 43, 48)
    internal val MUTED get() = if (darkMode) Color.rgb(185, 200, 201) else Color.rgb(88, 108, 113)
    internal val TEAL get() = if (darkMode) Color.rgb(15, 39, 43) else Color.rgb(0, 102, 177)
    internal val LINK get() = if (darkMode) Color.rgb(166, 202, 224) else Color.rgb(0, 102, 177)
    internal val TEAL_LIGHT get() = if (darkMode) Color.rgb(38, 56, 59) else Color.rgb(234, 243, 251)
    internal val LIME = Color.rgb(196, 216, 46)
    internal val CANVAS get() = if (darkMode) Color.rgb(17, 25, 28) else Color.rgb(241, 245, 248)
    internal val CARD get() = if (darkMode) Color.rgb(29, 41, 45) else Color.WHITE
    internal val WHITE = Color.WHITE
    // Filled accent for selected controls and own message bubbles (TEAL is too dark to stand out in dark mode).
    internal val ACCENT_FILL get() = if (darkMode) Color.rgb(47, 90, 120) else Color.rgb(0, 102, 177)
    // Accent for anything new: unread markers, counts of new entries, unread conversations.
    internal val ACCENT_NEW get() = if (darkMode) Color.rgb(255, 166, 92) else Color.rgb(214, 98, 10)
    // Avatar colours (background to foreground), picked per name so people are easy to tell apart.
    internal val AVATAR_COLORS get() = if (darkMode) listOf(
        Color.rgb(38, 66, 92) to Color.rgb(140, 200, 250), Color.rgb(36, 72, 52) to Color.rgb(130, 214, 150),
        Color.rgb(84, 56, 30) to Color.rgb(255, 180, 110), Color.rgb(62, 46, 90) to Color.rgb(196, 164, 250),
        Color.rgb(84, 40, 48) to Color.rgb(250, 150, 160), Color.rgb(76, 70, 26) to Color.rgb(240, 214, 90),
        Color.rgb(28, 70, 72) to Color.rgb(110, 216, 214),
    ) else listOf(
        Color.rgb(222, 236, 252) to Color.rgb(0, 92, 170), Color.rgb(220, 243, 226) to Color.rgb(24, 120, 60),
        Color.rgb(255, 234, 214) to Color.rgb(176, 84, 0), Color.rgb(236, 228, 252) to Color.rgb(100, 60, 170),
        Color.rgb(252, 226, 230) to Color.rgb(170, 40, 60), Color.rgb(252, 244, 200) to Color.rgb(128, 100, 0),
        Color.rgb(216, 244, 242) to Color.rgb(0, 112, 110),
    )

    internal val repository = ForumRepository()
    internal lateinit var listCache: ListCache
    internal val io = Executors.newFixedThreadPool(2)
    internal val main = Handler(Looper.getMainLooper())
    internal val requestId = AtomicInteger()
    internal val history = ArrayDeque<Screen>()
    // The screen last drawn, and unsent composer text per topic, conversation and the Chatbox (kept while the app runs).
    internal var renderedScreen: Screen? = null
    internal val drafts = mutableMapOf<String, CharSequence>()
    internal val bookmarks = linkedMapOf<String, String>()
    internal var signedIn = false

    internal lateinit var appBarTitle: TextView
    internal lateinit var appBarSubtitle: TextView
    internal lateinit var accountButton: android.widget.ImageView
    internal lateinit var backButton: TextView
    internal lateinit var content: LinearLayout
    internal lateinit var progress: ProgressBar
    internal lateinit var bottomNav: LinearLayout
    internal lateinit var bottomNavScroll: HorizontalScrollView
    internal var currentScreen: Screen = Screen.Home
    internal var selectedTab = Tab.HOME
    internal var backInvokedCallback: OnBackInvokedCallback? = null
    internal lateinit var scroll: PullRefreshScrollView
    internal lateinit var chatComposer: LinearLayout
    internal lateinit var chatInput: EditText

    // Native Chatbox state; polled every 10 s (the forum's own interval) while the tab is open.
    internal val chatMessages = mutableListOf<ChatMessage>()
    internal var chatLastId = 0L
    internal var chatMemberId = ""
    internal var chatCsrf = ""
    internal var chatList: LinearLayout? = null
    internal var conversation: ConversationPage? = null
    internal var topicPage: TopicPage? = null
    internal var scrollToEndAfterLoad = false
    internal lateinit var bottomButton: TextView
    internal lateinit var newTopicButton: TextView
    internal lateinit var quoteChip: TextView
    internal lateinit var composerHeader: LinearLayout
    internal lateinit var expandButton: TextView
    internal var composerFull = false
    internal var keyboardUp = false
    internal var quotedPost: ForumPost? = null
    internal var editing: EditForm? = null
    // Quotes of the edited post, kept as they are before the text (photos are edited in place).
    internal var editPrefixHtml = ""
    internal var editingPostId = 0L
    // Photo button of the reply box (topics and private messages), and the text box a picked photo goes into.
    internal lateinit var photoButton: TextView
    internal lateinit var sendButton: TextView
    /** A post, message or Chatbox line is on its way; further taps on Wyślij are ignored. */
    internal var sending = false
    internal var photoTarget: EditText? = null
    internal var pickedPhotos: List<android.net.Uri> = emptyList()
    internal var awaitingShareLink = false
    internal lateinit var notificationsBadge: TextView
    internal lateinit var notificationsButton: View
    internal lateinit var pullIndicator: android.widget.ImageView
    internal var counters = Counters(0, 0, 0)
    internal var countersFetchedAt = 0L
    internal var chatUnread = false
    internal var resumed = false
    internal val chatPoll = object : Runnable {
        override fun run() {
            if (currentScreen == Screen.Chatbox && resumed && chatCsrf.isNotBlank()) pollChat()
            main.removeCallbacks(this)
            if (currentScreen == Screen.Chatbox) main.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeMode = getSharedPreferences(PREFS, MODE_PRIVATE).getString(THEME_KEY, "system") ?: "system"
        darkMode = when (themeMode) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        applySystemBarColors()
        loadBookmarks()
        listCache = ListCache(java.io.File(cacheDir, "lists"))
        Avatars.diskDir = java.io.File(cacheDir, "avatars")
        signedIn = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(SIGNED_IN_KEY, false)
        hideSignatures = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(HIDE_SIGNATURES_KEY, true)
        restoreNavigationState(savedInstanceState)
        restoreSessionIfValid()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CANVAS)
        }
        // Android 15+ draws the app behind the status bar and ignores statusBarColor, so the
        // strip under it is a view in the toolbar colour (white icons stay readable in light mode).
        val statusStrip = View(this).apply { setBackgroundColor(TEAL) }
        root.addView(statusStrip, LinearLayout.LayoutParams(-1, 0))
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, 0, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            @Suppress("DEPRECATION")
            statusStrip.layoutParams = statusStrip.layoutParams.apply { height = insets.systemWindowInsetTop }
            // The tab bar only takes room from the text while the keyboard is open.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && ::bottomNavScroll.isInitialized) {
                keyboardShown(insets.isVisible(android.view.WindowInsets.Type.ime()))
            }
            insets
        }
        // Before Android 11 the keyboard shows in no inset (the window just shrinks): it is open when
        // the visible part of the window ends well above the screen's bottom.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) root.viewTreeObserver.addOnGlobalLayoutListener {
            val visible = android.graphics.Rect().also(root::getWindowVisibleDisplayFrame)
            val typing = root.rootView.height - visible.bottom > dp(150)
            if (::bottomNavScroll.isInitialized && typing != (bottomNavScroll.visibility == View.GONE)) keyboardShown(typing)
        }
        root.addView(buildAppBar())

        val body = FrameLayout(this).apply { setBackgroundColor(CANVAS) }
        scroll = PullRefreshScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, 0, dp(12))
            threshold = dp(110).toFloat()
            onPull = { distance -> showPull(distance) }
            onRefresh = {
                if (currentScreen != Screen.Settings && currentScreen != Screen.Login) {
                    renderCurrent()
                    refreshCounters(force = true)
                }
            }
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(22))
        }
        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))
        body.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        progress = ProgressBar(this).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(LIME)
            visibility = View.GONE
        }
        body.addView(progress, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(12)
        })
        bottomButton = label("⤓", 24f, WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Przewiń na sam dół"
            background = rounded(if (darkMode) Color.rgb(47, 90, 120) else Color.rgb(0, 102, 177), 26)
            elevation = dp(6).toFloat()
            visibility = View.GONE
            setOnClickListener { scrollTopicToEnd() }
        }
        body.addView(bottomButton, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(16)
            bottomMargin = dp(16)
        })
        // Shown by a section screen once its page says you may start topics there.
        newTopicButton = label("+", 30f, WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Napisz nowy temat"
            background = rounded(ACCENT_FILL, 28)
            elevation = dp(6).toFloat()
            visibility = View.GONE
        }
        body.addView(newTopicButton, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(16)
            bottomMargin = dp(16)
        })
        pullIndicator = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_refresh)
            imageTintList = android.content.res.ColorStateList.valueOf(LINK)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            background = rounded(CARD, 22)
            elevation = dp(4).toFloat()
            alpha = 0f
        }
        body.addView(pullIndicator, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        chatComposer = buildChatComposer()
        root.addView(chatComposer)

        bottomNav = buildBottomNav()
        bottomNavScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(CARD)
            addView(bottomNav, ViewGroup.LayoutParams(-1, -2))
        }
        root.addView(bottomNavScroll)
        setContentView(root)
        root.requestApplyInsets()
        renderCurrent()
        if (savedInstanceState == null) handleTabIntent(intent)
        ForumCheckJob.sync(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback { if (composerFull) setComposerFull(false) else goBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback,
            )
            backInvokedCallback = callback
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTabIntent(intent)
    }

    /** Tab requests from system notifications ("notifications" opens the forum's notification list). */
    internal fun handleTabIntent(intent: Intent?) {
        if (intent?.action == ACTION_INSTALL_STATUS) return handleInstallStatus(intent)
        val request = intent?.getStringExtra(EXTRA_SELECT_TAB) ?: return
        if (request == "notifications") {
            navigate(Screen.Notifications)
            return
        }
        val tab = when (request) {
            "home" -> Tab.HOME
            "forums" -> Tab.FORUMS
            "unread" -> Tab.UNREAD
            "chatbox" -> Tab.CHATBOX
            "messages" -> Tab.MESSAGES
            "saved" -> Tab.SAVED
            else -> null
        }
        if (tab != null) openTab(tab)
    }

    internal fun buildAppBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(8), dp(4))
            setBackgroundColor(TEAL)
        }
        backButton = label("‹", 32f, LIME, bold = false).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setOnClickListener { goBack() }
            // Not a keyboard-focus target: when a text field closes its keyboard, focus would land here and grey it out.
            isFocusable = false
        }
        bar.addView(backButton, LinearLayout.LayoutParams(dp(38), dp(40)))

        val titleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        appBarTitle = label("F650GS.PL", 18f, WHITE, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        appBarSubtitle = label("FORUM SPOŁECZNOŚCI", 9f, Color.rgb(183, 203, 198), bold = true).apply {
            letterSpacing = 0.08f
        }
        titleBlock.addView(appBarTitle)
        titleBlock.addView(appBarSubtitle)
        bar.addView(titleBlock, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })

        bar.addView(action(R.drawable.ic_search, "Szukaj") { openSearch() }, LinearLayout.LayoutParams(dp(42), dp(42)))
        notificationsBadge = badgeView()
        notificationsButton = FrameLayout(this).apply {
            addView(action(R.drawable.ic_notifications, "Powiadomienia") {
                if (currentScreen != Screen.Notifications) navigate(Screen.Notifications)
            }, FrameLayout.LayoutParams(dp(42), dp(42)))
            addView(notificationsBadge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        }
        bar.addView(notificationsButton, LinearLayout.LayoutParams(dp(42), dp(42)))
        accountButton = action(R.drawable.ic_login, "Zaloguj się") {
            if (!signedIn) openLogin() else if (currentScreen != Screen.Account) navigate(Screen.Account)
        }
        // The photo is drawn as a circle inside the button's padding.
        accountButton.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                val inset = dp(7)
                outline.setOval(inset, inset, view.width - inset, view.height - inset)
            }
        }
        accountButton.setPadding(dp(7), dp(7), dp(7), dp(7))
        bar.addView(accountButton, LinearLayout.LayoutParams(dp(42), dp(42)))
        return bar
    }

    /** The tab bar only takes room from the text while the keyboard is open. */
    private fun keyboardShown(typing: Boolean) {
        keyboardUp = typing
        updateComposerHeader()
        bottomNavScroll.visibility = if (typing) View.GONE else View.VISIBLE
        bottomButton.visibility = if (!typing && currentScreen is Screen.Topic) View.VISIBLE else View.GONE
        if (typing && (currentScreen == Screen.Chatbox || currentScreen is Screen.Conversation)) scrollChatToEnd()
    }

    internal fun buildBottomNav(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(8), dp(4), dp(8))
        setBackgroundColor(CARD)
        elevation = dp(10).toFloat()
        populateNav(this)
    }

    internal fun tabIcon(tab: Tab) = when (tab) {
        Tab.HOME -> R.drawable.ic_nav_recent
        Tab.FORUMS -> R.drawable.ic_nav_forums
        Tab.UNREAD -> R.drawable.ic_nav_unread
        Tab.CHATBOX -> R.drawable.ic_nav_chat
        Tab.MESSAGES -> R.drawable.ic_nav_mail
        Tab.SAVED -> R.drawable.ic_nav_saved
    }

    internal fun tabTitle(tab: Tab) = when (tab) {
        Tab.HOME -> "Ostatnie"
        Tab.FORUMS -> "Fora"
        Tab.UNREAD -> "Nieprzecz."
        Tab.CHATBOX -> "Chatbox"
        Tab.MESSAGES -> "Wiadomości"
        Tab.SAVED -> "Zapisane"
    }

    /** All tabs in the user's order (new tabs appended); hidden ones are filtered by [visibleTabs]. */
    internal fun tabOrder(): List<Tab> {
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(NAV_ORDER_KEY, "").orEmpty()
            .split(',').mapNotNull { name -> Tab.entries.firstOrNull { it.name == name } }
        return saved + Tab.entries.filter { it !in saved }
    }

    internal fun hiddenTabs(): Set<Tab> = getSharedPreferences(PREFS, MODE_PRIVATE).getString(NAV_HIDDEN_KEY, "").orEmpty()
        .split(',').mapNotNull { name -> Tab.entries.firstOrNull { it.name == name } }.toSet() - Tab.HOME

    internal fun visibleTabs() = tabOrder().filter { it !in hiddenTabs() }

    internal fun saveTabs(order: List<Tab>, hidden: Set<Tab>) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(NAV_ORDER_KEY, order.joinToString(",") { it.name })
            .putString(NAV_HIDDEN_KEY, hidden.joinToString(",") { it.name })
            .apply()
        populateNav(bottomNav)
    }

    internal fun populateNav(nav: LinearLayout) {
        nav.removeAllViews()
        visibleTabs().forEach { tab -> nav.addView(navItem(tabIcon(tab), tabTitle(tab), tab), LinearLayout.LayoutParams(0, -2, 1f)) }
    }

    internal fun tabBadge(tab: Tab) = when (tab) {
        Tab.MESSAGES -> counters.inbox
        Tab.UNREAD -> counters.unreadTopics
        else -> 0
    }

    /** Remembers the newest Chatbox message shown in the app and drops the tab's dot. */
    internal fun markChatSeen(id: Long) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (id > prefs.getLong(CHAT_SEEN_KEY, 0)) prefs.edit().putLong(CHAT_SEEN_KEY, id).apply()
        if (chatUnread) {
            chatUnread = false
            populateNav(bottomNav)
        }
    }

    /** Small red count bubble; hidden at 0. */
    internal fun badgeView(count: Int = 0): TextView = label("", 10f, WHITE, bold = true).apply {
        gravity = Gravity.CENTER
        minWidth = dp(18)
        setPadding(dp(5), 0, dp(5), dp(1))
        background = rounded(Color.rgb(220, 50, 47), 9)
        setBadge(this, count)
    }

    internal fun setBadge(view: TextView, count: Int) {
        view.text = if (count > 99) "99+" else count.toString()
        view.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    /** Material 3 style: icon on a tonal pill when selected, label underneath. */
    internal fun navItem(icon: Int, title: String, tab: Tab): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = title
        val selected = selectedTab == tab
        val color = if (selected) LINK else MUTED
        // The bar sits in a scroll view, so tabs take their natural width: give each an equal share of
        // the screen (the label ends in "…" if longer) and narrow the 58 dp icon when six tabs do not fit.
        val slot = (resources.displayMetrics.widthPixels - dp(8)) / visibleTabs().size.coerceAtLeast(1)
        val iconWidth = minOf(dp(58), slot - dp(4))
        addView(FrameLayout(this@MainActivity).apply {
            addView(android.widget.ImageView(this@MainActivity).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(color)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                val side = ((iconWidth - dp(30)) / 2).coerceAtLeast(0)
                setPadding(side, dp(4), side, dp(4))
                if (selected) background = rounded(TEAL_LIGHT, 16)
            }, FrameLayout.LayoutParams(iconWidth, dp(34)))
            addView(badgeView(tabBadge(tab)), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { rightMargin = dp(4) })
            if (tab == Tab.CHATBOX && chatUnread) addView(View(this@MainActivity).apply {
                background = rounded(Color.rgb(220, 50, 47), 5)
            }, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.TOP or Gravity.END).apply { topMargin = dp(2); rightMargin = dp(8) })
        }, LinearLayout.LayoutParams(iconWidth, dp(34)))
        addView(label(title, 11f, color, bold = selected).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(slot, -2).apply { topMargin = dp(4) })
        setOnClickListener { openTab(tab) }
    }

    internal fun openTab(tab: Tab) {
        selectedTab = tab
        history.clear()
        currentScreen = when (tab) {
            Tab.HOME -> Screen.Home
            Tab.FORUMS -> Screen.Forums
            Tab.SAVED -> Screen.Saved
            Tab.UNREAD -> Screen.Unread
            Tab.CHATBOX -> Screen.Chatbox
            Tab.MESSAGES -> Screen.Messages
        }
        updateChrome()
        renderCurrent()
    }

    // Through navigate(), so pending loads, the Chatbox composer and polling stop like on any other screen change.
    internal fun openSearch() = navigate(Screen.Search())

    internal fun navigate(screen: Screen) {
        history.addLast(currentScreen)
        currentScreen = screen
        updateChrome()
        renderCurrent()
    }

    internal fun goBack() {
        if (history.isNotEmpty()) {
            currentScreen = history.removeLast()
            updateChrome()
            renderCurrent()
        } else if (currentScreen is Screen.Topic || currentScreen is Screen.Conversation || currentScreen is Screen.Profile ||
            currentScreen is Screen.Compose || currentScreen is Screen.NewTopic) {
            // Keep a sensible list destination if Android recreated the activity and
            // the in-memory history stack was unavailable.
            currentScreen = when (selectedTab) {
                Tab.HOME -> Screen.Home
                Tab.FORUMS -> Screen.Forums
                Tab.SAVED -> Screen.Saved
                Tab.UNREAD -> Screen.Unread
                Tab.CHATBOX -> Screen.Chatbox
                Tab.MESSAGES -> Screen.Messages
            }
            updateChrome()
            renderCurrent()
        } else {
            openTab(Tab.HOME)
        }
    }

    internal fun restoreNavigationState(state: Bundle?) {
        if (state == null) return
        selectedTab = runCatching { Tab.valueOf(state.getString(STATE_SELECTED_TAB).orEmpty()) }
            .getOrDefault(Tab.HOME)
        decodeScreen(state.getString(STATE_CURRENT_SCREEN))?.let { currentScreen = it }
        history.clear()
        runCatching { org.json.JSONArray(state.getString(STATE_SCREEN_HISTORY).orEmpty()) }
            .getOrNull()?.let { stack ->
                for (index in 0 until stack.length()) {
                    decodeScreen(stack.optJSONObject(index)?.toString())?.let(history::addLast)
                }
            }
    }

    internal fun encodeScreen(screen: Screen): org.json.JSONObject = org.json.JSONObject().apply {
        when (screen) {
            Screen.Home -> put("type", "home")
            Screen.Forums -> put("type", "forums")
            Screen.Saved -> put("type", "saved")
            Screen.Unread -> put("type", "unread")
            Screen.Chatbox -> put("type", "chatbox")
            Screen.Messages -> put("type", "messages")
            is Screen.Search -> put("type", "search").put("query", screen.query)
            is Screen.Forum -> put("type", "forum").put("title", screen.title).put("url", screen.url)
            is Screen.Topic -> put("type", "topic").put("title", screen.title).put("url", screen.url)
            is Screen.Conversation -> put("type", "conversation").put("title", screen.title).put("url", screen.url)
            is Screen.Profile -> put("type", "profile").put("title", screen.name).put("url", screen.url)
            is Screen.Compose -> put("type", "compose").put("id", screen.memberId).put("title", screen.name).put("photo", screen.photo)
            is Screen.NewTopic -> put("type", "newtopic").put("title", screen.forum).put("url", screen.url)
            Screen.Settings -> put("type", "settings")
            Screen.Login -> put("type", "login")
            Screen.Notifications -> put("type", "notifications")
            Screen.SignUp -> put("type", "signup")
            Screen.NotificationSettings -> put("type", "notificationsettings")
            Screen.Account -> put("type", "account")
            is Screen.ForumPage -> put("type", "forumpage").put("title", screen.title).put("url", screen.url)
        }
    }

    internal fun decodeScreen(json: String?): Screen? = runCatching {
        val data = org.json.JSONObject(json.orEmpty())
        when (data.optString("type")) {
            "home" -> Screen.Home
            "forums" -> Screen.Forums
            "saved" -> Screen.Saved
            "unread" -> Screen.Unread
            "chatbox" -> Screen.Chatbox
            "messages" -> Screen.Messages
            "search" -> Screen.Search(data.optString("query"))
            "forum" -> Screen.Forum(data.optString("title"), data.optString("url"))
            "topic" -> Screen.Topic(data.optString("title"), data.optString("url"))
            "conversation" -> Screen.Conversation(data.optString("title"), data.optString("url"))
            "profile" -> Screen.Profile(data.optString("title"), data.optString("url"))
            "compose" -> Screen.Compose(data.optString("id"), data.optString("title"), data.optString("photo"))
            "newtopic" -> Screen.NewTopic(data.optString("title"), data.optString("url"))
            "settings" -> Screen.Settings
            "login" -> Screen.Login
            "notifications" -> Screen.Notifications
            "signup" -> Screen.SignUp
            "notificationsettings" -> Screen.NotificationSettings
            "account" -> Screen.Account
            "forumpage" -> Screen.ForumPage(data.optString("title"), data.optString("url"))
            else -> null
        }
    }.getOrNull()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SELECTED_TAB, selectedTab.name)
        outState.putString(STATE_CURRENT_SCREEN, encodeScreen(currentScreen).toString())
        val stack = org.json.JSONArray()
        history.forEach { stack.put(encodeScreen(it)) }
        outState.putString(STATE_SCREEN_HISTORY, stack.toString())
    }

    internal fun updateChrome() {
        val detail = currentScreen is Screen.Forum || currentScreen is Screen.Topic || currentScreen is Screen.Search ||
            currentScreen is Screen.Conversation || currentScreen is Screen.Profile || currentScreen is Screen.Compose ||
            currentScreen is Screen.NewTopic || currentScreen == Screen.Settings || currentScreen == Screen.Login ||
            currentScreen == Screen.Notifications || currentScreen == Screen.SignUp || currentScreen == Screen.NotificationSettings ||
            currentScreen is Screen.ForumPage || currentScreen == Screen.Account
        backButton.visibility = if (detail) View.VISIBLE else View.GONE
        appBarTitle.text = when (val screen = currentScreen) {
            Screen.Home -> "F650GS.PL"
            Screen.Forums -> "Fora"
            Screen.Saved -> "Zapisane"
            Screen.Unread -> "Nieprzeczytane"
            Screen.Chatbox -> "Chatbox"
            Screen.Messages -> "Wiadomości"
            is Screen.Search -> "Szukaj"
            is Screen.Forum -> screen.title
            is Screen.Topic -> screen.title
            is Screen.Conversation -> screen.title
            is Screen.Profile -> screen.name.ifBlank { "Profil" }
            is Screen.Compose -> "Nowa wiadomość"
            is Screen.NewTopic -> "Nowy temat"
            Screen.Settings -> "Ustawienia"
            Screen.Login -> "Logowanie"
            Screen.Notifications -> "Powiadomienia"
            Screen.SignUp -> "Rejestracja"
            Screen.NotificationSettings -> "Ustawienia powiadomień"
            is Screen.ForumPage -> screen.title.ifBlank { "Forum" }
            Screen.Account -> "Konto"
        }
        appBarSubtitle.text = when (currentScreen) {
            Screen.Home -> "FORUM SPOŁECZNOŚCI"
            Screen.Forums -> "WYBIERZ DZIAŁ"
            Screen.Saved -> "TWOJE TEMATY"
            Screen.Unread -> "TEMATY DO NADROBIENIA"
            Screen.Chatbox -> "ROZMOWY NA ŻYWO"
            Screen.Messages -> "PRYWATNE ROZMOWY"
            is Screen.Search -> "SZUKAJ W FORUM"
            is Screen.Forum -> "TEMATY W DZIALE"
            is Screen.Topic -> "DYSKUSJA"
            is Screen.Conversation -> "ROZMOWA"
            is Screen.Profile -> "PROFIL"
            is Screen.Compose -> "NOWA WIADOMOŚĆ"
            is Screen.NewTopic -> "NOWY TEMAT"
            Screen.Settings -> "USTAWIENIA"
            Screen.Login -> "LOGOWANIE"
            Screen.Notifications -> "POWIADOMIENIA"
            Screen.SignUp -> "REJESTRACJA"
            Screen.NotificationSettings -> "POWIADOMIENIA"
            is Screen.ForumPage -> "FORUM"
            Screen.Account -> "KONTO"
        }
        appBarSubtitle.visibility = if (detail) View.GONE else View.VISIBLE
        // Signed in: the member's photo (lime account icon until it is known); signed out: a plain "log in" arrow.
        val photo = if (signedIn) getSharedPreferences(PREFS, MODE_PRIVATE).getString(ACCOUNT_PHOTO_KEY, "").orEmpty() else ""
        accountButton.tag = null
        accountButton.setImageResource(if (signedIn) R.drawable.ic_account else R.drawable.ic_login)
        accountButton.imageTintList = android.content.res.ColorStateList.valueOf(if (signedIn) LIME else WHITE)
        accountButton.scaleType = android.widget.ImageView.ScaleType.CENTER
        accountButton.clipToOutline = false
        if (photo.isNotBlank()) {
            accountButton.imageTintList = null
            accountButton.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            accountButton.clipToOutline = true
            Avatars.load(photo, accountButton)
        }
        accountButton.contentDescription = if (signedIn) "Konto i wylogowanie" else "Zaloguj się"
        notificationsButton.visibility = if (signedIn) View.VISIBLE else View.GONE
        bottomNav.removeAllViews()
        populateNav(bottomNav)
        bottomNavScroll.post { bottomNavScroll.scrollTo(0, 0) }
    }

    /** Which composer draft a screen uses; pages of one topic share it. */
    internal fun draftKey(screen: Screen): String? = when (screen) {
        is Screen.Topic -> "topic:" + topicPath(screen.url)
        is Screen.Conversation -> "conversation:" + runCatching { java.net.URI(screen.url).path }.getOrDefault(screen.url)
        Screen.Chatbox -> "chatbox"
        else -> null
    }

    /** Keeps the composer's text as the draft for [key], in memory and on disk. */
    private fun keepDraft(key: String) {
        if (chatInput.text.isBlank()) drafts.remove(key) else drafts[key] = android.text.SpannableStringBuilder(chatInput.text)
        persistDraft(key, drafts[key])
    }

    private fun switchDraft(from: String?, to: String?) {
        if (from == to) return
        // An unfinished edit of a post is not a draft of a new reply.
        if (editing != null) setQuote(null)
        if (from != null) keepDraft(from)
        chatInput.setText(to?.let { key -> drafts[key] ?: storedDraft(key)?.also { drafts[key] = it } } ?: "")
        chatInput.setSelection(chatInput.text.length)
    }

    internal fun renderCurrent() {
        // A page still loading for the previous screen must not draw over this one.
        requestId.incrementAndGet()
        progress.visibility = View.GONE
        if (currentScreen != renderedScreen) {
            // Another screen (also on Back) starts at the top. The scroll view takes the focus first: when the
            // composer hides, focus would otherwise jump to a view in the page and scroll down to it.
            scroll.isFocusableInTouchMode = true
            if (android.os.Build.VERSION.SDK_INT >= 26) scroll.defaultFocusHighlightEnabled = false
            scroll.requestFocus()
            scroll.scrollTo(0, 0)
            setComposerFull(false)
            switchDraft(renderedScreen?.let(::draftKey), draftKey(currentScreen))
            renderedScreen = currentScreen
        }
        updateChrome()
        chatList = null
        conversation = null
        val composing = signedIn && (currentScreen == Screen.Chatbox || currentScreen is Screen.Conversation)
        chatComposer.visibility = if (composing) View.VISIBLE else View.GONE
        photoButton.visibility = if (currentScreen is Screen.Topic || currentScreen is Screen.Conversation) View.VISIBLE else View.GONE
        if (currentScreen !is Screen.Topic) setQuote(null)
        chatInput.hint = when (currentScreen) {
            is Screen.Conversation -> "Napisz odpowiedź…"
            is Screen.Topic -> "Odpowiedz w temacie…"
            else -> "Napisz wiadomość…"
        }
        bottomButton.visibility = if (currentScreen is Screen.Topic) View.VISIBLE else View.GONE
        newTopicButton.visibility = View.GONE
        if (currentScreen != Screen.Chatbox) main.removeCallbacks(chatPoll)
        when (val screen = currentScreen) {
            Screen.Home -> fetchCached("home", ListCache.STORIES, "Ładuję najnowsze wpisy…", { it.home(signedIn) }, ::renderHome)
            Screen.Forums -> fetchCached("forums", ListCache.CATEGORIES, "Ładuję działy…", { it.categories() }, ::renderForums)
            Screen.Saved -> renderSaved()
            Screen.Unread -> if (signedIn) {
                fetchCached("unread", ListCache.STORIES, "Ładuję nieprzeczytane tematy…", { it.unread() }, ::renderUnread)
            } else renderUnreadSignIn()
            Screen.Chatbox -> if (signedIn) loadChat() else renderForumFeature("Chatbox", "Rozmowy na żywo społeczności F650GS.", "Zaloguj się") {
                openLogin()
            }
            Screen.Messages -> if (signedIn) {
                fetchCached("messages", ListCache.CONVERSATIONS, "Ładuję wiadomości…", { it.conversations() }, ::renderMessages)
            } else renderForumFeature("Wiadomości prywatne", "Twoja skrzynka i rozmowy z innymi członkami forum.", "Zaloguj się") {
                openLogin()
            }
            is Screen.Conversation -> fetch("Ładuję rozmowę…", { it.conversation(screen.url) }, ::renderConversation)
            is Screen.Profile -> fetch("Ładuję profil…", { it.profile(screen.url) }, ::renderProfile)
            is Screen.Compose -> renderCompose(screen)
            is Screen.NewTopic -> renderNewTopic(screen)
            Screen.Settings -> renderSettings()
            Screen.Login -> renderLogin()
            Screen.Notifications -> if (signedIn) {
                fetch("Ładuję powiadomienia…", { it.notifications() }, ::renderNotifications)
            } else renderForumFeature("Powiadomienia", "Cytaty, wzmianki i reakcje na Twoje wpisy.", "Zaloguj się") { openLogin() }
            Screen.SignUp -> fetch("Ładuję formularz rejestracji…", { it.signUpForm() }, ::renderSignUp)
            Screen.NotificationSettings -> fetch("Ładuję ustawienia powiadomień…", { it.notificationSettings() }, ::renderNotificationSettings)
            is Screen.ForumPage -> loadForumPage(screen)
            Screen.Account -> if (signedIn) loadAccount() else renderLogin()
            is Screen.Search -> renderSearch(screen.query, emptyList())
            is Screen.Forum -> fetch("Ładuję tematy…", { it.forum(screen.url) }) { renderForum(screen, it) }
            is Screen.Topic -> fetch("Ładuję dyskusję…", { it.topic(screen.url) }, ::renderTopic)
        }
    }

    internal fun <T> fetch(message: String, loader: (ForumRepository) -> T, success: (T) -> Unit) {
        val request = requestId.incrementAndGet()
        repository.updateSessionCookies(CookieManager.getInstance().getCookie("http://f650gs.pl/"))
        content.removeAllViews()
        content.addView(sectionLabel(message))
        progress.visibility = View.VISIBLE
        io.execute {
            val result = runCatching { loader(repository) }
            main.post {
                if (request != requestId.get()) return@post
                progress.visibility = View.GONE
                result.onSuccess(success).onFailure { showError(it) }
            }
        }
    }

    /**
     * Shows the saved copy of a list at once and loads the fresh one in the background; the
     * screen is redrawn only when something changed, at the same scroll position.
     */
    internal fun <T> fetchCached(key: String, codec: ListCache.Codec<T>, message: String, loader: (ForumRepository) -> T, success: (T) -> Unit) {
        val saved = listCache.get(key, codec)
            ?: return fetch(message, { repository -> loader(repository).also { listCache.put(key, codec, it) } }, success)
        val request = requestId.incrementAndGet()
        repository.updateSessionCookies(CookieManager.getInstance().getCookie("http://f650gs.pl/"))
        success(saved)
        progress.visibility = View.VISIBLE
        io.execute {
            val result = runCatching { loader(repository).also { listCache.put(key, codec, it) } }
            main.post {
                if (request != requestId.get()) return@post
                progress.visibility = View.GONE
                result.onSuccess { fresh ->
                    if (fresh == saved) return@onSuccess
                    val y = scroll.scrollY
                    success(fresh)
                    scroll.post { scroll.scrollTo(0, y) }
                }.onFailure {
                    android.widget.Toast.makeText(this, "Brak połączenia z forum — widzisz ostatnio zapisaną wersję.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (currentScreen == Screen.Chatbox && chatCsrf.isNotBlank()) chatPoll.run()
        refreshCounters()
        checkForUpdate()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The clipboard is readable only while the app has focus; that's when the album link is picked up.
        if (hasFocus) takeAlbumLinkFromClipboard()
    }

    override fun onPause() {
        resumed = false
        // The app may be closed from here on: save what is being written (not an edit of an existing post).
        if (editing == null) renderedScreen?.let(::draftKey)?.let(::keepDraft)
        super.onPause()
    }

    internal inner class PostContentHeightBridge(
        private val webView: WebView,
        private val posts: MutableList<ForumPost>,
        private val onLaidOut: () -> Unit = {},
    ) {
        @JavascriptInterface
        fun scrollToCss(cssTop: Float) {
            main.post {
                webView.post {
                    // The page's delayed focus calls can still arrive after the topic was left.
                    if (!webView.isAttachedToWindow) return@post
                    val y = content.top + webView.top + (cssTop * resources.displayMetrics.density).toInt() - dp(8)
                    scroll.smoothScrollTo(0, y.coerceAtLeast(0))
                }
            }
        }

        @JavascriptInterface
        fun edit(index: Int) {
            main.post { posts.getOrNull(index)?.let(::startEdit) }
        }

        @JavascriptInterface
        fun quote(index: Int) {
            main.post { posts.getOrNull(index)?.let(::setQuote) }
        }

        @JavascriptInterface
        fun toggleLike(index: Int) {
            main.post {
                val post = posts.getOrNull(index) ?: return@post
                val url = if (post.likedByMe) post.unlikeUrl else post.likeUrl
                repository.updateSessionCookies(CookieManager.getInstance().getCookie("http://f650gs.pl/"))
                io.execute {
                    val result = runCatching { repository.react(url) }
                    main.post {
                        val updated = if (result.isSuccess) post.copy(
                            likedByMe = !post.likedByMe,
                            likeCount = (post.likeCount + if (post.likedByMe) -1 else 1).coerceAtLeast(0),
                        ) else post
                        posts[index] = updated
                        val label = JSONObject.quote(likeLabel(updated))
                        webView.evaluateJavascript(
                            "(function(){var b=document.getElementById('like$index');if(!b)return;b.disabled=false;" +
                                "b.classList.toggle('liked',${updated.likedByMe});b.querySelector('.likeLabel').textContent=$label})()",
                            null,
                        )
                        result.onFailure {
                            android.widget.Toast.makeText(this@MainActivity, "Nie udało się zmienić polubienia", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        @JavascriptInterface
        fun update(cssHeight: Float) {
            main.post {
                val params = webView.layoutParams ?: return@post
                params.height = dp(kotlin.math.ceil(cssHeight).toInt().coerceAtLeast(1))
                webView.layoutParams = params
                onLaidOut()
            }
        }

        @JavascriptInterface
        fun openImage(url: String) = openGallery(org.json.JSONArray(listOf(url)).toString(), 0)

        /** The post's photos ([urlsJson], a JSON array) in the preview, starting at [index]. */
        @JavascriptInterface
        fun openGallery(urlsJson: String, index: Int) {
            main.post {
                val urls = runCatching { org.json.JSONArray(urlsJson) }.getOrNull()?.let { array -> (0 until array.length()).map(array::optString) }
                    ?.filter { Uri.parse(it).scheme in setOf("http", "https") }.orEmpty()
                val start = urls.getOrNull(index) ?: return@post
                startActivity(Intent(this@MainActivity, ImagePreviewActivity::class.java)
                    .putExtra(ImagePreviewActivity.EXTRA_URL, start)
                    .putStringArrayListExtra(ImagePreviewActivity.EXTRA_URLS, ArrayList(urls))
                    .putExtra(ImagePreviewActivity.EXTRA_INDEX, index)
                    .putExtra(ImagePreviewActivity.EXTRA_DARK_MODE, darkMode)
                    .putExtra(ImagePreviewActivity.EXTRA_COOKIES, CookieManager.getInstance().getCookie("http://f650gs.pl/")))
            }
        }
    }

    @Deprecated("Activity result used for API 24 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (handlePhotoResult(requestCode, resultCode, data)) return
        if (requestCode != WEB_ACTION_REQUEST || resultCode != RESULT_OK) return
        signedIn = data?.getBooleanExtra(ForumWebActivity.EXTRA_SIGNED_IN, false) ?: false
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(SIGNED_IN_KEY, signedIn).apply()
        repository.updateSessionCookies(CookieManager.getInstance().getCookie("http://f650gs.pl/"))
        updateChrome()
        renderCurrent()
    }

    internal fun showPull(distance: Float) {
        val travel = (distance / 2f).coerceAtMost(dp(80).toFloat())
        pullIndicator.alpha = (distance / scroll.threshold).coerceIn(0f, 1f)
        pullIndicator.translationY = travel - dp(30)
        pullIndicator.rotation = distance
        pullIndicator.imageTintList = android.content.res.ColorStateList.valueOf(if (distance >= scroll.threshold) ACCENT_NEW else LINK)
    }

    /** Header counts for tab badges; at most once a minute unless [force]d. */
    internal fun refreshCounters(force: Boolean = false) {
        if (!signedIn) return
        val now = System.currentTimeMillis()
        if (!force && now - countersFetchedAt < 60_000) return
        countersFetchedAt = now
        repository.updateSessionCookies(CookieManager.getInstance().getCookie("http://f650gs.pl/"))
        val chatSeen = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(CHAT_SEEN_KEY, 0)
        io.execute {
            val result = runCatching { repository.counters(chatSeen) }.getOrNull() ?: return@execute
            main.post {
                counters = result
                // First check ever: take the current Chatbox as seen rather than flag 100 old messages.
                if (chatSeen == 0L) markChatSeen(result.chat.maxOfOrNull { it.id } ?: 0)
                else chatUnread = currentScreen != Screen.Chatbox && chatHasNews(result.chat, chatSeen, accountName())
                // Counts seen in the app should not come back as system notifications.
                ForumCheckJob.markSeen(this, result)
                setBadge(notificationsBadge, result.notifications)
                populateNav(bottomNav)
                if (getSharedPreferences(PREFS, MODE_PRIVATE).getString(ACCOUNT_NAME_KEY, "").isNullOrBlank()) io.execute {
                    val account = runCatching { repository.account() }.getOrNull()
                    if (account != null) main.post { rememberAccount(account) }
                }
            }
        }
    }

    internal fun applySystemBarColors() {
        window.statusBarColor = TEAL
        window.navigationBarColor = CANVAS
        window.decorView.systemUiVisibility = if (darkMode) 0 else
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    @Deprecated("Deprecated in Android; retained for API 24 back navigation")
    override fun onBackPressed() {
        if (composerFull) return setComposerFull(false)
        goBack()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backInvokedCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        requestId.incrementAndGet()
        main.removeCallbacks(chatPoll)
        io.shutdownNow()
        super.onDestroy()
    }
}
