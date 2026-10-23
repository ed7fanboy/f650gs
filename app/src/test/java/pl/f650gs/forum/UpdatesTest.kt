package pl.f650gs.forum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatesTest {
    private val sha = "e9396f20" + "0".repeat(56)

    @Test fun readsVersionFile() {
        val info = parseUpdateInfo("""{"versionCode":43,"versionName":"0.18.0","apk":"https://ed7fanboy.github.io/f650gs/f650gs.apk","sha256":"${sha.uppercase()}"}""")
        assertEquals(UpdateInfo(43, "0.18.0", "https://ed7fanboy.github.io/f650gs/f650gs.apk", sha), info)
    }

    @Test fun refusesApksFromElsewhereOrWithoutChecksum() {
        assertNull(parseUpdateInfo("""{"versionCode":43,"versionName":"0.18.0","apk":"https://example.com/f650gs.apk","sha256":"$sha"}"""))
        assertNull(parseUpdateInfo("""{"versionCode":43,"versionName":"0.18.0","apk":"https://ed7fanboy.github.io/f650gs/f650gs.apk","sha256":"abc"}"""))
        assertNull(parseUpdateInfo("not json"))
    }
}
