package pl.f650gs.forum

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MentionsTest {
    @Test fun tapatalkMentionsBecomeProfileLinks() {
        assertEquals(
            "z <a class=\"forumMention\" href=\"http://f650gs.pl/profile/352-dakarowy/\">@Dakarowy</a>: nie &lt;sprzedałem&gt;",
            tapatalkMentionsHtml("z [mention=352]Dakarowy[/mention]: nie <sprzedałem>"),
        )
        assertEquals(
            "Z <a class=\"forumMention\" href=\"http://f650gs.pl/app-mention/?name=arthurr\">@arthurr</a>rozmyślamy",
            tapatalkMentionsHtml("Z [mention]arthurr [/mention]rozmyślamy"),
        )
    }

    @Test fun mentionsInsideLinksAreLeftAlone() {
        val body = Jsoup.parseBodyFragment("<p>Hej [mention=5]Ola Nowak[/mention]!</p><a href=\"x\">[mention=6]Jan[/mention]</a>").body()
        linkTapatalkMentions(body)
        assertEquals("http://f650gs.pl/profile/5-ola-nowak/", body.selectFirst("p a")!!.attr("href"))
        assertEquals("@Ola Nowak", body.selectFirst("p a")!!.text())
        assertEquals("[mention=6]Jan[/mention]", body.select("a").last()!!.text())
    }

    @Test fun mentionHtmlMatchesTheForumEditor() {
        assertEquals(
            "<a contenteditable=\"false\" data-ipshover=\"\" data-ipshover-target=\"http://f650gs.pl/profile/284-cykli/?do=hovercard\" " +
                "data-mentionid=\"284\" href=\"http://f650gs.pl/profile/284-cykli/\" rel=\"\">@cykli</a>",
            mentionHtml("284", "cykli"),
        )
    }

    @Test fun queryNeedsThreeLettersRightAfterAt() {
        assertEquals(4 to "dak", mentionQuery("hej @dak"))
        assertEquals(0 to "Pszemo", mentionQuery("@Pszemo"))
        assertNull(mentionQuery("hej @da"))
        assertNull(mentionQuery("mail@dakar"))
        assertNull(mentionQuery("@dakar "))
    }

    @Test fun rankingPutsPrefixAndTopicPeopleFirst() {
        fun hit(id: String, name: String) = MemberHit(id, name, "")
        val found = listOf(hit("1", "Serdak"), hit("2", "AdaK313"), hit("3", "gsdakar"), hit("4", "Dakarek"), hit("5", "dakar100"))
        val ranked = rankMembers(found, listOf(hit("352", "dakarowy"), hit("9", "Pawel")), "dak")
        assertEquals(listOf("dakarowy", "Dakarek", "dakar100", "Serdak", "AdaK313", "gsdakar"), ranked.map { it.name })
    }
}
