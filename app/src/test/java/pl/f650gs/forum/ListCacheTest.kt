package pl.f650gs.forum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ListCacheTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun listsComeBackUnchanged() {
        val cache = ListCache(folder.newFolder("lists"))
        val stories = listOf(ActivityStory("Tytuł „x”", "Ola", "kontekst", "", "wczoraj", "3 odpowiedzi", "http://f650gs.pl/topic/1-x/", "", "http://f650gs.pl/profile/2-ola/"))
        val categories = listOf(ForumCategory("Dział", listOf(ForumSummary("Forum", "opis", "10", "temat", "Jan", "dziś", "http://f650gs.pl/forum/1-f/"))))
        val conversations = listOf(Conversation("Zlot", "http://f650gs.pl/messenger/1/", "…", "Ty i Jan", "8", "04.06.2025", true, "http://f650gs.pl/p.png", "Jan"))
        cache.put("home", ListCache.STORIES, stories)
        cache.put("forums", ListCache.CATEGORIES, categories)
        cache.put("messages", ListCache.CONVERSATIONS, conversations)
        assertEquals(stories, cache.get("home", ListCache.STORIES))
        assertEquals(categories, cache.get("forums", ListCache.CATEGORIES))
        assertEquals(conversations, cache.get("messages", ListCache.CONVERSATIONS))
        cache.clear()
        assertNull(cache.get("home", ListCache.STORIES))
    }

    @Test fun damagedFileIsIgnored() {
        val dir = folder.newFolder("lists")
        java.io.File(dir, "home.json").writeText("{not json")
        assertNull(ListCache(dir).get("home", ListCache.STORIES))
    }
}
