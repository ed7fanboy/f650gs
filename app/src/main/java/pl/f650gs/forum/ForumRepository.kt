package pl.f650gs.forum

import org.jsoup.Jsoup
import pl.f650gs.forum.ForumParser.clean
import pl.f650gs.forum.ForumParser.safeForumUrl

/** Talks to the forum over HTTP with the app's session cookies; [ForumParser] reads the pages. */
class ForumRepository {
    private companion object { const val MAX_REDIRECTS = 10 }

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
     * With [asGuest] no session cookies are sent: the forum marks a topic read for whoever opens it, and
     * background look-ups behind list cards must not do that.
     */
    fun topic(url: String, asGuest: Boolean = false): TopicPage {
        var location = safeForumUrl(url)
        var focus = 0L
        var response: org.jsoup.Connection.Response? = null
        for (hop in 0 until 6) {
            val current = connect(location, asGuest).followRedirects(false).ignoreContentType(true).execute()
            Regex("""comment[-=](\d+)""").find(location)?.let { focus = it.groupValues[1].toLong() }
            val next = current.header("Location")
            if (current.statusCode() !in 300..399 || next.isNullOrBlank()) { response = current; break }
            location = safeForumUrl(redirectTarget(location, next).toString())
        }
        val document = (response ?: connect(location, asGuest).fetch()).parse()
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
        return ForumParser.members(ajax("/index.php?app=core&module=system&controller=ajax&do=findMember&input=$encoded").fetch().body())
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
            .fetch()
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
            .fetch()
        val url = response.url().toString()
        if (Regex("""/topic/\d+""").containsMatchIn(url)) return safeForumUrl(url)
        error(ForumParser.formError(response.parse(), "Forum nie przyjęło tematu."))
    }

    fun logout(url: String) {
        require(url.contains("logout")) { "Nieprawidłowy adres wylogowania." }
        connect(url).ignoreContentType(true).fetch()
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
        val form = connect("/login/").fetch()
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
            .fetch()
        val problem = ForumParser.pageErrors(response.parse())
        if (problem.isNotBlank()) error(problem)
    }

    /**
     * Sends the topic's moderation form: [action] (e.g. "merge", "delete") on the posts [postIds].
     * A delete first answers with the forum's "are you sure?" form, sent again confirmed (the
     * member already confirmed in the app); a merge answers with the form for the combined post,
     * sent as the forum proposes it. Done when the forum shows no error; the page it lands
     * on may be empty, e.g. after deleting the last page's posts.
     */
    fun moderate(form: ModerationForm, action: String, postIds: Collection<Long>) {
        val fields = form.hidden + ("modaction" to action) + postIds.associate { "multimod[$it]" to "1" }
        fun send(url: String, data: Map<String, String>) =
            connect(url).method(org.jsoup.Connection.Method.POST).data(data).fetch().parse()
        var document = send(form.action, fields)
        ForumParser.confirmForm(document)?.let { confirm ->
            document = send(confirm.action, fields + confirm.fields + ("wasConfirmed" to "1"))
        }
        ForumParser.mergeForm(document)?.let { merge ->
            // Joining posts with photos takes the forum longer than the usual 15 seconds.
            document = connect(merge.action).method(org.jsoup.Connection.Method.POST).timeout(60_000)
                .data(fields + merge.fields).data(merge.editor, merge.content).fetch().parse()
        }
        val problem = ForumParser.pageErrors(document)
        if (problem.isNotBlank()) error(problem)
        if (ForumParser.confirmForm(document) != null) error("Forum nie przyjęło potwierdzenia. Zrób to na stronie forum.")
        if (ForumParser.mergeForm(document) != null) error("Forum nie połączyło wpisów. Zrób to na stronie forum.")
    }

    /** The move form for a topic, asked for in its no-script shape so all sections come in one list. */
    fun moveForm(moveUrl: String): MoveForm =
        ForumParser.moveForm(page("$moveUrl&_nodeSelectName=move_to&_noJs=1")) ?: error("Forum nie pokazało listy działów.")

