package pl.f650gs.forum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PastedMediaTest {
    @Test fun imageAddressesBecomePhotos() {
        val media = pastedMedia(" https://example.com/a/Moto.JPG?w=800&h=600 \n")!!
        assertEquals("<p><img src=\"https://example.com/a/Moto.JPG?w=800&amp;h=600\" alt=\"\"></p>", media.html)
        assertEquals("https://example.com/a/Moto.JPG?w=800&h=600", media.previewUrl)
        listOf("http://x.pl/a.jpeg", "https://x.pl/b.png", "https://x.pl/c.gif", "https://x.pl/d.webp", "https://x.pl/e.AVIF").forEach { assertEquals(it, pastedMedia(it)?.previewUrl) }
    }

    @Test fun youtubeLinksBecomeEmbeds() {
        val embed = "<div class=\"ipsEmbeddedVideo\"><div><iframe allowfullscreen=\"true\" frameborder=\"0\" height=\"270\" " +
            "src=\"https://www.youtube.com/embed/abc_DEF-123?feature=oembed\" width=\"480\"></iframe></div></div>"
        listOf(
            "https://www.youtube.com/watch?v=abc_DEF-123",
            "https://m.youtube.com/watch?feature=share&v=abc_DEF-123&t=42",
            "https://youtu.be/abc_DEF-123?si=xyz",
            "https://youtube.com/shorts/abc_DEF-123",
        ).forEach { url ->
            val media = pastedMedia(url)!!
            assertEquals(url, embed, media.html)
            assertEquals("https://img.youtube.com/vi/abc_DEF-123/hqdefault.jpg", media.previewUrl)
        }
    }

    @Test fun otherTextStaysText() {
        listOf(
            "https://x.pl/a.jpg.html",
            "zobacz https://x.pl/a.jpg",
            "https://x.pl/a.jpg https://x.pl/b.jpg",
            "https://x.pl/page",
            "https://www.youtube.com/channel/UC123",
            "ftp://x.pl/a.png",
            "https://x.pl/scan.tiff",
        ).forEach { assertNull(it, pastedMedia(it)) }
    }
}
