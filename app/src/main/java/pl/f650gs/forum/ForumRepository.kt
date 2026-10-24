package pl.f650gs.forum

import org.jsoup.Jsoup
import pl.f650gs.forum.ForumParser.clean
import pl.f650gs.forum.ForumParser.safeForumUrl

/** Talks to the forum over HTTP with the app's session cookies; [ForumParser] reads the pages. */
class ForumRepository {
    @Volatile private var sessionCookies: Map<String, String> = emptyMap()

    fun updateSessionCookies(cookieHeader: String?) {
        sessionCookies = cookieHeader.orEmpty().split(';')
            .mapNotNull { part ->
                val split = part.trim().split('=', limit = 2)
                if (split.size == 2 && split[0].isNotBlank()) split[0] to split[1] else null
            }.toMap()
    }

    fun activity(): List<ActivityStory> = ForumParser.activity(page("/discover/"))

    fun unread(): List<ActivityStory> = ForumParser.activity(page("/discover/unread/"))

    /** The activity stream; signed in, with the posts you have not read marked. */
    fun home(signedIn: Boolean): List<ActivityStory> {
        val stories = activity()
        return if (signedIn) markUnread(stories, runCatching { unread() }.getOrDefault(emptyList())) else stories
    }

    fun categories(): List<ForumCategory> = ForumParser.categories(page("/"))

    fun forum(url: String): ForumPage = ForumParser.forum(page(safeForumUrl(url)))

    fun markForumRead(actionUrl: String) {
        require(actionUrl.isNotBlank()) { "Forum nie udostępnia akcji oznaczenia jako przeczytane." }
        page(safeForumUrl(actionUrl))
    }

    /**
     * Opens a topic page. Redirects are followed by hand so the `#comment-ID` target of
     * `?do=getNewComment` (first unread post) or `?do=findComment` is not lost.
     */
    fun topic(url: String): TopicPage {
        var location = safeForumUrl(url)
        var focus = 0L
        var response: org.jsoup.Connection.Response? = null
        for (hop in 0 until 6) {
            val current = connect(location).followRedirects(false).ignoreContentType(true).execute()
            Regex("""comment[-=](\d+)""").find(location)?.let { focus = it.groupValues[1].toLong() }
            val next = current.header("Location")
            if (current.statusCode() !in 300..399 || next.isNullOrBlank()) { response = current; break }
            location = safeForumUrl(java.net.URI(location).resolve(next).toString())
        }
        val document = (response ?: connect(location).execute()).parse()
        return ForumParser.topic(document, location.substringBefore('#'), focus)
    }

    /** Null when the forum does not consider the session signed in. */
    fun account(): Account? = ForumParser.account(page("/"))

    fun profile(url: String): MemberProfile = ForumParser.profile(page(safeForumUrl(url)), url)

    /** Profile address for a member id; the forum redirects it to the page with the member's name in it. */
    fun memberUrl(id: String): String = "http://f650gs.pl/index.php?app=core&module=members&controller=profile&id=$id"

    /** Members whose name starts with [query] (the forum needs at least 3 characters). */
    fun findMembers(query: String): List<MemberHit> {
        val encoded = java.net.URLEncoder.encode(query, Charsets.UTF_8.name())
        return ForumParser.members(ajax("/index.php?app=core&module=system&controller=ajax&do=findMember&input=$encoded").execute().body())
    }

    /** The forum's "new conversation" form addressed to member [memberId]. */
    fun composeForm(memberId: String): ComposeForm = ForumParser.composeForm(page("/messenger/compose/?to=$memberId"))

    /** Starts a conversation and returns its address. */
    fun startConversation(compose: ComposeForm, title: String, text: String): String {
        val response = connect(compose.form.action)
            .method(org.jsoup.Connection.Method.POST)
            .data(compose.form.fields)
            .data("messenger_to", compose.recipient)
            .data("messenger_title", forumSafeText(title).trim())
            .data(compose.form.editor, paragraphsHtml(text))
            .execute()
        val url = response.url().toString()
        if (Regex("""/messenger/\d+""").containsMatchIn(url)) return safeForumUrl(url)
        error(ForumParser.formError(response.parse(), "Forum nie przyjęło wiadomości."))
    }

    /** The "new topic" form of the section at [forumUrl]. */
    fun newTopicForm(forumUrl: String): NewTopicForm {
        return ForumParser.newTopicForm(page(forumUrl.substringBefore('?').trimEnd('/') + "/?do=add"))
    }