    fun moveTopic(form: MoveForm, targetId: String) {
        val fields = form.hidden + mapOf("move_to" to targetId, "move_keep_link" to "0", "returnto" to "item")
        val document = connect(form.action).method(org.jsoup.Connection.Method.POST).data(fields).fetch().parse()
        val problem = ForumParser.pageErrors(document)
        if (problem.isNotBlank()) error(problem)
        if (ForumParser.moveForm(document) != null) error("Forum nie przeniosło tematu.")
    }

    /** Fills in the staff dialog at [url] (hide reason, merge address) with [values] and sends it. */
    fun sendTopicForm(url: String, values: Map<String, String>) {
        val form = ForumParser.dialogForm(page(url), values.keys) ?: error("Forum nie pokazało formularza.")
        val document = connect(form.action).method(org.jsoup.Connection.Method.POST).data(form.fields + values).fetch().parse()
        val problem = ForumParser.pageErrors(document)
        if (problem.isNotBlank()) error(problem)
        if (ForumParser.dialogForm(document, values.keys) != null) error("Forum nie przyjęło formularza.")
    }

    /** The page at [url] drawn natively, or null when the app should show the web page instead. */
    fun nativePage(url: String): NativePage? {
        val response = connect(url).fetch()
        return ForumParser.nativePage(response.parse(), safeForumUrl(response.url().toString()))?.withCookies(response.cookies())
    }

