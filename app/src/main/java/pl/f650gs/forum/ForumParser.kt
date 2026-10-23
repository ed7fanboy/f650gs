package pl.f650gs.forum

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Reads forum pages (IPS 4 HTML) into app data. No network and no Android classes here,
 * so every function can be tested on saved pages (see app/src/test).
 */
object ForumParser {
    fun activity(document: Document): List<ActivityStory> {
        // Action blocks are reactions ("X reacted to post"), follows and sign-ups. They
        // point at an existing post, so showing them made one post appear several times.
        return document.select("li.ipsStreamItem[data-role=activityItem]:not(.ipsStreamItem_actionBlock)").mapNotNull { item ->
            // IPS streams can put the topic link in different places depending on the
            // activity type. Prefer an actual topic URL so a profile/forum link cannot
            // accidentally become the card's headline.
            val titleLink = item.selectFirst(".ipsStreamItem_title a[href*=/topic/]")
                ?: item.selectFirst(".ipsStreamItem_status a[href*=/topic/]")
                ?: item.selectFirst("a[href*=/topic/]")
                ?: return@mapNotNull null
            val authorLink = item.selectFirst(
                ".ipsStreamItem_header a[href*=/profile/], .ipsStreamItem_status a[href*=/profile/], .ipsStreamItem_action a[href*=/profile/]"
            )
            val authorPhoto = item.selectFirst(".ipsStreamItem_header .ipsUserPhoto, .ipsStreamItem_action .ipsUserPhoto")
            val author = sequenceOf(
                authorLink?.text().clean(),
                authorPhoto?.attr("title")?.removePrefix("Przejdź do profilu ").clean(),
                authorPhoto?.selectFirst("img")?.attr("alt").clean(),
            ).firstOrNull { it.isNotBlank() }.orEmpty()
            ActivityStory(
                title = titleLink.text().clean().ifBlank { titleLink.attr("title").clean() },
                author = author,
                context = item.selectFirst(".ipsStreamItem_status")?.text().clean(),
                snippet = item.selectFirst(".ipsStreamItem_snippet")?.text().clean(),
                time = (item.selectFirst(".ipsStreamItem_meta time") ?: item.selectFirst("time"))?.text().clean(),
                replies = item.select(".ipsStreamItem_meta li").lastOrNull()?.text()?.clean().orEmpty(),
                url = safeForumUrl(titleLink.absUrl("href")),
                photo = photoUrl(item.selectFirst(".ipsUserPhoto img")),
                authorUrl = profileLink(authorLink ?: authorPhoto),
            )
        }
    }

    /** The profile address behind a member link or photo, or "" when there is none. */
    private fun profileLink(element: Element?): String =
        element?.absUrl("href")?.takeIf { it.contains("/profile/") }?.let(::safeForumUrl).orEmpty()

    /** Member photo URL; IPS letter avatars as inline SVG are left to the app's own letter badge. */
    private fun photoUrl(image: Element?): String =
        image?.absUrl("src")?.takeIf { it.startsWith("http") }.orEmpty()

    fun categories(document: Document): List<ForumCategory> =
        document.select("ol.cForumList > li[data-categoryid]").mapNotNull { category ->
            val title = category.select(".cForumTitle a").firstOrNull { it.absUrl("href").contains("/forum/") }
                ?.text()?.clean() ?: return@mapNotNull null
            ForumCategory(title, category.select("li[data-forumid]").mapNotNull(::forumSummary))
        }.filter { it.forums.isNotEmpty() }

    private fun forumSummary(row: Element): ForumSummary? {
        val titleLink = row.selectFirst(".ipsDataItem_title a") ?: return null
        val lastPoster = row.selectFirst(".ipsDataItem_lastPoster")
        return ForumSummary(
            title = titleLink.text().clean(),
            description = row.selectFirst(".ipsDataItem_meta")?.text().clean(),
            postCount = row.selectFirst(".ipsDataItem_stats_number")?.text().clean(),
            latestTopic = lastPoster?.select("a[href*=/topic/]")?.firstOrNull()?.text().clean(),
            latestAuthor = lastPoster?.select("a[href*=/profile/]")?.lastOrNull()?.text().clean(),
            latestTime = lastPoster?.selectFirst("time")?.text().clean(),
            url = safeForumUrl(titleLink.absUrl("href")),
            markReadUrl = markReadLink(row),
        )
    }

    /** The first pager on a page; links are empty where the forum greys the button out. */
    private fun pager(document: Document): Pager {
        val pagination = document.selectFirst("ul.ipsPagination")
        fun pageLink(css: String) = pagination?.selectFirst("$css:not(.ipsPagination_inactive) a[href]")
            ?.absUrl("href")?.let(::safeForumUrl).orEmpty()
        val pages = pagination?.attr("data-pages")?.toIntOrNull() ?: 1
        val current = pagination?.selectFirst(".ipsPagination_active")?.text()?.trim()?.toIntOrNull() ?: 1
        val perPage = pagination?.attr("data-ipsPagination-perPage")?.toIntOrNull() ?: 0
        val prev = pageLink(".ipsPagination_prev")
        val next = pageLink(".ipsPagination_next")
        return Pager(
            label = if (pages > 1) "Strona $current z $pages" else "",
            firstUrl = if (prev.isBlank()) "" else pageLink(".ipsPagination_first"),
            prevUrl = prev,
            nextUrl = next,
            lastUrl = if (next.isBlank()) "" else pageLink(".ipsPagination_last"),
            firstNumber = (current - 1) * perPage + 1,
        )
    }

