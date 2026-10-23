package pl.f650gs.forum

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * The parts of the Google Photos Library API still open to apps since 31.03.2025: creating an
 * album and uploading into it (scope photoslibrary.appendonly). Sharing the album is not
 * possible through the API any more; the user does that once in Google Photos.
 */
class GooglePhotosApi(private val accessToken: String) {

    data class Album(val id: String, val productUrl: String)

    fun createAlbum(title: String): Album {
        val body = JSONObject().put("album", JSONObject().put("title", title))
        val answer = JSONObject(request("POST", "$BASE/albums", body.toString().toByteArray(), "application/json"))
        return Album(answer.getString("id"), answer.optString("productUrl"))
    }

    /** Uploads [data] and adds it to album [albumId]; returns the media item's id. */
    fun upload(albumId: String, fileName: String, mimeType: String, data: ByteArray): String {
        val token = request(
            "POST", "$BASE/uploads", data, "application/octet-stream",
            mapOf("X-Goog-Upload-Content-Type" to mimeType, "X-Goog-Upload-Protocol" to "raw"),
        )
        val body = JSONObject()
            .put("albumId", albumId)
            .put("newMediaItems", JSONArray().put(
                JSONObject().put("simpleMediaItem", JSONObject().put("uploadToken", token).put("fileName", fileName))
            ))
        val answer = JSONObject(request("POST", "$BASE/mediaItems:batchCreate", body.toString().toByteArray(), "application/json"))
        val result = answer.getJSONArray("newMediaItemResults").getJSONObject(0)
        val status = result.optJSONObject("status")
        val item = result.optJSONObject("mediaItem")
            ?: error("Google Photos nie przyjął zdjęcia: ${status?.optString("message").orEmpty()}")
        return item.getString("id")
    }

    private fun request(
        method: String,
        url: String,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", contentType)
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Google Photos: HTTP $code ${text.take(300)}")
            return text
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val BASE = "https://photoslibrary.googleapis.com/v1"
        const val SCOPE_APPEND = "https://www.googleapis.com/auth/photoslibrary.appendonly"
    }
}