    /** Starts a topic and returns its address. */
    fun createTopic(form: NewTopicForm, title: String, bodyHtml: String): String {
        val response = connect(form.form.action)
            .method(org.jsoup.Connection.Method.POST)
            .data(form.form.fields)
            .data(form.titleField, forumSafeText(title).trim())
            .data(form.form.editor, bodyHtml)
            .execute()
        val url = response.url().toString()
        if (Regex("""/topic/\d+""").containsMatchIn(url)) return safeForumUrl(url)
        error(ForumParser.formError(response.parse(), "Forum nie przyjęło tematu."))
    }

    fun logout(url: String) {
        require(url.contains("logout")) { "Nieprawidłowy adres wylogowania." }
        connect(url).ignoreContentType(true).execute()
    }

    /** The forum's edit form for a post, or null when this account may not edit it. */
    fun editForm(url: String): EditForm? {
        require(url.contains("do=editComment")) { "Nieprawidłowy adres edycji." }
        return ForumParser.editForm(page(url))
    }

    /** Header counts; with [chatSince] >= 0 also the Chatbox messages after that id (0 = the latest 100). */
    fun counters(chatSince: Long = -1): Counters {
        val document = page("/discover/unread/")
        val counters = ForumParser.counters(document)
        if (chatSince < 0) return counters
        val csrf = ForumParser.chatSession(document.html()).second ?: return counters
        val chat = runCatching { chatMessages(chatSince, csrf).messages }.getOrDefault(emptyList())
        return counters.copy(chat = chat)
    }

    /**
     * Signs in with the forum's own login form and returns the session cookies
     * (to be handed to the WebView). Throws with the forum's message on failure.
     */
    fun login(user: String, password: String, remember: Boolean): Map<String, String> {
        val form = connect("/login/").execute()
        val cookies = form.cookies().toMutableMap()
        val csrf = ForumParser.loginCsrf(form.parse()) ?: error("Forum nie udostępnia formularza logowania.")
        val response = Jsoup.connect("http://f650gs.pl/login/")
            .userAgent("F650GSForum/0.9 (Android; native reader)")
            .timeout(15_000)
            .cookies(cookies)
            .followRedirects(false)
            .ignoreContentType(true)
            .method(org.jsoup.Connection.Method.POST)
            .data("csrfKey", csrf)
            .data("auth", user)
            .data("password", password)
            .apply { if (remember) data("remember_me", "1") }
            .data("_processLogin", "usernamepassword")
            .execute()
        cookies.putAll(response.cookies())
        val member = cookies.entries.firstOrNull { it.key.endsWith("member_id") }?.value?.toLongOrNull() ?: 0
        if (member <= 0) {
            val message = runCatching { ForumParser.pageErrors(response.parse(), ".ipsMessage_error, .ipsType_warning") }.getOrNull()
            error(message?.ifBlank { null } ?: "Nieprawidłowa nazwa użytkownika lub hasło.")
        }
        sessionCookies = cookies
        return cookies
    }

    fun notifications(url: String = "/notifications/"): NotificationsPage = ForumParser.notifications(page(url))

    fun notificationSettings(): SettingsForm =
        ForumParser.notificationSettings(page("/notifications/options/")) ?: error("Forum nie udostępnia ustawień powiadomień.")

    /** Saves the settings with [on] switched on and every other switch off. */
    fun saveNotificationSettings(form: SettingsForm, on: List<FormToggle>) {
        val response = connect(form.action)
            .method(org.jsoup.Connection.Method.POST)
            .data(form.hidden)
            .apply { on.forEach { data(it.name, it.value) } }
            .execute()
        val problem = ForumParser.pageErrors(response.parse())
        if (problem.isNotBlank()) error(problem)
    }

    /** The page at [url] drawn natively, or null when the app should show the web page instead. */
    fun nativePage(url: String): NativePage? {
        val response = connect(url).execute()
        return ForumParser.nativePage(response.parse(), safeForumUrl(response.url().toString()))?.withCookies(response.cookies())
    }

    /** Sends [form] with [values] (unticked boxes left out) and reads the page the forum answers with. */
    fun submitForm(form: ForumForm, values: List<Pair<String, String>>): FormOutcome {
        val response = connect(form.action)
            .cookies(form.cookies)
            .method(org.jsoup.Connection.Method.POST)
            .data(form.hidden)
            .apply { values.forEach { (name, value) -> data(name, value) } }
            .execute()
        val cookies = response.cookies()
        // A new password or e-mail renews the session cookies.
        if (sessionCookies.isNotEmpty() && cookies.isNotEmpty()) sessionCookies = sessionCookies + cookies
        val document = response.parse()
        val failed = document.selectFirst("#ipsLayout_mainArea .ipsMessage_error, .ipsFieldRow_error") != null
        val page = ForumParser.nativePage(document, safeForumUrl(response.url().toString()))?.withCookies(form.cookies + cookies)
        return FormOutcome(page, saved = !failed, cookies = cookies)
    }