    fun forum(document: Document): ForumPage {
        val topics = document.select("ol.cForumTopicTable li[data-rowid]").mapNotNull { row ->
            val titleLink = row.selectFirst(".ipsDataItem_title a[href*=/topic/]") ?: return@mapNotNull null
            TopicSummary(
                title = titleLink.text().clean(),
                author = row.selectFirst(".ipsDataItem_meta a[href*=/profile/]")?.text().clean(),
                date = row.selectFirst(".ipsDataItem_meta time")?.text().clean(),
                replies = row.selectFirst(".ipsDataItem_stats_number")?.text().clean(),
                url = safeForumUrl(titleLink.absUrl("href")),
                isUnread = (sequenceOf(row) + row.select("*").asSequence()).any { element ->
                    element.classNames().any { it.contains("unread", ignoreCase = true) } ||
                        element.attr("data-readstatus").equals("unread", ignoreCase = true) ||
                        element.attr("data-isread").equals("false", ignoreCase = true)
                },
            )
        }
        return ForumPage(
            document.selectFirst("h1.ipsType_pageTitle")?.text().clean().ifBlank { "Forum" },
            topics,
            markReadLink(document),
            subforums = document.select(".cForumList li[data-forumid]").mapNotNull(::forumSummary),
            sorts = document.select("[id^=elSortByMenu][id$=_menu] li.ipsMenu_item > a[href]").map {
                SortOption(it.text().clean(), safeForumUrl(it.absUrl("href")), it.parent()!!.hasClass("ipsMenu_itemChecked"))
            }.filter { !it.url.contains("advancedSearchForm") },
            pager = pager(document),
            canPost = document.select("a[href*=do=add]").any { it.absUrl("href").contains("/forum/") },
        )
    }

    private fun markReadLink(root: Element): String {
        val action = root.select("a[href], [data-url]").firstOrNull { element ->
            val href = element.attr("href")
            val dataUrl = element.attr("data-url")
            href.contains("do=markRead", ignoreCase = true) ||
                dataUrl.contains("markRead", ignoreCase = true)
        } ?: return ""
        val href = action.attr("href").ifBlank { action.attr("data-url") }
        if (href.isBlank()) return ""
        return safeForumUrl(action.absUrl(if (action.attr("href").isNotBlank()) "href" else "data-url"))
    }

    fun safePostHtml(body: Element): String {
        // IPS commonly renders YouTube embeds as iframes. We don't load remote
        // embeds inside the native post reader, but preserve a safe link to the
        // video so the post doesn't appear to have lost it entirely.
        body.select("iframe[src],iframe[data-src]").toList().forEach { iframe ->
            val rawSource = iframe.attr("src").ifBlank { iframe.attr("data-src") }.trim()
            val resolved = runCatching { java.net.URI(body.baseUri()).resolve(rawSource) }.getOrNull()
            val host = resolved?.host?.lowercase()?.removePrefix("www.")
            val path = resolved?.path.orEmpty()
            val isYoutube = host == "youtube.com" || host == "youtube-nocookie.com" || host == "youtu.be"
            val scheme = resolved?.scheme?.lowercase()
            if (isYoutube && scheme in setOf("http", "https")) {
                val videoUrl = when {
                    host == "youtube.com" && (path.startsWith("/embed/") || path.startsWith("/live/") || path.startsWith("/shorts/")) -> {
                        val id = path.substringAfterLast('/').takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
                        if (id.isNotBlank()) "https://www.youtube.com/watch?v=$id" else resolved.toString()
                    }
                    else -> "https://${if (host == "youtu.be") "youtu.be" else host}${path}" +
                        (resolved.rawQuery?.let { "?$it" } ?: "")
                }
                val link = Element(org.jsoup.parser.Tag.valueOf("a"), body.baseUri())
                    .attr("href", videoUrl)
                    .addClass("forumYoutubeLink")
                    .text("▶ Otwórz film na YouTube")
                iframe.replaceWith(link)
            } else {
                iframe.remove()
            }
        }
        body.select("script,style,iframe,object,embed,form,input,button,video,audio,source,svg,math,canvas").remove()
        // The forum double-escapes "&" in link texts that are bare addresses, so they read "&amp;".
        body.select("a").forEach { link ->
            val text = link.text()
            if (text.startsWith("http") && text.contains("&amp;") && link.children().isEmpty()) link.text(text.replace("&amp;", "&"))
        }
        body.select("*").forEach { element ->
            element.attributes().asList().map { it.key }.forEach { attribute ->
                if (attribute.startsWith("on", ignoreCase = true) || attribute in setOf("srcdoc", "formaction")) {
                    element.removeAttr(attribute)
                }
            }
            element.removeAttr("style")
            if (element.normalName() == "img") {
                val sourceAttribute = listOf("src", "data-src", "data-original", "data-lazy-src")
                    .firstOrNull { element.attr(it).isNotBlank() }
                val rawSource = sourceAttribute?.let { element.attr(it) }.orEmpty()
                if (rawSource.isBlank()) {
                    element.remove()
                    return@forEach
                }
                val resolved = runCatching { java.net.URI(body.baseUri()).resolve(rawSource).toString() }.getOrDefault("")
                val scheme = runCatching { java.net.URI(resolved).scheme?.lowercase() }.getOrNull()
                if (scheme == "http" || scheme == "https" || resolved.startsWith("data:image/")) {
                    element.attr("src", resolved)
                } else {
                    element.remove()
                }
                listOf("srcset", "data-src", "data-original", "data-lazy-src", "loading").forEach(element::removeAttr)
            }
            if (element.normalName() == "a") {
                val href = element.attr("href").trim()
                val scheme = runCatching { java.net.URI(href).scheme?.lowercase() }.getOrNull()
                if (href.isNotBlank() && scheme !in setOf("http", "https", "mailto")) element.removeAttr("href")
                element.removeAttr("target")
            }
        }
        return body.html()
    }