    /** Sends [form] with [values] (unticked boxes left out) and reads the page the forum answers with. */
    fun submitForm(form: ForumForm, values: List<Pair<String, String>>): FormOutcome {
        val response = connect(form.action)
            .cookies(form.cookies)
            .method(org.jsoup.Connection.Method.POST)
            .data(form.hidden)
            .apply { values.forEach { (name, value) -> data(name, value) } }
            .fetch()
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
        val document = connect(url).ignoreContentType(true).fetch().parse()
        val confirm = ForumParser.confirmForm(document) ?: return
        val answer = connect(confirm.action)
            .method(org.jsoup.Connection.Method.POST)
            .data(confirm.fields)
            .fetch()
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
            .fetch()
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
            .fetch()
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
            .fetch()
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

    /** Everyone who reacted to post [postId] of the topic at [topicUrl] (at most 10 pages of the list). */
    fun likers(topicUrl: String, postId: Long): List<Liker> {
        val likers = mutableListOf<Liker>()
        var url = safeForumUrl(topicUrl).substringBefore('#').substringBefore('?') + "?do=showReactionsComment&comment=$postId"
        repeat(10) {
            val (found, next) = ForumParser.likers(ajax(url).fetch().parse())
            likers += found
            if (next.isBlank() || next == url) return likers
            url = next
        }
        return likers
    }

    /** Sends a reaction (or its removal) the same way the forum's own JavaScript does. */
    fun react(actionUrl: String) {
        require(actionUrl.contains("do=reactComment") || actionUrl.contains("do=unreactComment")) { "Nieprawidłowa akcja polubienia." }
        ajax(actionUrl).fetch()
    }

    /**
     * Chatbox messages via the bimchatbox AJAX API: newer than [lastId] (0 = the latest 100), or,
     * with [older], the batch before message [lastId]. Messages come oldest first.
     */
    fun chatMessages(lastId: Long, csrfKey: String, older: Boolean = false): ChatBatch {
        val body = ajax("/?app=bimchatbox&module=chatbox&controller=chatbox&do=getMessages&lastID=$lastId&loadMoreMode=${if (older) 1 else 0}&csrfKey=$csrfKey")
            .fetch().body()
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
            .fetch().body()
        val json = runCatching { org.json.JSONObject(body) }.getOrNull()
        if (json?.optString("type") == "error") error(json.optString("message").ifBlank { "Nie udało się wysłać wiadomości." })
    }

    fun search(query: String): List<ActivityStory> {
        // Spaces as %20: the forum finds nothing for a multi-word query sent with "+" between the words.
        val encoded = java.net.URLEncoder.encode(query, Charsets.UTF_8.name()).replace("+", "%20")
        return ForumParser.search(page("/search/?q=$encoded"))
    }

    private fun connect(pathOrUrl: String, asGuest: Boolean = false) = Jsoup.connect(safeForumUrl(pathOrUrl))
        .userAgent("F650GSForum/0.7 (Android; native reader)")
        .header("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.5")
        .timeout(15_000)
        .apply { if (!asGuest && sessionCookies.isNotEmpty()) cookies(sessionCookies) }

    private fun page(pathOrUrl: String) = connect(pathOrUrl).fetch().parse()

    /**
     * Runs the request with redirects followed by hand and only within the forum. Jsoup's own redirects send the
     * session cookies to whatever host a Location header names; a redirect off the forum ends with an error.
     * Like a browser, 301/302/303 continue as a GET without the form data; 307/308 keep the method.
     *
     * Jsoup checks the content type of every response it returns, and with its own redirects off that includes
     * each redirect. IPS sends redirects with an empty media type (`Content-Type: ;charset=UTF-8`), which Jsoup
     * rejects ("Unhandled content type"), so the check is switched off for the hops and done here on the final page,
     * as Jsoup's own redirect handling does.
     */
    private fun org.jsoup.Connection.fetch(): org.jsoup.Connection.Response {
        val checkType = !request().ignoreContentType()
        followRedirects(false)
        ignoreContentType(true)
        var response = execute()
        repeat(MAX_REDIRECTS) {
            if (response.statusCode() !in 300..399) return response.also { if (checkType) requireTextType(it) }
            val next = response.header("Location")?.trim()?.takeIf { it.isNotEmpty() } ?: return response
            val target = redirectTarget(response.url().toString(), next)
            if (response.statusCode() !in setOf(307, 308)) {
                request().method(org.jsoup.Connection.Method.GET)
                request().data().clear()
                request().requestBody(null)
                request().removeHeader("Content-Type")
            }
            cookies(response.cookies())
            url(safeForumUrl(target.toString()))
            response = execute()
        }
        error("Forum przekierowuje w kółko.")
    }

    /**
     * The forum address a redirect's Location header [next] points to from [from]. Locations may carry raw spaces,
     * which java.net.URI rejects; a target off the forum is an error, so cookies never leave f650gs.pl.
     */
    private fun redirectTarget(from: String, next: String): java.net.URI {
        val target = runCatching { java.net.URI(from).resolve(next.trim().replace(" ", "%20")) }.getOrNull()
            ?: error("Nieprawidłowe przekierowanie forum.")
        if (!ForumParser.isForumHost(target.host)) error("Forum przekierowało poza f650gs.pl (${target.host}).")
        return target
    }

    /** Jsoup's own rule for pages it parses: text or XML, or no content type at all. */
    private fun requireTextType(response: org.jsoup.Connection.Response) {
        val type = response.contentType() ?: return
        val text = type.startsWith("text/", ignoreCase = true) ||
            Regex("""(?i)^[^/;]+/(?:xml(?:-[^/;]+)?|[^/;]+\+xml)(?:\s*;.*)?$""").matches(type)
        if (!text) throw org.jsoup.UnsupportedMimeTypeException(
            "Unhandled content type. Must be a text or XML media type", type, response.url().toString())
    }








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
    "<p>" + forumSafeHtml(paragraph) { linkedHtml(it) { run -> android.text.Html.escapeHtml(run) } }.replace("\n", "<br>") + "</p>"
}

private val WEB_ADDRESS = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"]+""")

/**
 * Plain [text] with its web addresses as links, the markup the forum's editor gives a pasted address
 * (the forum itself leaves a bare address as text). Trailing punctuation stays outside the link, and a
 * closing bracket too unless the address opened one. [escape] escapes text for HTML.
 */
internal fun linkedHtml(text: String, escape: (String) -> String): String = buildString {
    var from = 0
    WEB_ADDRESS.findAll(text).forEach { match ->
        var address = match.value
        while (address.isNotEmpty()) {
            val last = address.last()
            val unbalanced = last == ')' && address.count { it == '(' } < address.count { it == ')' }
            if (last in ".,;:!?'\"]}" || unbalanced) address = address.dropLast(1) else break
        }
        if (address.length <= 4) return@forEach
        val start = match.range.first
        append(escape(text.substring(from, start)))
        val href = if (address.startsWith("www.", ignoreCase = true)) "http://$address" else address
        append("<a href=\"").append(escape(href)).append("\" rel=\"external nofollow\">").append(escape(address)).append("</a>")
        from = start + address.length
    }
    append(escape(text.substring(from)))
}