    /**
     * A one-tap forum action link (it carries its own csrfKey), e.g. "stop ignoring". The app has
     * already asked the member, so the forum's own "are you sure?" form is sent as well.
     */
    fun forumAction(url: String) {
        require(url.contains("csrfKey=")) { "Nieprawidłowy adres akcji." }
        val document = connect(url).ignoreContentType(true).execute().parse()
        val confirm = ForumParser.confirmForm(document) ?: return
        val answer = connect(confirm.action)
            .method(org.jsoup.Connection.Method.POST)
            .data(confirm.fields)
            .execute()
            .parse()
        if (ForumParser.confirmForm(answer) != null) error("Forum nie potwierdziło akcji.")
    }

    private fun NativePage.withCookies(cookies: Map<String, String>) = copy(blocks = blocks.map { block ->
        if (block is PageBlock.Form) PageBlock.Form(block.form.copy(cookies = cookies)) else block
    })

    /** The sign-up form, read as a guest so its csrfKey matches the cookies it comes with. */
    fun signUpForm(): SignUpForm {
        val response = Jsoup.connect("http://f650gs.pl/register/")
            .userAgent("F650GSForum/0.7 (Android; native reader)")
            .header("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.5")
            .timeout(15_000)
            .execute()
        val form = ForumParser.signUpForm(response.parse()) ?: error("Forum nie udostępnia formularza rejestracji.")
        return form.copy(cookies = response.cookies())
    }

    /** Sends the sign-up form with the member's [values] (field name to value; unticked boxes left out). */
    fun signUp(form: SignUpForm, values: Map<String, String>): SignUpResult {
        val response = Jsoup.connect(form.action)
            .userAgent("F650GSForum/0.7 (Android; native reader)")
            .header("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.5")
            .timeout(30_000)
            .cookies(form.cookies)
            .method(org.jsoup.Connection.Method.POST)
            .data(form.hidden)
            .data(values)
            .execute()
        val cookies = form.cookies + response.cookies()
        val document = response.parse()
        ForumParser.signUpForm(document)?.let { again ->
            val silent = again.error.isBlank() && again.fields.none { it.error.isNotBlank() }
            return SignUpResult.Retry(again.copy(cookies = cookies, error = if (silent) "Forum nie przyjęło formularza." else again.error))
        }
        val member = cookies.entries.firstOrNull { it.key.endsWith("member_id") }?.value?.toLongOrNull() ?: 0
        if (member > 0) sessionCookies = cookies
        val message = document.selectFirst("#ipsLayout_mainArea")?.select(".ipsType_pageTitle, .ipsType_normal, .ipsMessage, p")
            ?.eachText()?.distinct()?.joinToString("\n\n").orEmpty().ifBlank { "Konto zostało założone." }
        return SignUpResult.Done(message, cookies, member > 0)
    }

    fun conversations(): List<Conversation> = ForumParser.conversations(page("/messenger/"))

    /** A conversation, opened on its newest page; [ConversationPage.olderUrl] points at the page before. */
    fun conversation(url: String, newestPage: Boolean = true): ConversationPage {
        var document = page(safeForumUrl(url))
        if (newestPage) ForumParser.conversationNewestUrl(document)?.let { document = page(it) }
        return ForumParser.conversation(document, url)
    }

    /** Posts [bodyHtml] (after [prefixHtml] and an optional quoted post) through the forum's own comment form, as its editor would. */
    fun sendReply(form: ReplyForm, bodyHtml: String, quote: ForumPost? = null, prefixHtml: String = "") {
        val html = prefixHtml + (quote?.let { quoteHtml(form, it) } ?: "") + bodyHtml
        val response = connect(form.action)
            .method(org.jsoup.Connection.Method.POST)
            .data(form.fields)
            .data(form.editor, html)
            .execute()
        // Administrators' header notices are not errors; taking them for a failed reply made the same post go out twice.
        val problem = ForumParser.pageErrors(response.parse())
        if (problem.isNotBlank()) error(problem)
    }