    /** A topic page; [focus] is the post the address pointed at (`#comment-ID`), 0 for none. */
    fun topic(document: Document, url: String, focus: Long = 0): TopicPage {
        val posts = document.select("article.ipsComment").mapNotNull { article ->
            val body = article.selectFirst("[data-role=commentContent]") ?: return@mapNotNull null
            val reactions = article.selectFirst("[data-controller=core.front.core.reaction]")
            val likeButton = reactions?.selectFirst("[data-action=reactLaunch]")
            ForumPost(
                id = article.id().removePrefix("elComment_").toLongOrNull() ?: 0,
                author = article.selectFirst(".cAuthorPane_author a, .ipsComment_author a")?.text().clean().ifBlank { "Użytkownik forum" },
                date = article.selectFirst(".ipsComment_meta time, time")?.text().clean(),
                text = body.text().clean(),
                html = safePostHtml(body),
                likeCount = reactions?.select(".ipsReact_reactCount")?.sumOf { count ->
                    count.text().filter(Char::isDigit).toIntOrNull() ?: 0
                } ?: 0,
                likedByMe = likeButton?.hasClass("ipsReact_reacted") == true,
                likeUrl = likeButton?.selectFirst("a[data-role=reaction]")?.absUrl("href")?.let(::safeForumUrl).orEmpty(),
                unlikeUrl = reactions?.selectFirst("[data-unreact]")?.absUrl("data-unreact")?.let(::safeForumUrl).orEmpty(),
                photo = photoUrl(article.selectFirst(".cAuthorPane_photo img, .ipsComment_author img")),
                editUrl = article.selectFirst("a[href*=do=editComment]")?.absUrl("href")?.let(::safeForumUrl).orEmpty(),
                authorUrl = profileLink(article.selectFirst(".cAuthorPane_author a[href*=/profile/], .ipsComment_author a[href*=/profile/]")),
                likedBy = reactions?.selectFirst(".ipsReact_overview")?.text().clean()
                    .replace(" reacted to this", " lubi to").replace(Regex("^You\\b"), "Ty").replace(" others", " innych"),
            ).takeIf { it.text.isNotBlank() || it.html.isNotBlank() }
        }
        val title = document.selectFirst("h1.ipsType_pageTitle")?.text().clean().ifBlank { "Temat" }
        val forum = document.selectFirst(".ipsType_pageTitle + p a[href*=/forum/]")?.text().clean()
        return TopicPage(
            title, forum, posts, url,
            focusPostId = focus.takeIf { id -> posts.any { it.id == id } } ?: 0,
            pager = pager(document),
            replyForm = replyForm(document),
        )
    }

    /** Null when the page is not signed in. */
    fun account(document: Document): Account? {
        val userLink = document.selectFirst("#cUserLink") ?: return null
        val menu = document.select("#elUserLink_menu a[href]").filter { it.attr("href").startsWith("http") }
        val logout = menu.firstOrNull { it.attr("href").contains("/logout/") || it.attr("href").contains("do=logout") }
        return Account(
            name = document.selectFirst("#elUserLink")?.text().clean().ifBlank { "Konto" },
            profileUrl = userLink.selectFirst("a.ipsUserPhoto")?.absUrl("href")?.let(::safeForumUrl).orEmpty(),
            photo = photoUrl(userLink.selectFirst("img")),
            links = menu.filter { it !== logout }.map { it.text().clean() to safeForumUrl(it.absUrl("href")) }
                .filter { it.first.isNotBlank() },
            logoutUrl = logout?.absUrl("href")?.let(::safeForumUrl).orEmpty(),
        )
    }

