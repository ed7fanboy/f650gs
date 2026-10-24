package pl.f650gs.forum

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser checks on saved, anonymised forum pages (app/src/test/resources/pages, refreshed with
 * tools/sanitize_fixtures.py). A failure here after a forum update shows which screen broke.
 */
class ForumParserTest {
    private fun page(name: String, url: String): Document {
        val stream = javaClass.classLoader!!.getResourceAsStream("pages/$name") ?: error("Missing fixture $name")
        return stream.use { Jsoup.parse(it, "UTF-8", url) }
    }

    private val topicUrl = Regex("""^http://f650gs\.pl/topic/\d+-""")
    private val profileUrl = Regex("""^http://f650gs\.pl/profile/\d+-""")

    @Test fun activityStreamListsTopicsWithAuthors() {
        val items = ForumParser.activity(page("discover_in.html", "http://f650gs.pl/discover/"))
        assertTrue("items: ${items.size}", items.size >= 20)
        items.forEach { assertTrue(it.url, topicUrl.containsMatchIn(it.url)) }
        assertTrue(items.all { it.title.isNotBlank() })
        assertTrue(items.count { it.author.isNotBlank() } >= items.size - 2)
        assertTrue(items.count { profileUrl.containsMatchIn(it.authorUrl) } >= items.size - 2)
    }

    @Test fun moderatorProfileLinksLoseWarnReference() {
        assertEquals("http://f650gs.pl/profile/12-name/", ForumParser.withoutWarnReference("http://f650gs.pl/profile/12-name/?wr=eyJhcHAiOiJmb3J1bXMifQ=="))
        assertEquals("http://f650gs.pl/profile/12-name/?tab=x", ForumParser.withoutWarnReference("http://f650gs.pl/profile/12-name/?wr=abc&tab=x"))
        assertEquals("http://f650gs.pl/profile/12-name/", ForumParser.withoutWarnReference("http://f650gs.pl/profile/12-name/"))
        assertEquals("http://f650gs.pl/profile/12-name/#c", ForumParser.withoutWarnReference("http://f650gs.pl/profile/12-name/?wr=abc#c"))
        assertEquals("http://f650gs.pl/profile/12-name/?tab=x", ForumParser.withoutWarnReference("http://f650gs.pl/profile/12-name/?tab=x&wr=abc"))
    }

    @Test fun moderationFormAndPostActions() {
        val html = """<form action="/topic/1-t/?csrfKey=k&amp;do=multimodComment" data-role="moderationTools">
            <article id="elComment_5" class="ipsComment"><div data-role="commentContent"><p>a</p></div>
            <input type="checkbox" name="multimod[5]" data-role="moderation" data-actions="split merge hide delete" data-state="unhidden"></article>
            <article id="elComment_6" class="ipsComment"><div data-role="commentContent"><p>b</p><input type="hidden" name="pollField" value="1"></div>
            <input type="checkbox" name="multimod[6]" data-role="moderation" data-actions="merge hide delete" data-state="hidden"></article>
            <input type="hidden" name="csrfKey" value="k"><select name="modaction"><option value="merge">Połącz</option>
            <optgroup label="Ukryj"><option value="hide">Ukryj</option><option value="unhide">Odkryj</option></optgroup><option value="delete">Usuń</option></select></form>"""
        val topic = ForumParser.topic(org.jsoup.Jsoup.parse(html, "http://f650gs.pl/"), "http://f650gs.pl/topic/1-t/")
        assertEquals(listOf("split", "merge", "hide", "delete"), topic.posts[0].modActions)
        assertEquals(listOf("merge", "unhide", "delete"), topic.posts[1].modActions)
        val form = topic.moderation!!
        assertEquals(listOf("merge", "hide", "unhide", "delete"), form.actions.map { it.first })
        assertEquals("k", form.hidden["csrfKey"])
        assertNull(form.hidden["pollField"])
    }

