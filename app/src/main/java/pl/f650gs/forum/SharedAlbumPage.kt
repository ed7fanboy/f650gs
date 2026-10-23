package pl.f650gs.forum

import java.net.HttpURLConnection
import java.net.URL

/**
 * The public page of a shared album (the "Utwórz link" address). Not an API: this reads the same
 * page a browser shows and picks out the photos' lh3.googleusercontent.com addresses, the ones
 * forum members copy with "Kopiuj adres obrazu". If Google changes the page, this breaks.
 */
object SharedAlbumPage {

    /**
     * Photo addresses by file fingerprint. Each photo on the page carries the base64url SHA-1 of
     * its file (Google's duplicate check), so an uploaded file is found even when it was already
     * in the album and Google kept the old copy instead of adding a new one.
     */
    fun photosByFingerprint(html: String): Map<String, String> =
        Regex("""\["AF1Qip[A-Za-z0-9_-]+",\["(https://lh3\.googleusercontent\.com/pw/[A-Za-z0-9_-]+)",\d+,\d+.*?]],-?\d+,"([A-Za-z0-9_-]{27})"""")
            .findAll(html).associate { it.groupValues[2] to it.groupValues[1] }

    /** A size the forum shows well; the address works with any `=w…-h…` suffix. */
    fun forumUrl(photoUrl: String): String = "$photoUrl=w1600-h1600"

    fun fetch(shareUrl: String): String {
        var location = shareUrl
        // photos.app.goo.gl redirects to photos.google.com/share/…; follow by hand across hosts.
        repeat(6) {
            val connection = URL(location).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.5")
                val code = connection.responseCode
                if (code in 300..399) {
                    location = URL(URL(location), connection.getHeaderField("Location")).toString()
                    return@repeat
                }
                if (code !in 200..299) error("Strona albumu: HTTP $code")
                return connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
        error("Strona albumu: za dużo przekierowań")
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"
}
