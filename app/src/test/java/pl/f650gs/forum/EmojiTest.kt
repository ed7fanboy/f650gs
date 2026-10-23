package pl.f650gs.forum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The forum stores 3-byte UTF-8 only: nothing from U+10000 up may reach it. */
class EmojiTest {
    private fun fits(text: String) = text.codePoints().allMatch { it <= 0xFFFF }

    @Test fun plainTextGetsAsciiCodes() {
        assertEquals("Super :) jedziemy ;) (y)", forumSafeText("Super 🙂 jedziemy 😉 👍"))
        assertEquals("Ale jazda xD", forumSafeText("Ale jazda 😂"))
    }

    @Test fun unknownEmojiSkinTonesAndFlagsAreDropped() {
        val text = forumSafeText("Polska 🇵🇱 i kciuk 👍🏽 i rakieta 🚀 i rodzina 👨‍👩‍👧 koniec")
        assertTrue(text, fits(text))
        assertEquals("Polska  i kciuk (y) i rakieta  i rodzina  koniec", text)
    }

    @Test fun polishLettersAndThreeByteSymbolsStay() {
        assertEquals("Zażółć gęślą jaźń ❤ ✌ €", forumSafeText("Zażółć gęślą jaźń ❤ ✌ €"))
    }

    @Test fun postsGetForumEmoticonImages() {
        val html = forumSafeHtml("Dzięki 😀!") { it }
        assertEquals("Dzięki <img src=\"http://f650gs.pl/uploads/emoticons/default_biggrin.png\" alt=\":D\" title=\":D\" data-emoticon=\"\">!", html)
        assertTrue(fits(forumSafeHtml("🙂️ 🍺 🚀 👍") { it }))
    }

    @Test fun variationSelectorAfterEmojiDoesNotLinger() {
        assertEquals(":) ok", forumSafeText("☺️ ok"))
    }
}