    @Test fun likersListWithReactionsAndNextPage() {
        val html = """<ol class="ipsGrid" data-role="tableRows">
            <li class="ipsPhotoPanel"><a href="http://f650gs.pl/profile/901-czlonek-901/?wr=x" class="ipsUserPhoto"><img src="http://f650gs.pl/uploads/p901.png" alt="Członek 1"></a>
              <div><h3>Członek 1</h3><span class="ipsType_light"><img src="http://f650gs.pl/uploads/reactions/react_like.png"> <span><time datetime="2026-10-23T12:51:57Z">piątek o 14:51</time></span></span>
              <a href="http://f650gs.pl/topic/1-t/?do=unreactComment&amp;comment=5&amp;member=901"><i></i></a></div></li>
            <li class="ipsPhotoPanel"><a href="http://f650gs.pl/profile/902-czlonek-902/" class="ipsUserPhoto"><img src="http://f650gs.pl/uploads/p902.png" alt="Członek 2"></a>
              <div><h3>Członek 2</h3><span class="ipsType_light"><img src="http://f650gs.pl/uploads/reactions/react_thanks.png"> <span><time>wczoraj</time></span></span></div></li>
            </ol><ul class="ipsPagination"><li class="ipsPagination_next"><a href="http://f650gs.pl/topic/1-t/?do=showReactionsComment&amp;comment=5&amp;page=2">Dalej</a></li></ul>"""
        val (likers, next) = ForumParser.likers(org.jsoup.Jsoup.parse(html, "http://f650gs.pl/"))
        assertEquals(listOf("Członek 1", "Członek 2"), likers.map { it.name })
        assertEquals(listOf("like", "thanks"), likers.map { it.reaction })
        assertEquals("http://f650gs.pl/profile/901-czlonek-901/", likers[0].profileUrl)
        assertEquals("piątek o 14:51", likers[0].date)
        assertEquals("http://f650gs.pl/topic/1-t/?do=showReactionsComment&comment=5&page=2", next)
    }

    @Test fun topicModerationMenuAndMoveForm() {
        val menu = """<ul id="elTopicActions_menu">
            <li><a href="http://f650gs.pl/topic/1-t/?csrfKey=k&amp;do=moderate&amp;action=unfeature">Przestań promować</a></li>
            <li><a href="http://f650gs.pl/topic/1-t/?csrfKey=k&amp;do=moderate&amp;action=unpin">Odepnij</a></li>
            <li><a href="http://f650gs.pl/topic/1-t/?csrfKey=k&amp;do=moderate&amp;action=lock">Zamknij</a></li>
            <li><a href="http://f650gs.pl/topic/1-t/?csrfKey=k&amp;do=move">Przenieś</a></li>
            <li><a href="http://f650gs.pl/topic/1-t/?csrfKey=k&amp;do=merge">Połącz</a></li>
            <li><a href="http://f650gs.pl/topic/1-t/?csrfKey=k&amp;do=moderate&amp;action=delete" data-confirm>Usuń</a></li>
            <li><a href="http://f650gs.pl/topic/1-t/?csrfKey=k&amp;do=modLog">Historia moderacji</a></li></ul>"""
        val topic = ForumParser.topic(org.jsoup.Jsoup.parse(menu, "http://f650gs.pl/"), "http://f650gs.pl/topic/1-t/")
        val mod = topic.topicModeration!!
        assertEquals(listOf("unfeature", "unpin", "lock", "move", "merge", "delete"), mod.actions.map { it.key })
        assertEquals("Odepnij", mod.actions[1].label)
        assertTrue(mod.url("move"), mod.url("move").endsWith("csrfKey=k&do=move"))
        assertEquals("", mod.url("unlock"))
        assertNull(ForumParser.topic(org.jsoup.Jsoup.parse("<p>x</p>", "http://f650gs.pl/"), "http://f650gs.pl/topic/1-t/").topicModeration)

        val merge = """<form action="http://f650gs.pl/topic/1-t/?do=merge" method="post"><input type="hidden" name="csrfKey" value="k">
            <input type="url" name="merge_with"><input type="hidden" name="move_keep_link" value="0"></form>"""
        val dialog = ForumParser.dialogForm(org.jsoup.Jsoup.parse(merge, "http://f650gs.pl/"), listOf("merge_with", "move_keep_link"))!!
        assertEquals("http://f650gs.pl/topic/1-t/?do=merge", dialog.action)
        assertEquals("k", dialog.fields["csrfKey"])
        assertNull(ForumParser.dialogForm(org.jsoup.Jsoup.parse(merge, "http://f650gs.pl/"), listOf("hide_reason")))

        val form = """<form action="http://f650gs.pl/topic/1-t/?do=move&amp;_nodeSelectName=move_to&amp;_noJs=1" method="post">
            <input type="hidden" name="form_submitted" value="1"><input type="hidden" name="csrfKey" value="k">
            <select name="move_to"><option value="9" disabled>Zaplecze</option><option value="10" disabled>- Forumowe...</option>
            <option value="25">- Wysypisko</option><option value="48">- - Silnik</option></select>
            <input type="hidden" name="move_keep_link" value="0"></form>"""
        val move = ForumParser.moveForm(org.jsoup.Jsoup.parse(form, "http://f650gs.pl/"))!!
        assertEquals(listOf(MoveTarget("25", "Wysypisko", 1), MoveTarget("48", "Silnik", 2)), move.targets)
        assertEquals(mapOf("form_submitted" to "1", "csrfKey" to "k"), move.hidden)
        assertTrue(move.action, move.action.contains("_noJs=1"))
    }

