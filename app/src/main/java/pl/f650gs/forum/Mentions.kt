package pl.f650gs.forum

import android.text.Editable
import android.text.Spanned
import android.widget.EditText

/** Tapatalk writes mentions into the post text: `[mention=352]Name[/mention]`, or without the member id. */
internal val TAPATALK_MENTION = Regex("""\[mention(?:=(\d+))?]\s*([^\[\]]+?)\s*\[/mention]""", RegexOption.IGNORE_CASE)

/** Link target for a Tapatalk mention without a member id; the app looks the name up when it is tapped. */
internal const val MENTION_LOOKUP = "http://f650gs.pl/app-mention/"

/** A member's profile address; the forum redirects any name part to the right one. */
internal fun profileUrl(id: String, name: String): String {
    val slug = name.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), "-").trim('-').ifBlank { "member" }
    return "http://f650gs.pl/profile/$id-$slug/"
}

private fun escapeHtml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** A mention as the forum's editor writes it, which also notifies the member. */
internal fun mentionHtml(id: String, name: String): String {
    val url = profileUrl(id, name)
    return "<a contenteditable=\"false\" data-ipshover=\"\" data-ipshover-target=\"$url?do=hovercard\" " +
        "data-mentionid=\"$id\" href=\"$url\" rel=\"\">@${escapeHtml(name)}</a>"
}

/** [text] as HTML with each Tapatalk mention turned into a link to the member's profile. */
internal fun tapatalkMentionsHtml(text: String): String {
    val html = StringBuilder()
    var from = 0
    TAPATALK_MENTION.findAll(text).forEach { match ->
        html.append(escapeHtml(text.substring(from, match.range.first)))
        val (id, name) = match.destructured
        val href = if (id.isNotBlank()) profileUrl(id, name) else MENTION_LOOKUP + "?name=" + java.net.URLEncoder.encode(name, "UTF-8")
        html.append("<a class=\"forumMention\" href=\"").append(escapeHtml(href)).append("\">@").append(escapeHtml(name)).append("</a>")
        from = match.range.last + 1
    }
    return html.append(escapeHtml(text.substring(from))).toString()
}

/** Replaces Tapatalk mentions in the text of [body] (outside existing links) with profile links. */
internal fun linkTapatalkMentions(body: org.jsoup.nodes.Element) {
    body.select("*").flatMap { it.textNodes() }
        .filter { node -> TAPATALK_MENTION.containsMatchIn(node.wholeText) && generateSequence(node.parent()) { it.parent() }.none { it.nodeName() == "a" } }
        .forEach { node ->
            node.before(tapatalkMentionsHtml(node.wholeText))
            node.remove()
        }
}

/** Opens the profile behind a name-only Tapatalk mention. */
internal fun MainActivity.openMention(name: String) {
    if (name.length < 3) return toast("Nie znaleziono użytkownika $name.")
    io.execute {
        val hit = runCatching { repository.findMembers(name) }.getOrDefault(emptyList()).firstOrNull { it.name.equals(name, ignoreCase = true) }
        main.post {
            if (hit == null) toast("Nie znaleziono użytkownika $name.")
            else navigate(Screen.Profile(hit.name, profileUrl(hit.id, hit.name)))
        }
    }
}

/** A member picked from the @ suggestions, sent as a forum mention while the text still reads "@Name". */
internal class MentionSpan(color: Int, val id: String, val name: String) : android.text.style.ForegroundColorSpan(color)

/** "@abc" right before the cursor: the start of the "@" and the name typed so far. */
internal fun mentionQuery(textBeforeCursor: CharSequence): Pair<Int, String>? {
    val match = Regex("""(?:^|\s)@([\p{L}\p{N}_.\-]{3,30})$""").find(textBeforeCursor) ?: return null
    val name = match.groupValues[1]
    return textBeforeCursor.length - name.length - 1 to name
}

/**
 * [text] with each mention whose "@Name" is still intact swapped for a token, and the HTML for
 * each token; the tokens survive escaping and are replaced once the paragraphs are built.
 */