    fun profile(document: Document, url: String): MemberProfile {
        val header = document.selectFirst("#elProfileHeader") ?: error("Nie udało się odczytać profilu.")
        val id = document.selectFirst("[data-followArea=member][data-followID]")?.attr("data-followID")
            ?: Regex("""/profile/(\d+)""").find(document.location())?.groupValues?.get(1).orEmpty()
        val stats = document.select("#elProfileStats > ul > li").mapNotNull { item ->
            val title = item.selectFirst("h4")?.text().clean()
            val value = item.clone().apply { select("h4").remove() }.text().clean()
            if (title.isBlank() || value.isBlank()) null
            else (if (title == "Days Won") "Dni z najwięcej polubień" else title) to value
        }
        val reputation = document.selectFirst(".cProfileRepScore_points")?.text().clean()
        val fields = document.select("ul.cProfileFields > li").mapNotNull { item ->
            val title = item.selectFirst("strong")?.text().clean().removeSuffix(":").trim()
            val value = item.clone().apply { selectFirst("strong")?.parent()?.remove() }.text().clean()
            if (title.isBlank() || value.isBlank()) null else title to value
        }
        return MemberProfile(
            id = id,
            name = header.selectFirst("h1")?.text().clean().ifBlank { "Użytkownik forum" },
            url = safeForumUrl(document.location().ifBlank { url }),
            photo = photoUrl(header.selectFirst("#elProfilePhoto img")),
            group = header.selectFirst(".cProfileHeader_name > span")?.text().clean(),
            online = document.selectFirst("#elProfileStats .ipsOnlineStatus_online") != null,
            stats = stats + listOfNotNull(reputation.takeIf { it.isNotBlank() }?.let { "Reputacja" to it }),
            fields = fields,
            activity = activity(document),
            canMessage = document.selectFirst("a[href*=/messenger/compose/?to=$id]") != null,
        )
    }

    /** The forum's member name search answer (JSON array). */
    fun members(json: String): List<MemberHit> {
        val array = org.json.JSONArray(json)
        return (0 until array.length()).map { array.getJSONObject(it) }.map {
            MemberHit(it.optString("id"), org.jsoup.parser.Parser.unescapeEntities(it.optString("name"), false), it.optString("photo"))
        }
    }

    fun composeForm(document: Document): ComposeForm {
        val form = replyForm(document) ?: error(
            document.selectFirst(".ipsMessage_error, .ipsType_warning")?.text().clean().ifBlank { "Nie możesz napisać do tej osoby." }
        )
        val recipient = document.selectFirst("textarea[name=messenger_to]")?.wholeText()?.trim().orEmpty()
        if (recipient.isBlank()) error("Nie możesz napisać do tej osoby.")
        return ComposeForm(form, recipient)
    }

    fun newTopicForm(document: Document): NewTopicForm {
        val form = replyForm(document) ?: error(
            document.selectFirst(".ipsMessage_error, .ipsType_warning")?.text().clean().ifBlank { "Nie możesz założyć tematu w tym dziale." }
        )
        val titleField = form.fields.keys.firstOrNull { it.endsWith("_title") } ?: error("Forum nie udostępnia pola tytułu.")
        return NewTopicForm(form, titleField)
    }

    /** The forum's complaint about a submitted form, or [fallback] when the page shows none. */
    fun formError(document: Document, fallback: String): String =
        document.select(".ipsMessage_error, .ipsFieldRow_error .ipsType_warning").text().clean().ifBlank { fallback }

    /** The edit form for a post, or null when this account may not edit it. */
    fun editForm(document: Document): EditForm? {
        val form = replyForm(document) ?: return null
        val original = document.selectFirst("textarea[data-role=contentEditor]")?.wholeText().orEmpty()
        return EditForm(form, original)
    }

    /** Header badges, plus the number of distinct topics in an unread stream page. */
    fun counters(document: Document): Counters {
        fun badge(type: String) = document.selectFirst("span.ipsNotificationCount[data-notificationType=$type]")
            ?.attr("data-currentCount")?.toIntOrNull() ?: 0
        val topics = activity(document).map { runCatching { java.net.URI(it.url).path }.getOrDefault(it.url) }.distinct().size
        return Counters(badge("inbox"), badge("notify"), topics)
    }

    fun notifications(document: Document): NotificationsPage {
        val items = document.select("ol[data-role=tableRows] > li.ipsDataItem").mapNotNull { row ->
            val link = row.selectFirst("a.ipsDataItem_title[href]") ?: return@mapNotNull null
            val photo = row.selectFirst(".ipsDataItem_icon img")
            ForumNotification(
                // The forum leaves this one phrase untranslated.
                text = link.text().clean().replace(" reacted to post w temacie ", " – reakcja na Twój wpis w temacie "),
                url = link.absUrl("href"),
                time = row.selectFirst("time")?.text().clean(),
                author = photo?.attr("alt").clean(),
                photo = photoUrl(photo),
                unread = row.hasClass("ipsDataItem_unread") || row.selectFirst(".ipsItemStatus:not(.ipsItemStatus_read)") != null,
            )
        }
        val next = document.selectFirst("ul.ipsPagination .ipsPagination_next:not(.ipsPagination_inactive) a[href]")
        return NotificationsPage(items, next?.absUrl("href")?.let(::safeForumUrl).orEmpty())
    }

    private fun formToggle(input: Element, label: String) = FormToggle(
        name = input.attr("name"),
        value = input.attr("value").ifBlank { "1" },
        label = label,
        checked = input.hasAttr("checked"),
        enabled = !input.hasAttr("disabled"),
        radio = input.attr("type").equals("radio", ignoreCase = true),
    )