    @Test fun categoriesListForums() {
        val categories = ForumParser.categories(page("index.html", "http://f650gs.pl/"))
        assertTrue(categories.size >= 3)
        val forums = categories.flatMap { it.forums }
        assertTrue(forums.all { it.url.startsWith("http://f650gs.pl/forum/") && it.title.isNotBlank() })
        assertTrue(forums.any { it.title == "Przywitalnia" })
    }

    @Test fun forumListsTopics() {
        val forum = ForumParser.forum(page("forum.html", "http://f650gs.pl/forum/7-sprzedam/"))
        assertEquals("Sprzedam", forum.title)
        assertTrue(forum.topics.size >= 10)
        forum.topics.forEach { assertTrue(it.url, topicUrl.containsMatchIn(it.url)) }
        assertTrue(forum.sorts.size >= 5)
        assertEquals(1, forum.sorts.count { it.selected })
        assertTrue(forum.sorts.all { it.url.contains("sortby=") })
        assertTrue(forum.pager.label, forum.pager.label.startsWith("Strona 1 z "))
        assertTrue(forum.pager.nextUrl, forum.pager.nextUrl.contains("page=2"))
        assertTrue(forum.pager.lastUrl, Regex("""page=\d+""").containsMatchIn(forum.pager.lastUrl))
        assertEquals("", forum.pager.prevUrl)
        assertEquals("", forum.pager.firstUrl)
        assertFalse(forum.canPost) // guest page
    }

    @Test fun bareLinkTextLosesDoubleEscapedAmpersands() {
        val body = Jsoup.parseBodyFragment(
            "<p><a href=\"https://x.pl/?a=1&amp;b=2\">https://x.pl/?a=1&amp;amp;b=2</a> <a href=\"/t\">Tom &amp;amp; Jerry</a></p>",
            "http://f650gs.pl/",
        ).body()
        val html = ForumParser.safePostHtml(body)
        assertTrue(html, html.contains(">https://x.pl/?a=1&amp;b=2</a>"))
        assertTrue("plain link text stays as written: $html", html.contains("Tom &amp;amp; Jerry"))
    }

    @Test fun parentSectionListsSubforums() {
        val forum = ForumParser.forum(page("forum_parent.html", "http://f650gs.pl/forum/18-dzia%C5%82-techniczny/"))
        assertTrue(forum.topics.isEmpty())
        assertTrue("subforums: ${forum.subforums.size}", forum.subforums.size >= 2)
        assertTrue(forum.subforums.all { it.url.startsWith("http://f650gs.pl/forum/") && it.title.isNotBlank() })
        assertTrue(forum.subforums.any { it.title == "Mechanika ogólna" })
    }

    @Test fun topicPostsPagingAndReplyForm() {
        val url = "http://f650gs.pl/topic/13836-kilka-klatek-wkolo-komina/page/199/"
        val topic = ForumParser.topic(page("topic_in.html", url), url)
        assertTrue(topic.posts.isNotEmpty())
        topic.posts.forEach { post ->
            assertTrue(post.id > 0)
            assertTrue(post.author.isNotBlank())
            assertTrue(post.authorUrl, profileUrl.containsMatchIn(post.authorUrl))
            assertFalse(post.html.contains("<script"))
            assertTrue("signed-in page has like links", post.likeUrl.contains("do=reactComment"))
        }
        assertTrue(topic.pager.label, topic.pager.label.startsWith("Strona 199 z"))
        assertTrue(topic.pager.prevUrl, topic.pager.prevUrl.contains("page=198"))
        assertTrue(topic.pager.firstUrl, topic.pager.firstUrl.isNotBlank() && !topic.pager.firstUrl.contains("page=198"))
        assertEquals(198 * 25 + 1, topic.pager.firstNumber)
        val form = assertNotNullAndGet(topic.replyForm)
        assertTrue(form.fields.containsKey("csrfKey"))
        assertTrue(form.editor.isNotBlank())
        assertTrue(form.contentId.isNotBlank())
    }