    /** The same blockquote markup the forum editor produces when you press "Cytuj". */
    private fun quoteHtml(form: ReplyForm, post: ForumPost): String {
        val contents = Jsoup.parseBodyFragment(post.html).apply { select("blockquote").remove() }.body().html()
        val name = android.text.Html.escapeHtml(post.author)
        return "<blockquote class=\"ipsQuote\" data-ipsquote=\"\" data-ipsquote-contentapp=\"forums\" " +
            "data-ipsquote-contenttype=\"forums\" data-ipsquote-contentclass=\"forums_Topic\" " +
            "data-ipsquote-contentid=\"${form.contentId}\" data-ipsquote-contentcommentid=\"${post.id}\" " +
            "data-ipsquote-username=\"$name\"><div class=\"ipsQuote_citation\">$name napisał:</div>" +
            "<div class=\"ipsQuote_contents\">$contents</div></blockquote>"
    }

    /** Sends a reaction (or its removal) the same way the forum's own JavaScript does. */
    fun react(actionUrl: String) {
        require(actionUrl.contains("do=reactComment") || actionUrl.contains("do=unreactComment")) { "Nieprawidłowa akcja polubienia." }
        ajax(actionUrl).execute()
    }

    /**
     * Chatbox messages via the bimchatbox AJAX API: newer than [lastId] (0 = the latest 100), or,
     * with [older], the batch before message [lastId]. Messages come oldest first.
     */
    fun chatMessages(lastId: Long, csrfKey: String, older: Boolean = false): ChatBatch {
        val body = ajax("/?app=bimchatbox&module=chatbox&controller=chatbox&do=getMessages&lastID=$lastId&loadMoreMode=${if (older) 1 else 0}&csrfKey=$csrfKey")
            .execute().body()
        return ForumParser.chatBatch(body, lastId)
    }

    /** The signed-in member id and CSRF key, read from the Chatbox page. */
    fun chatSession(): Pair<String, String> {
        val (member, csrf) = ForumParser.chatSession(page("/chatbox/").html())
        return member to (csrf ?: error("Zaloguj się, aby pisać w Chatboxie."))
    }

    fun sendChat(text: String, csrfKey: String) {
        // The forum's script sends encodeURIComponent(escapeHtml(text)) as the form value.
        val escaped = android.text.Html.escapeHtml(forumSafeText(text))
        val encoded = java.net.URLEncoder.encode(escaped, Charsets.UTF_8.name())
            .replace("+", "%20").replace("%21", "!").replace("%27", "'")
            .replace("%28", "(").replace("%29", ")").replace("%7E", "~")
        val body = ajax("/?app=bimchatbox&module=chatbox&controller=chatbox&do=chat")
            .method(org.jsoup.Connection.Method.POST)
            .data("txt", encoded)
            .data("csrfKey", csrfKey)
            .execute().body()
        val json = runCatching { org.json.JSONObject(body) }.getOrNull()
        if (json?.optString("type") == "error") error(json.optString("message").ifBlank { "Nie udało się wysłać wiadomości." })
    }

    fun search(query: String): List<ActivityStory> {
        // Spaces as %20: the forum finds nothing for a multi-word query sent with "+" between the words.
        val encoded = java.net.URLEncoder.encode(query, Charsets.UTF_8.name()).replace("+", "%20")
        return ForumParser.search(page("/search/?q=$encoded"))
    }

    private fun connect(pathOrUrl: String) = Jsoup.connect(safeForumUrl(pathOrUrl))
        .userAgent("F650GSForum/0.7 (Android; native reader)")
        .header("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.5")
        .timeout(15_000)
        .apply { if (sessionCookies.isNotEmpty()) cookies(sessionCookies) }

    private fun page(pathOrUrl: String) = connect(pathOrUrl).get()








    private fun ajax(pathOrUrl: String) = Jsoup.connect(safeForumUrl(pathOrUrl))
        .userAgent("F650GSForum/0.7 (Android; native reader)")
        .header("X-Requested-With", "XMLHttpRequest")
        .header("Accept", "application/json, text/javascript, */*; q=0.01")
        .ignoreContentType(true)
        .timeout(15_000)
        .apply { if (sessionCookies.isNotEmpty()) cookies(sessionCookies) }

    fun sessionCookieHeader(): String = sessionCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

/** Plain text as editor paragraphs: blank lines split paragraphs, single newlines become line breaks. */
internal fun paragraphsHtml(text: String): String = if (text.isBlank()) "" else text.trim().split(Regex("\n{2,}")).joinToString("") { paragraph ->
    "<p>" + forumSafeHtml(paragraph) { android.text.Html.escapeHtml(it) }.replace("\n", "<br>") + "</p>"
}