    /** Checkbox or radio rows of a field group ("Ignoruj:", "Metoda obserwowania"). */
    private fun choiceRows(list: Element): List<FormSettingRow> = list.select("> li").mapNotNull { choice ->
        val input = choice.selectFirst("input[type=checkbox], input[type=radio]") ?: return@mapNotNull null
        FormSettingRow(choice.selectFirst("label")?.text().clean(), "", listOf(formToggle(input, "")))
    }

    private fun groupTitle(row: Element) = row.selectFirst("> strong, strong.ipsFieldRow_title, .ipsFieldRow_label")?.text().clean().removeSuffix(":")

    /** The settings matrix (events by in-app / e-mail column), one section per sub-header. */
    private fun matrixSections(form: Element): List<FormSettingSection> {
        val sections = mutableListOf<FormSettingSection>()
        var title = ""
        var rows = mutableListOf<FormSettingRow>()
        fun flush() { if (rows.isNotEmpty()) sections += FormSettingSection(title, rows); rows = mutableListOf() }
        form.select("table tr").forEach { tr ->
            val header = tr.selectFirst("th.ipsMatrix_subHeader")
            if (header != null) { flush(); title = header.text().clean(); return@forEach }
            if (!tr.hasAttr("data-matrixrowid")) return@forEach
            val toggles = tr.select("td[data-col]").mapNotNull { cell ->
                cell.selectFirst("input[type=checkbox]")?.let { formToggle(it, cell.attr("data-title").clean()) }
            }
            if (toggles.isNotEmpty()) rows += FormSettingRow(tr.selectFirst(".ipsMatrix_rowTitle")?.text().clean(), "", toggles)
        }
        flush()
        return sections
    }

    /**
     * The notification settings form: single boxes, groups of boxes or radio choices, and the
     * matrix of events with an in-app and an e-mail column, split by its sub-headers.
     */
    fun notificationSettings(document: Document): SettingsForm? {
        val form = document.selectFirst("form[action*=/notifications/options]") ?: return null
        val hidden = form.select("input[type=hidden][name]").associate { it.attr("name") to it.attr("value") }
        val sections = mutableListOf<FormSettingSection>()
        val single = mutableListOf<FormSettingRow>()
        form.select("li.ipsFieldRow").filter { it.selectFirst("table") == null }.forEach { row ->
            val list = row.selectFirst("ul.ipsField_fieldList")
            if (list != null) {
                val choices = choiceRows(list)
                if (choices.isNotEmpty()) sections += FormSettingSection(groupTitle(row), choices)
            } else {
                val input = row.selectFirst("input[type=checkbox]") ?: return@forEach
                single += FormSettingRow(row.selectFirst("label")?.text().clean(), row.selectFirst(".ipsFieldRow_desc")?.text().clean(), listOf(formToggle(input, "")))
            }
        }
        if (single.isNotEmpty()) sections.add(0, FormSettingSection("", single))
        sections += matrixSections(form)
        return SettingsForm(safeForumUrl(form.absUrl("action")), hidden, sections).takeIf { hidden.containsKey("csrfKey") && sections.isNotEmpty() }
    }

    private val inputKinds = setOf("text", "email", "password", "number", "tel", "url", "search", "select", "textarea", "checkbox")

    /** One input row of an IPS form, or null when it is a kind the app cannot draw (date, file, …). */
    private fun formField(row: Element, input: Element): SignUpField? {
        val kind = when {
            // A member-name box with forum suggestions; a plain name works when sent.
            input.hasAttr("data-ipsautocomplete") -> "text"
            input.normalName() == "select" || input.normalName() == "textarea" -> input.normalName()
            else -> input.attr("type").lowercase().ifBlank { "text" }
        }
        if (kind !in inputKinds) return null
        val label = row.selectFirst("label.ipsFieldRow_label") ?: row.selectFirst("label")?.takeIf { kind == "checkbox" }
        return SignUpField(
            name = input.attr("name"),
            label = label?.clone()?.apply { select(".ipsFieldRow_required").remove() }?.text().clean().removeSuffix(":")
                .ifBlank { input.attr("placeholder").clean() },
            kind = kind,
            required = label?.selectFirst(".ipsFieldRow_required") != null || input.attr("aria-required") == "true",
            description = row.selectFirst(".ipsFieldRow_desc")?.text().clean(),
            options = input.select("option").map { it.attr("value") to it.text().clean() },
            value = when (kind) {
                "select" -> (input.selectFirst("option[selected]") ?: input.selectFirst("option"))?.attr("value").orEmpty()
                "textarea" -> input.wholeText()
                "checkbox" -> if (input.hasAttr("checked")) "1" else ""
                else -> input.attr("value")
            },
            error = row.takeIf { it.hasClass("ipsFieldRow_error") }?.select(".ipsType_warning")?.text().clean(),
            maxLength = input.attr("maxlength").toIntOrNull() ?: 0,
            link = label?.selectFirst("a[href]")?.absUrl("href")?.let(::safeForumUrl).orEmpty(),
            memberSearch = input.attr("data-ipsautocomplete-datasource").contains("do=findMember"),
        )
    }