    @Test fun topicFocusOnlyForPostsOnThePage() {
        val url = "http://f650gs.pl/topic/13940-ukryty-kanister-1litr-w-dziobie-f650gs-twin/"
        val document = page("mytopic.html", url)
        val first = ForumParser.topic(document, url).posts.first()
        assertEquals(first.id, ForumParser.topic(document, url, first.id).focusPostId)
        assertEquals(0L, ForumParser.topic(document, url, 1).focusPostId)
    }

    @Test fun ownPostsHaveEditLinks() {
        // The forum only shows "Edytuj" while the edit window is open, so this uses a minimal post.
        fun post(id: Int, edit: String) = "<article class='ipsComment' id='elComment_$id'><div class='cAuthorPane_author'>" +
            "<a href='/profile/1-a/'>A</a></div>$edit<div data-role='commentContent'><p>Tekst</p></div></article>"
        val document = Jsoup.parse(post(1, "<a href='/topic/1-x/?do=editComment&amp;comment=1'>Edytuj</a>") + post(2, ""), "http://f650gs.pl/topic/1-x/")
        val posts = ForumParser.topic(document, "http://f650gs.pl/topic/1-x/").posts
        assertEquals("http://f650gs.pl/topic/1-x/?do=editComment&comment=1", posts[0].editUrl)
        assertEquals("", posts[1].editUrl)
    }

    @Test fun accountOnlyWhenSignedIn() {
        val account = assertNotNullAndGet(ForumParser.account(page("discover_in.html", "http://f650gs.pl/discover/")))
        assertEquals("Ja", account.name)
        assertTrue(account.logoutUrl.contains("logout"))
        assertTrue(profileUrl.containsMatchIn(account.profileUrl))
        assertNull(ForumParser.account(page("index.html", "http://f650gs.pl/")))
    }

    @Test fun chatNewsIgnoresSeenAndOwnMessages() {
        fun message(id: Long, author: String) = ChatMessage(id, "1", author, "", "hej", 0)
        val batch = listOf(message(10, "Ja"), message(11, "Członek 1"))
        assertTrue(chatHasNews(batch, 10, "Ja"))
        assertFalse(chatHasNews(batch, 11, "Ja"))
        assertFalse(chatHasNews(listOf(message(12, "ja")), 11, "Ja"))
        assertFalse(chatHasNews(emptyList(), 11, "Ja"))
    }

    @Test fun homePreviewPicksFirstUnreadElseLatest() {
        val stream = ForumParser.activity(page("discover_in.html", "http://f650gs.pl/discover/"))
        val topic = stream.filter { it.url.contains("/topic/4282-") }
        assertTrue(topic.size >= 5)
        val me = topic[1].author
        // All read: the latest post by someone else, with nothing or only your own posts above it.
        val (latest, aboveLatest) = homePreview(topic, me)
        assertEquals(topic.first { it.author != me }, latest)
        assertTrue(aboveLatest.all { it.author == me })
        // Three oldest unread: the oldest of them (by someone else), everything newer summarised.
        val marked = markUnread(topic, topic.takeLast(3))
        assertEquals(3, marked.count { it.unread })
        val (first, newer) = homePreview(marked, "")
        assertEquals(topic.last().url, first.url)
        assertEquals(topic.size - 1, newer.size)
        // Your own unread posts are skipped.
        val (notMine, _) = homePreview(marked, topic.last().author)
        assertTrue(notMine.author != topic.last().author || !notMine.unread)
    }

    @Test fun countersReadHeaderBadges() {
        val counters = ForumParser.counters(page("discover_in.html", "http://f650gs.pl/discover/"))
        assertTrue(counters.inbox >= 0 && counters.notifications >= 0)
        assertTrue(counters.unreadTopics > 0)
    }

