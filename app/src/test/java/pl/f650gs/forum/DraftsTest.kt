package pl.f650gs.forum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DraftsTest {
    @Test fun mediaPreviewsComeFromTheirHtml() {
        assertEquals("https://img.youtube.com/vi/abc_DEF-123/hqdefault.jpg", draftPreview(pastedMedia("https://youtu.be/abc_DEF-123")!!.html))
        assertEquals("https://x.pl/a.jpg?w=1&h=2", draftPreview(pastedMedia("https://x.pl/a.jpg?w=1&h=2")!!.html))
        assertEquals("https://lh3.googleusercontent.com/pw/AP1=w300-h300",
            draftPreview("<p><img src=\"https://lh3.googleusercontent.com/pw/AP1=w2048-h2048\" alt=\"\"></p>"))
        assertNull(draftPreview("<p><video src=\"x.mp4\"></video></p>"))
    }
}