    /** The sign-up form, or null when [document] has none (e.g. after a successful sign-up). */
    fun signUpForm(document: Document): SignUpForm? {
        val form = document.selectFirst("form[action*=/register]") ?: return null
        val hidden = form.select("input[type=hidden][name]").associate { it.attr("name") to it.attr("value") }
        val fields = form.select("li.ipsFieldRow").mapNotNull { row ->
            val input = row.selectFirst("input[name]:not([type=hidden]), select[name], textarea[name]") ?: return@mapNotNull null
            formField(row, input)
        }
        if (fields.isEmpty() || !hidden.containsKey("csrfKey")) return null
        return SignUpForm(
            action = safeForumUrl(form.absUrl("action")),
            hidden = hidden,
            fields = fields,
            error = document.select(".ipsMessage_error").text().clean(),
        )
    }

    /**
     * Any IPS form as items in page order, or null when it has something the app cannot draw
     * (the post editor, uploads, unknown inputs); such pages stay in the WebView.
     */
    fun forumForm(form: Element): ForumForm? {
        if (form.selectFirst("textarea[data-role=contentEditor], input[type=file], [data-ipsuploader], [data-ipseditor]") != null) return null
        val hidden = form.select("input[type=hidden][name]").associate { it.attr("name") to it.attr("value") }
        if (!hidden.containsKey("csrfKey")) return null
        val items = mutableListOf<FormItem>()
        val rows = form.select("li.ipsFieldRow").filter { row -> row.parents().none { it.hasClass("ipsFieldRow") } }
        for (row in rows) {
            if (row.selectFirst("table") != null) continue
            val group = row.selectFirst("ul.ipsField_fieldList, ul.ipsFieldRow_content")
            if (group != null) {
                val choices = choiceRows(group)
                if (choices.isNotEmpty()) { items += FormItem.Choices(groupTitle(row), choices); continue }
            }
            if (row.hasClass("ipsFieldRow_textValue")) {
                items += FormItem.Info(row.selectFirst(".ipsFieldRow_label")?.text().clean(), row.selectFirst(".ipsFieldRow_content")?.text().clean())
                continue
            }
            val input = row.selectFirst("input[name]:not([type=hidden]):not([type=submit]):not([type=button]), select[name], textarea[name]") ?: continue
            items += FormItem.Input(formField(row, input) ?: return null)
        }
        matrixSections(form).forEach { items += FormItem.Choices(it.title, it.rows) }
        if (items.isEmpty()) return null
        val submit = form.selectFirst("button[type=submit], input[type=submit]")
        return ForumForm(
            action = safeForumUrl(form.absUrl("action")),
            hidden = hidden,
            items = items,
            submit = submit?.text().clean().ifBlank { submit?.attr("value").clean() }.ifBlank { "Zapisz" },
        )
    }

    /**
     * A forum page the app draws itself: title, notes, lists and forms in page order, plus the
     * page's side menu. Null when nothing on it can be drawn, so the caller shows the web page.
     */
    fun nativePage(document: Document, url: String): NativePage? {
        val main = document.selectFirst("#ipsLayout_mainArea") ?: return null
        val blocks = mutableListOf<PageBlock>()
        main.selectFirst(".ipsPageHeader_info, .ipsPageHeader > .ipsType_light, .ipsPageHeader > p")?.text().clean()
            .takeIf { it.isNotBlank() }?.let { blocks += PageBlock.Text(it) }
        val taken = mutableListOf<Element>()
        // Menus, hidden blocks and the side menu are drawn differently or not at all.
        fun skip(element: Element) = element.parents().any { parent ->
            parent in taken || parent.hasClass("ipsMenu") || parent.hasClass("ipsHide") || parent.hasClass("ipsSideMenu")
        }
        for (element in main.select("h2, .ipsType_textBlock, .ipsMessage, ul.ipsDataList, ol.ipsDataList, form")) {
            if (skip(element)) continue
            when {
                element.normalName() == "form" -> {
                    if (element.attr("data-role") == "pageJump") continue
                    blocks += PageBlock.Form(forumForm(element) ?: return null)
                }
                element.normalName() == "h2" -> blocks += PageBlock.Heading(element.text().clean())
                element.hasClass("ipsMessage") -> if (!element.hasClass("ipsMessage_error")) blocks += PageBlock.Text(element.text().clean())
                element.hasClass("ipsType_textBlock") -> blocks += PageBlock.Text(
                    element.select("li").eachText().joinToString("\n") { "• ${it.clean()}" }.ifBlank { element.text().clean() },
                )
                else -> dataListBlock(element)?.let { blocks += it }
            }
            taken.add(element)
        }
        if (blocks.none { it is PageBlock.Form || it is PageBlock.Links }) return null
        // The side menu, without the page itself and rows the page already lists.
        val shown = blocks.filterIsInstance<PageBlock.Links>().flatMap { it.items }.map { it.url.trimEnd('/') }.toSet() +
            url.substringBefore('#').trimEnd('/')
        val side = main.select(".ipsSideMenu_list a[href]").map { PageLink(it.text().clean(), safeForumUrl(it.absUrl("href"))) }
            .filter { it.title.isNotBlank() && it.url.trimEnd('/') !in shown }
        // The page header's blurb is the same on every tab of a section; keep it where nothing else explains the page.
        if (blocks.any { it is PageBlock.Heading }) (blocks.firstOrNull() as? PageBlock.Text)?.let { intro ->
            if (main.selectFirst(".ipsPageHeader")?.text().orEmpty().contains(intro.text)) blocks.remove(intro)
        }
        if (side.isNotEmpty()) blocks += listOf(PageBlock.Heading("Więcej"), PageBlock.Links(side))
        return NativePage(
            title = main.selectFirst("h1.ipsType_pageTitle, h1")?.text().clean().ifBlank { document.title().substringBefore(" - ").clean() },
            url = url,
            blocks = blocks,
            message = main.select(".ipsMessage_success").text().clean(),
            error = main.select(".ipsMessage_error").text().clean(),
        )
    }