    @Test fun profileHeaderStatsFieldsAndActivity() {
        val url = "http://f650gs.pl/profile/1082-marcin-n/"
        val profile = ForumParser.profile(page("profile_in.html", url), url)
        assertEquals("1082", profile.id)
        assertEquals("Członek 10", profile.name)
        assertTrue(profile.photo.startsWith("http"))
        assertTrue(profile.stats.any { it.first == "Reputacja" })
        assertTrue(profile.stats.any { it.first == "Rejestracja" })
        assertTrue(profile.fields.any { (label, value) -> label == "Lokalizacja" && value.startsWith("Tekst ") })
        assertTrue(profile.canMessage)
        assertTrue(profile.activity.isNotEmpty())
    }

    @Test fun conversationList() {
        val items = ForumParser.conversations(page("messenger.html", "http://f650gs.pl/messenger/"))
        assertTrue(items.size >= 5)
        items.forEach { assertTrue(it.url, Regex("""/messenger/\d+""").containsMatchIn(it.url)) }
        assertTrue(items.all { it.title.isNotBlank() })
    }

    @Test fun conversationMessagesAndReplyForm() {
        val url = "http://f650gs.pl/messenger/1/"
        val page = ForumParser.conversation(page("conv.html", url), url)
        assertTrue(page.messages.isNotEmpty())
        assertEquals("901", page.myMemberId)
        assertTrue(page.messages.all { it.authorId.isNotBlank() })
        assertTrue(page.messages.any { it.authorId == page.myMemberId })
        assertNotNull(page.replyForm)
    }

    @Test fun composeFormIsAddressed() {
        val compose = ForumParser.composeForm(page("compose.html", "http://f650gs.pl/messenger/compose/?to=4057"))
        assertEquals("Ja", compose.recipient)
        assertEquals("messenger_content", compose.form.editor)
        assertTrue(compose.form.fields.containsKey("csrfKey"))
    }

    @Test fun searchResults() {
        val items = ForumParser.search(page("search.html", "http://f650gs.pl/search/?q=kanister"))
        assertTrue(items.isNotEmpty())
        items.forEach { assertTrue(it.url, topicUrl.containsMatchIn(it.url)) }
    }

    @Test fun postHtmlIsSanitised() {
        val body = Jsoup.parse(
            "<div id=b><p onclick='x()'>Hi<script>bad()</script></p>" +
                "<a href='javascript:alert(1)'>bad</a><a href='http://ok.pl/' target='_blank'>ok</a>" +
                "<iframe src='https://www.youtube.com/embed/abc_DEF-1?x=1'></iframe><iframe src='http://evil.example/'></iframe>" +
                "<img data-src='/uploads/a.jpg'><img src='javascript:x'></div>",
            "http://f650gs.pl/topic/1-x/",
        ).getElementById("b")!!
        val html = ForumParser.safePostHtml(body)
        assertFalse(html.contains("script"))
        assertFalse(html.contains("onclick"))
        assertFalse(html.contains("javascript:"))
        assertFalse(html.contains("target="))
        assertFalse(html.contains("evil.example"))
        assertTrue(html.contains("https://www.youtube.com/watch?v=abc_DEF-1"))
        assertTrue(html.contains("src=\"http://f650gs.pl/uploads/a.jpg\""))
    }

    @Test fun youtubeEmbedsBecomeThumbnailCards() {
        val body = Jsoup.parse(
            "<div id=b><div class='ipsEmbeddedVideo'><div><iframe src='https://www.youtube.com/embed/abc_DEF-123?feature=oembed' title='Jazda &amp; kurz'></iframe></div></div>" +
                "<iframe src='https://www.youtube.com/embed/xyz_UVW-456'></iframe></div>",
            "http://f650gs.pl/topic/1-x/",
        ).getElementById("b")!!
        val cards = Jsoup.parseBodyFragment(ForumParser.safePostHtml(body)).select("a.forumYoutubeLink")
        assertEquals(2, cards.size)
        assertEquals("https://www.youtube.com/watch?v=abc_DEF-123", cards[0].attr("href"))
        assertEquals("https://i.ytimg.com/vi/abc_DEF-123/hqdefault.jpg", cards[0].selectFirst("img[data-ytthumb]")!!.attr("src"))
        assertEquals("Jazda & kurz", cards[0].selectFirst(".forumYoutubeTitle")!!.text())
        // No title in the iframe: a placeholder the page replaces with the film's title.
        assertEquals("Otwórz film na YouTube", cards[1].selectFirst(".forumYoutubeTitle")!!.text())
        assertEquals(0, ForumParser.imageCount(cards.outerHtml()))
    }