internal fun withMentionTokens(text: CharSequence): Pair<CharSequence, Map<String, String>> {
    val spanned = text as? Spanned ?: return text to emptyMap()
    val mentions = spanned.getSpans(0, spanned.length, MentionSpan::class.java).sortedByDescending(spanned::getSpanStart)
    if (mentions.isEmpty()) return text to emptyMap()
    val copy = android.text.SpannableStringBuilder(spanned)
    val html = mutableMapOf<String, String>()
    mentions.forEachIndexed { index, span ->
        val start = spanned.getSpanStart(span)
        val end = spanned.getSpanEnd(span)
        if (spanned.substring(start, end) != "@${span.name}") return@forEachIndexed
        val token = "[[f650mention:$index]]"
        copy.replace(start, end, token)
        html[token] = mentionHtml(span.id, span.name)
    }
    return copy to html
}

/** The authors of the open topic's posts, as mention candidates. */
internal fun MainActivity.topicMembers(): List<MemberHit> = topicPage?.posts.orEmpty().mapNotNull { post ->
    val id = Regex("""/profile/(\d+)-""").find(post.authorUrl)?.groupValues?.get(1) ?: return@mapNotNull null
    MemberHit(id, post.author, post.photo)
}.distinctBy { it.id }

/**
 * The forum matches [query] anywhere in a name: put names starting with it first, and people
 * already in the conversation ([participants]) ahead of the rest.
 */
internal fun rankMembers(found: List<MemberHit>, participants: List<MemberHit>, query: String): List<MemberHit> {
    val inTopic = participants.filter { it.name.contains(query, ignoreCase = true) }
    val ids = participants.map { it.id }.toSet()
    return (inTopic + found).distinctBy { it.id }.sortedBy { member ->
        val prefix = member.name.startsWith(query, ignoreCase = true)
        when {
            member.id in ids && prefix -> 0
            prefix -> 1
            member.id in ids -> 2
            else -> 3
        }
    }.take(8)
}

/**
 * Suggests forum members after "@abc" in [edit] (while [enabled]), people in [participants] first;
 * picking one inserts a mention.
 */
internal fun MainActivity.mentionSuggestions(edit: EditText, participants: () -> List<MemberHit> = { emptyList() }, enabled: () -> Boolean = { true }) {
    val popup = android.widget.ListPopupWindow(this).apply {
        anchorView = edit
        isModal = false
        setBackgroundDrawable(rounded(CARD, 12))
        inputMethodMode = android.widget.ListPopupWindow.INPUT_METHOD_NEEDED
    }
    var pending: Runnable? = null
    edit.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable) {
            pending?.let(main::removeCallbacks)
            val cursor = edit.selectionEnd
            val query = if (enabled() && cursor >= 0 && cursor <= s.length) mentionQuery(s.subSequence(0, cursor)) else null
            if (query == null || s.getSpans(query.first, cursor, MentionSpan::class.java).isNotEmpty()) { popup.dismiss(); return }
            val (at, name) = query
            val people = participants()
            pending = Runnable {
                io.execute {
                    val found = rankMembers(runCatching { repository.findMembers(name) }.getOrDefault(emptyList()), people, name)
                    main.post {
                        val now = edit.selectionEnd
                        if (!edit.isAttachedToWindow || now > edit.text.length || mentionQuery(edit.text.subSequence(0, now)) != query) return@post
                        if (found.isEmpty()) { popup.dismiss(); return@post }
                        popup.setAdapter(object : android.widget.BaseAdapter() {
                            override fun getCount() = found.size
                            override fun getItem(position: Int) = found[position]
                            override fun getItemId(position: Int) = position.toLong()
                            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup) = row {
                                setPadding(dp(12), dp(8), dp(12), dp(8))
                                addView(initialBadge(found[position].name, found[position].photo), android.widget.LinearLayout.LayoutParams(dp(30), dp(30)))
                                addView(label(found[position].name, 15f, INK, bold = true), android.widget.LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
                            }
                        })
                        popup.setOnItemClickListener { _, _, position, _ ->
                            popup.dismiss()
                            val member = found[position]
                            val text = edit.text
                            val end = edit.selectionEnd.coerceAtMost(text.length)
                            val shown = "@${member.name}"
                            text.replace(at, end, "$shown ")
                            text.setSpan(MentionSpan(LINK, member.id, member.name), at, at + shown.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            edit.setSelection((at + shown.length + 1).coerceAtMost(text.length))
                        }
                        popup.width = (edit.width * 0.9).toInt().coerceAtLeast(dp(220))
                        popup.show()
                    }
                }
            }.also { main.postDelayed(it, 300) }
        }
    })
}
