package pl.f650gs.forum

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkedHtmlTest {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun html(s: String) = linkedHtml(s, ::esc)

    @Test fun plainTextUnchanged() = assertEquals("a &lt;b&gt; c", html("a <b> c"))

    @Test fun addressBecomesLink() = assertEquals(
        "zobacz <a href=\"https://f650gs.pl/topic/1-x/?a=1&amp;b=2\" rel=\"external nofollow\">https://f650gs.pl/topic/1-x/?a=1&amp;b=2</a>.",
        html("zobacz https://f650gs.pl/topic/1-x/?a=1&b=2."),
    )

    @Test fun wwwGetsScheme() = assertEquals(
        "(<a href=\"http://www.bmw.pl\" rel=\"external nofollow\">www.bmw.pl</a>)",
        html("(www.bmw.pl)"),
    )

    @Test fun bracketInsideAddressKept() = assertEquals(
        "<a href=\"https://pl.wikipedia.org/wiki/BMW_(firma)\" rel=\"external nofollow\">https://pl.wikipedia.org/wiki/BMW_(firma)</a>",
        html("https://pl.wikipedia.org/wiki/BMW_(firma)"),
    )

    @Test fun twoAddresses() = assertEquals(
        "<a href=\"http://a.pl\" rel=\"external nofollow\">http://a.pl</a> i <a href=\"http://b.pl/x\" rel=\"external nofollow\">http://b.pl/x</a>",
        html("http://a.pl i http://b.pl/x"),
    )
}