    @Test fun urlsStayOnTheForum() {
        assertEquals("http://f650gs.pl/topic/1-x/", ForumParser.safeForumUrl("/topic/1-x/"))
        assertEquals("http://f650gs.pl/a?b=1#c", ForumParser.safeForumUrl("https://f650gs.pl/a?b=1#c"))
        assertEquals("http://f650gs.pl/", ForumParser.safeForumUrl("http://evil.example/f650gs.pl"))
        assertEquals("http://f650gs.pl/", ForumParser.safeForumUrl("not a url"))
    }

    @Test fun chatboxAnswers() {
        val json = """{"content":"7~~#~~12~~#~~Jan~~#~~x~~#~~http://f650gs.pl/p.png~~#~~x~~#~~Cześć~~#~~1700000000~~||~~8~~#~~13~~#~~Ola~~#~~x~~#~~~~#~~x~~#~~Hej~~#~~1700000060","lastID":"8"}"""
        val batch = ForumParser.chatBatch(json, 0)
        assertEquals(8L, batch.lastId)
        assertEquals(listOf("Cześć", "Hej"), batch.messages.map { it.text })
        assertEquals("12", batch.messages.first().memberId)
        assertEquals("" to null, ForumParser.chatSession("<html>signed out</html>"))
        assertEquals("12" to "0123456789abcdef0123456789abcdef",
            ForumParser.chatSession("memberID: 12, url: '?csrfKey=0123456789abcdef0123456789abcdef'"))
    }

    @Test fun memberSearchAnswer() {
        val hits = ForumParser.members("""[{"id":"839","value":"Mard","name":"M&amp;rd","photo":"http://f650gs.pl/p.png"}]""")
        assertEquals(listOf(MemberHit("839", "M&rd", "http://f650gs.pl/p.png")), hits)
    }

    @Test fun imageCountSkipsEmoticonsAndQuotes() {
        val html = "<p>Widok <img src=\"https://lh3.googleusercontent.com/a=w1600-h1600\"> i " +
            "<img data-emoticon=\"\" src=\"http://f650gs.pl/uploads/emoticons/smile.png\"> " +
            "<img src=\"http://emoji.tapatalk-cdn.com/emoji1.png\"></p>" +
            "<blockquote><p><img src=\"https://example.com/quoted.jpg\"></p></blockquote>" +
            "<p><img src=\"http://f650gs.pl/uploads/monthly_2026_10/photo.jpg\"></p>"
        assertEquals(2, ForumParser.imageCount(html))
        assertEquals(0, ForumParser.imageCount("<p>Sam tekst</p>"))
    }

    @Test fun imagesLabelUsesPolishPlurals() {
        assertEquals(listOf("1 zdjęcie", "2 zdjęcia", "5 zdjęć", "12 zdjęć", "22 zdjęcia"), listOf(1, 2, 5, 12, 22).map(::imagesLabel))
    }

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    @Test fun notificationsListEntriesAndNextPage() {
        val page = ForumParser.notifications(page("notifications.html", "http://f650gs.pl/notifications/"))
        assertEquals(2, page.items.size)
        val (quote, reaction) = page.items
        assertTrue(quote.unread)
        assertFalse(reaction.unread)
        assertEquals("Tester", quote.author)
        assertTrue(quote.url, topicUrl.containsMatchIn(quote.url))
        assertTrue(quote.photo.startsWith("http://f650gs.pl/uploads/"))
        assertEquals("29 minut temu", quote.time)
        assertEquals("Drugi – reakcja na Twój wpis w temacie Przykładowy temat", reaction.text)
        assertEquals("http://f650gs.pl/notifications/?page=2", page.nextUrl)
    }

