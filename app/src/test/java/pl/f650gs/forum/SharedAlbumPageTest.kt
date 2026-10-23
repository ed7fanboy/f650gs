package pl.f650gs.forum

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedAlbumPageTest {
    // Shape of a photo entry on a shared Google Photos album page (2026-10), shortened.
    private val page = """
        <img src="https://lh3.googleusercontent.com/a/ACg8ocAvatar=s64">
        ["AF1QipOhdD5t",["https://lh3.googleusercontent.com/pw/AP1GczN0a3",1080,1211,null,null,null,null,null,[null,null,1],[16381686]],1741247339000,"aRm9fr3WiYPgOB3fNMylkrfDM14",3600000,1792709334943,["AF1QipPdA"],[[2]],2,{"15":171808}],
        ["AF1QipPNHYUV",["https://lh3.googleusercontent.com/pw/AP1GczNKxT",600,615,null,null,null,null,null,[null,null,1],[15395820]],-1000,"6hZkfx79NawHrTuOv6XOegARowc",3600000,1792709334943]
    """

    @Test fun photosAreKeyedByFileFingerprint() {
        assertEquals(
            mapOf(
                "aRm9fr3WiYPgOB3fNMylkrfDM14" to "https://lh3.googleusercontent.com/pw/AP1GczN0a3",
                "6hZkfx79NawHrTuOv6XOegARowc" to "https://lh3.googleusercontent.com/pw/AP1GczNKxT",
            ),
            SharedAlbumPage.photosByFingerprint(page),
        )
    }

    @Test fun forumSizeSuffix() {
        assertEquals("https://lh3.googleusercontent.com/pw/X=w1600-h1600", SharedAlbumPage.forumUrl("https://lh3.googleusercontent.com/pw/X"))
    }
}