    /** Rows of an IPS data list as links: title, the rest of the text, the member photo and actions. */
    private fun dataListBlock(list: Element): PageBlock? {
        val links = list.select("> li.ipsDataItem").mapNotNull { item ->
            val main = item.selectFirst(".ipsDataItem_main") ?: item
            val titleElement = main.selectFirst(".ipsDataItem_title")
            val title = titleElement?.text().clean()
            if (title.isBlank()) return@mapNotNull null
            val actions = item.select("a[href*=csrfKey]").map { it.text().clean().ifBlank { "Wykonaj" } to safeForumUrl(it.absUrl("href")) }
            val target = (titleElement?.selectFirst("a[href]") ?: titleElement?.closest("a[href]")
                ?: main.select("a[href]").firstOrNull { !it.attr("href").contains("csrfKey") && !it.attr("href").startsWith("#") })
            val detail = main.clone().apply {
                select(".ipsDataItem_title, .ipsButton, script, .ipsHide, .ipsMenu").remove()
            }.text().clean()
            PageLink(
                title = title,
                url = target?.absUrl("href")?.let(::safeForumUrl).orEmpty(),
                detail = detail,
                photo = photoUrl(item.selectFirst(".ipsUserPhoto img")),
                actions = actions,
            )
        }
        if (links.isNotEmpty()) return PageBlock.Links(links)
        return list.text().clean().takeIf { it.isNotBlank() }?.let { PageBlock.Text(it) }
    }

    /** The forum's "are you sure?" step after a delete-type link: its form, ready to send. */
    fun confirmForm(document: Document): ReplyForm? {
        val form = document.select("form").firstOrNull { it.selectFirst("input[name=wasConfirmed]") != null } ?: return null
        val fields = form.select("input[type=hidden][name]").associate { it.attr("name") to it.attr("value") }
        return ReplyForm(safeForumUrl(form.absUrl("action")), fields, "")
    }

    fun loginCsrf(document: Document): String? =
        document.selectFirst("form[action*=/login/] input[name=csrfKey]")?.attr("value")

    fun conversations(document: Document): List<Conversation> =
        document.select("li.cMessage[data-messageid]").mapNotNull { row ->
            val link = row.selectFirst("a.cMessageTitle, a[data-role=messageURL]") ?: return@mapNotNull null
            Conversation(
                title = link.text().clean(),
                url = safeForumUrl(link.absUrl("href")),
                snippet = row.selectFirst(".ipsMessageRow")?.text().clean(),
                participants = row.selectFirst(".ipsDataItem_main > .ipsType_light")?.text().clean(),
                count = row.selectFirst(".ipsCommentCount")?.text().clean(),
                time = row.selectFirst("time")?.attr("title")?.clean()?.substringBeforeLast(' ').orEmpty()
                    .ifBlank { row.selectFirst("time")?.text().clean() },
                unread = row.hasClass("ipsDataItem_unread") || link.classNames().any { it.contains("unread", ignoreCase = true) },
                photo = photoUrl(row.selectFirst(".ipsDataItem_icon img")),
                photoOwner = row.selectFirst(".ipsDataItem_icon img")?.attr("alt")?.clean().orEmpty(),
            )
        }

    private fun messagePages(document: Document) =
        document.select("ul.ipsPagination").firstOrNull { it.attr("data-ipsPagination-pageParam") != "listPage" }

    /** Address of a conversation's newest page when [document] is an older one, else null. */
    fun conversationNewestUrl(document: Document): String? =
        messagePages(document)?.selectFirst(".ipsPagination_last:not(.ipsPagination_inactive) a[href]")?.absUrl("href")?.let(::safeForumUrl)

    fun conversation(document: Document, url: String): ConversationPage {
        val messages = document.select("article.ipsComment").mapNotNull { article ->
            val body = article.selectFirst("[data-role=commentContent]") ?: return@mapNotNull null
            val authorLink = article.selectFirst(".ipsComment_author a[href*=/profile/]")
            PrivateMessage(
                authorId = Regex("""/profile/(\d+)""").find(authorLink?.attr("href").orEmpty())?.groupValues?.get(1).orEmpty(),
                author = authorLink?.text().clean().ifBlank { "Użytkownik forum" },
                date = article.selectFirst(".ipsComment_meta time")?.attr("title")?.clean().orEmpty(),
                html = safePostHtml(body),
                photo = photoUrl(article.selectFirst(".ipsComment_header .ipsUserPhoto img")),
            )
        }
        val older = messagePages(document)
            ?.selectFirst(".ipsPagination_prev:not(.ipsPagination_inactive) a[href]")?.absUrl("href")?.let(::safeForumUrl).orEmpty()
        return ConversationPage(
            title = document.selectFirst("h1.ipsType_pageTitle.ipsType_break")?.text().clean().ifBlank { "Rozmowa" },
            url = safeForumUrl(url),
            messages = messages,
            myMemberId = Regex("""memberID:\s*(\d+)""").find(document.html())?.groupValues?.get(1).orEmpty(),
            replyForm = replyForm(document),
            olderUrl = older,
        )
    }