    @Test fun signUpFormReadsEveryField() {
        val form = ForumParser.signUpForm(page("register.html", "http://f650gs.pl/register/"))
        assertNotNull(form)
        form!!
        assertEquals("http://f650gs.pl/register/", form.action)
        assertTrue(form.hidden.containsKey("csrfKey"))
        assertEquals("0", form.hidden["reg_agreed_terms"])
        val byName = form.fields.associateBy { it.name }
        assertEquals("password", byName["password"]?.kind)
        assertEquals("email", byName["email_address"]?.kind)
        assertEquals(26, byName["username"]?.maxLength)
        assertEquals("Nazwa użytkownika", byName["username"]?.label)
        val sex = byName["core_pfield_5"]!!
        assertEquals("select", sex.kind)
        assertEquals("Nie powiem", sex.value)
        assertEquals(3, sex.options.size)
        val question = byName["q_and_a"]!!
        assertTrue(question.required)
        assertTrue(question.label, question.label.contains("F650GS"))
        val terms = byName["reg_agreed_terms_checkbox"]!!
        assertEquals("checkbox", terms.kind)
        assertEquals("", terms.value)
        assertTrue(terms.link, terms.link.endsWith("/terms/"))
        assertEquals("1", byName["reg_admin_mails_checkbox"]?.value)
        assertFalse(byName["core_pfield_12"]!!.required)
    }

    @Test fun notificationSettingsReadSwitchesAndMatrix() {
        val form = ForumParser.notificationSettings(page("notification_options.html", "http://f650gs.pl/notifications/options/"))
        assertNotNull(form)
        form!!
        assertEquals("http://f650gs.pl/notifications/options/", form.action)
        assertEquals("0", form.hidden["member_follow[member_notifications_inline]"])
        val titles = form.sections.map { it.title }
        assertTrue(titles.toString(), titles.containsAll(listOf("Automatycznie obserwowana zawartość", "Profil", "Komunikator", "Ogólne")))
        val tracking = form.sections.first { it.title.startsWith("Metoda") }.rows.map { it.toggles.single() }
        assertTrue(tracking.all { it.radio && it.name == "auto_track_type" })
        assertEquals(1, tracking.count { it.checked })
        val quote = form.sections.first { it.title == "Ogólne" }.rows.first { it.title.contains("zacytowaniu") }
        assertEquals(listOf("Powiadomienie", "E-mail"), quote.toggles.map { it.label })
        assertEquals("quote[member_notifications_inline_checkbox]", quote.toggles.first().name)
        val locked = form.sections.first { it.title == "Profil" }.rows.first { it.title.contains("komentarzach") }
        assertTrue(locked.toggles.none { it.enabled })
    }

    private fun nativePage(name: String, url: String) = ForumParser.nativePage(page(name, url), url)

    private fun NativePage.form() = blocks.filterIsInstance<PageBlock.Form>().single().form

    @Test fun settingsOverviewListsAccountRowsAndSideMenu() {
        val page = nativePage("settings.html", "http://f650gs.pl/settings/")
        assertNotNull(page)
        page!!
        assertEquals("Ustawienia", page.title)
        val rows = page.blocks.filterIsInstance<PageBlock.Links>().flatMap { it.items }
        val email = rows.first { it.title == "Adres e-mail" }
        assertEquals("user@example.com", email.detail)
        assertEquals("http://f650gs.pl/settings/email/", email.url)
        // The side menu leaves out the page itself.
        assertTrue(rows.any { it.url == "http://f650gs.pl/settings/devices/" })
        assertTrue(rows.none { it.title == "Przegląd" })
        assertEquals(1, rows.count { it.url == "http://f650gs.pl/settings/email/" })
    }

    @Test fun passwordFormHasThreeRequiredPasswords() {
        val form = nativePage("settings_password.html", "http://f650gs.pl/settings/password/")!!.form()
        val fields = form.items.filterIsInstance<FormItem.Input>().map { it.field }
        assertEquals(listOf("current_password", "new_password", "confirm_new_password"), fields.map { it.name })
        assertTrue(fields.all { it.kind == "password" && it.required })
        assertEquals("Zapisz", form.submit)
        assertEquals("http://f650gs.pl/settings/password/", form.action)
    }

    @Test fun emailFormShowsCurrentAddressReadOnly() {
        val page = nativePage("settings_email.html", "http://f650gs.pl/settings/email/")!!
        assertTrue(page.blocks.any { it is PageBlock.Text && it.text.contains("ponowna aktywacja") })
        val items = page.form().items
        assertEquals(FormItem.Info("Bieżący adres e-mail", "user@example.com"), items.first())
        assertEquals("email", (items[1] as FormItem.Input).field.kind)
    }

