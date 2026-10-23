package pl.f650gs.forum

/*
 * The forum's database stores text as 3-byte UTF-8, so a post with an emoji from the Android
 * keyboard (4 bytes: U+10000 and up) does not go through. Before sending, the app turns emoji into
 * the forum's own emoticons in posts, into their ASCII codes in plain-text places (Chatbox, titles),
 * and drops what has no equivalent.
 */

/** A forum emoticon: its file under uploads/emoticons/, its code (alt text), and ASCII for plain text. */
private class Emoticon(val file: String, val code: String, val ascii: String)

private val EMOTICONS: Map<String, Emoticon> = buildMap {
    fun add(emoji: String, file: String, code: String, ascii: String = code) =
        emoji.split(' ').forEach { put(it, Emoticon(file, code, ascii)) }
    add("🙂 😊 ☺ 🤗", "default_smile.png", ":)")
    add("😀 😃 😄 😁", "default_biggrin.png", ":D")
    add("😆 😂 🤣", "default_laugh.png", ":lol:", "xD")
    add("😉 😜", "default_wink.png", ";)")
    add("🙁 ☹ 😞 😟 😔 😥 😓", "default_sad.png", ":(")
    add("😢 😭", "default_icon_cry.gif", ":cry:", ":'(")
    add("😛 😝 😋 🤪", "default_tongue.png", ":P")
    add("😮 😯 😲 😱 😨", "default_ohmy.png", ":o")
    add("😎", "default_cool.png", "B)")
    add("😠 😡 🤬", "default_angry.png", ":angry:", ">:(")
    add("🤔 🤨 😕", "default_huh.png", ":huh:", ":/")
    add("😐 😑 😒", "default_dry.png", ":dry:", "-_-")
    add("😳", "default_blink.png", ":blink:", "o_O")
    add("🙄", "default_rolleyes.gif", ":rolleyes:")
    add("😍 🥰 😘", "default_wub.png", ":wub:", "<3")
    add("😴", "default_sleep.png", ":sleep:", "-_-zzz")
    add("😵 🥴", "default_wacko.png", ":wacko:", "@_@")
    add("😈", "default_icon_evil.gif", ":evil:", ">:)")
    add("😅 😬", "default_icon_redface.gif", ":oops:", "^^'")
    add("💡", "default_icon_idea.gif", ":idea:")
    add("🍺", "default_icon_beer.gif", ":beer:", "(piwo)")
    add("🍻", "default_icon_beer1.gif", ":beers:", "(piwa)")
}

/** Emoji with no forum emoticon, kept as plain-text stand-ins. */
private val ASCII = mapOf(
    "👍" to "(y)", "👎" to "(n)", "👌" to "(ok)", "🙏" to "(dzięki)", "💪" to "(siła)",
    "🏍" to "(moto)", "🔥" to "(ogień)", "🎉" to "(hura)", "😏" to ";)", "🙃" to ":)",
)

/** Variation selectors and joiners that only make sense next to an emoji. */
private val EMOJI_GLUE = setOf(0xFE0F, 0xFE0E, 0x200D, 0x20E3)

/**
 * Splits [text] into plain runs and emoji; [plain] gets each run of normal text, [emoji] each
 * emoji that has a forum emoticon or an ASCII stand-in. Other 4-byte characters are dropped.
 */
private fun scan(text: String, plain: (String) -> Unit, emoji: (Emoticon?, String) -> Unit) {
    val run = StringBuilder()
    var i = 0
    var afterEmoji = false
    while (i < text.length) {
        val point = text.codePointAt(i)
        val char = String(Character.toChars(point))
        i += char.length
        val known = EMOTICONS[char]
        val stand = ASCII[char]
        when {
            known != null || stand != null -> {
                if (run.isNotEmpty()) { plain(run.toString()); run.clear() }
                emoji(known, stand ?: known!!.ascii)
                afterEmoji = true
            }
            // Joiners, variation selectors, skin tones and the rest of an unknown emoji.
            point in EMOJI_GLUE || point in 0x1F3FB..0x1F3FF -> if (!afterEmoji) run.append(char)
            point > 0xFFFF -> afterEmoji = true
            else -> { run.append(char); afterEmoji = false }
        }
    }
    if (run.isNotEmpty()) plain(run.toString())
}

/** [text] for plain-text places (Chatbox, titles): emoji as ASCII codes, others dropped. */
internal fun forumSafeText(text: String): String = buildString {
    scan(text, { append(it) }) { _, ascii -> append(ascii) }
}

/**
 * [text] as post HTML: plain runs through [escape], emoji as the forum's emoticon images
 * (the markup the forum's own editor produces), others as ASCII or dropped.
 */
internal fun forumSafeHtml(text: String, escape: (String) -> String): String = buildString {
    scan(text, { append(escape(it)) }) { emoticon, ascii ->
        if (emoticon == null) append(escape(ascii))
        else append("<img src=\"http://f650gs.pl/uploads/emoticons/${emoticon.file}\" alt=\"${escape(emoticon.code)}\" title=\"${escape(emoticon.code)}\" data-emoticon=\"\">")
    }
}