    /** A comment form on the page (topic reply, conversation reply, post edit): where to post it and its fields. */
    fun replyForm(document: Document): ReplyForm? {
        val form = document.select("form").firstOrNull { it.selectFirst("textarea[data-role=contentEditor]") != null } ?: return null
        // Hidden fields plus visible ones with their current values (e.g. edit reason, ticked boxes).
        val fields = form.select("input[name]").filter { input ->
            when (input.attr("type").lowercase()) {
                "hidden", "text", "" -> true
                "checkbox", "radio" -> input.hasAttr("checked")
                else -> false
            }
        }.associate { it.attr("name") to it.attr("value") }
        if (!fields.containsKey("csrfKey")) return null
        return ReplyForm(
            action = safeForumUrl(form.absUrl("action")),
            fields = fields,
            editor = form.selectFirst("textarea[data-role=contentEditor]")!!.attr("name"),
        )
    }

    /** A bimchatbox getMessages answer: rows split by `~~||~~`, fields by `~~#~~`, oldest first. */
    fun chatBatch(json: String, lastId: Long): ChatBatch {
        val answer = org.json.JSONObject(json)
        if (answer.optString("type") == "error") error(answer.optString("message").ifBlank { "Chatbox jest niedostępny." })
        val messages = answer.optString("content").split("~~||~~").mapNotNull { row ->
            val fields = row.split("~~#~~")
            val id = fields.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            ChatMessage(
                id = id,
                memberId = fields.getOrNull(1).orEmpty(),
                author = fields.getOrNull(2).orEmpty(),
                photoUrl = fields.getOrNull(4).orEmpty(),
                text = fields.getOrNull(6).orEmpty(),
                timestamp = fields.getOrNull(7)?.toLongOrNull() ?: 0,
            )
        }
        return ChatBatch(messages, answer.optString("lastID").toLongOrNull() ?: lastId)
    }

    /** The signed-in member id and CSRF key from the Chatbox page; null key when signed out. */
    fun chatSession(html: String): Pair<String, String?> =
        Regex("""memberID:\s*(\d+)""").find(html)?.groupValues?.get(1).orEmpty() to
            Regex("""csrfKey=([0-9a-f]{32})""").find(html)?.groupValues?.get(1)

    fun search(document: Document): List<ActivityStory> =
        document.select("[data-role=resultsContents] li[data-role=activityItem]").mapNotNull { item ->
            val titleLink = item.selectFirst(".ipsStreamItem_title a, h2 a, h3 a[href*=/topic/]")
                ?: return@mapNotNull null
            val url = safeForumUrl(titleLink.absUrl("href"))
            if (url == "http://f650gs.pl/") return@mapNotNull null
            ActivityStory(
                title = titleLink.text().clean(),
                author = item.selectFirst(".ipsStreamItem_header .ipsUserPhoto")?.attr("title")
                    ?.removePrefix("Przejdź do profilu ").clean(),
                context = item.selectFirst(".ipsStreamItem_status")?.text().clean(),
                snippet = item.selectFirst(".ipsStreamItem_snippet")?.text().clean(),
                time = item.selectFirst(".ipsStreamItem_meta time")?.text().clean(),
                replies = item.select(".ipsStreamItem_meta li").lastOrNull()?.text()?.clean().orEmpty(),
                url = url,
                photo = photoUrl(item.selectFirst(".ipsUserPhoto img")),
                authorUrl = profileLink(item.selectFirst(".ipsStreamItem_header .ipsUserPhoto")),
            )
        }

    /** Pictures in a post's HTML: forum emoticons, Tapatalk emoji and images inside quotes of other posts don't count. */
    fun imageCount(html: String): Int = Jsoup.parseBodyFragment(html)
        .select("img:not([data-emoticon]):not([src*=/emoticons/]):not([src*=tapatalk-cdn.com/emoji])")
        .count { image -> image.parents().none { it.normalName() == "blockquote" } }

    /** Keeps the app on the forum: any other host becomes the forum's front page; always plain HTTP. */
    fun safeForumUrl(value: String): String {
        val resolved = if (value.startsWith("/")) "http://f650gs.pl$value" else value
        val uri = runCatching { java.net.URI(resolved) }.getOrNull() ?: return "http://f650gs.pl/"
        val host = uri.host?.lowercase().orEmpty()
        if (host != "f650gs.pl" && host != "www.f650gs.pl" && !host.endsWith(".f650gs.pl")) return "http://f650gs.pl/"
        val path = uri.rawPath.ifBlank { "/" }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        return "http://$host$path$query$fragment"
    }

    fun String?.clean(): String = this.orEmpty().replace(Regex("\\s+"), " ").trim()
}