    @Test fun ignoreFormHasMemberBoxAndIgnoreChoices() {
        val page = nativePage("ignore.html", "http://f650gs.pl/ignore/")!!
        val form = page.form()
        val member = (form.items.first() as FormItem.Input).field
        assertEquals("member", member.name)
        assertTrue(member.label, member.label.startsWith("Znajdź użytkownika"))
        assertTrue(member.memberSearch)
        assertEquals("text", member.kind)
        val choices = form.items.filterIsInstance<FormItem.Choices>().single()
        assertEquals("Ignoruj", choices.title)
        assertEquals(5, choices.rows.size)
        assertEquals("ignore_topics_checkbox", choices.rows.first().toggles.single().name)
        assertEquals("0", form.hidden["ignore_topics"])
        assertEquals("Dodaj użytkownika", form.submit)
        assertTrue(page.blocks.any { it is PageBlock.Text && it.text.contains("Brak wyników") })
    }

    @Test fun pagesWithThePostEditorStayOnTheWeb() {
        assertNull(nativePage("topic_in.html", "http://f650gs.pl/topic/1-x/"))
    }

    @Test fun confirmStepIsFoundWithItsHiddenFields() {
        val confirm = ForumParser.confirmForm(page("confirm.html", "http://f650gs.pl/ignore/?do=remove&id=901&csrfKey=0"))
        assertNotNull(confirm)
        assertEquals("http://f650gs.pl/ignore/?do=remove&id=901", confirm!!.action)
        assertEquals("1", confirm.fields["wasConfirmed"])
        assertTrue(confirm.fields.containsKey("csrfKey"))
        assertNull(ForumParser.confirmForm(page("ignore.html", "http://f650gs.pl/ignore/")))
    }

    @Test fun attachmentsListShowsThumbnailsPostsAndPages() {
        val page = nativePage("attachments.html", "http://f650gs.pl/attachments/")!!
        val rows = page.blocks.filterIsInstance<PageBlock.Links>().flatMap { it.items }
        val picture = rows.first { it.title == "zdjecie-1.jpg" }
        assertEquals("http://f650gs.pl/uploads/file-1.jpg", picture.photo)
        assertTrue(picture.url, picture.url.contains("attachment.php?id=901"))
        assertTrue(picture.detail, picture.detail.startsWith("39 kB"))
        assertEquals(listOf("Temat 1"), picture.links.map { it.first })
        assertEquals("", rows.first { it.title == "instrukcja.pdf" }.photo)
        assertTrue(rows.any { it.title == "Następna strona ›" && it.url.endsWith("page=2") })
        assertTrue(rows.none { it.title.startsWith("‹") })
    }

    @Test fun emptyFollowedContentIsDrawnWithItsTypes() {
        val page = nativePage("followed.html", "http://f650gs.pl/followed/")
        assertNotNull(page)
        page!!
        assertTrue(page.blocks.none { it is PageBlock.Form })
        assertTrue(page.blocks.any { it is PageBlock.Text && it.text == "Nic tu jeszcze nie ma" })
        val types = page.blocks.filterIsInstance<PageBlock.Links>().flatMap { it.items }.map { it.title }
        assertTrue(types.toString(), types.containsAll(listOf("Tematy", "Forum", "Użytkownicy")))
    }

    @Test fun adminHeaderNoticeIsNotAnError() {
        val page = Jsoup.parse(
            "<body><div id=\"ipsLayout_header\"><div class=\"ipsMessage ipsMessage_error\">Jest dostępna nowa wersja IPS Community Suite: 4.7.25</div></div>" +
                "<div id=\"ipsLayout_mainArea\"><form><div class=\"ipsMessage ipsMessage_error\">Wpis jest za krótki.</div></form></div></body>",
            "http://f650gs.pl/",
        )
        assertEquals("Wpis jest za krótki.", ForumParser.pageErrors(page))
        assertEquals("", ForumParser.pageErrors(Jsoup.parse(page.html().replace("Wpis jest za krótki.", "").replace("ipsMessage ipsMessage_error\">", "x\">").let {
            "<body><div id=\"ipsLayout_header\"><div class=\"ipsMessage ipsMessage_error\">Jest dostępna nowa wersja IPS Community Suite</div></div></body>"
        })))
    }
}
