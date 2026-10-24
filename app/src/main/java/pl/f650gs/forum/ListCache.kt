package pl.f650gs.forum

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The last loaded copy of each list screen, kept as JSON files in the app's cache folder so
 * a screen can show it at once while the fresh copy loads. Android may clear the folder at any time.
 */
class ListCache(private val dir: File) {
    /** How one list type is written to and read from JSON. */
    class Codec<T>(val write: (T) -> JSONArray, val read: (JSONArray) -> T)

    fun <T> get(key: String, codec: Codec<T>): T? = runCatching {
        codec.read(JSONArray(File(dir, "$key.json").readText()))
    }.getOrNull()

    fun <T> put(key: String, codec: Codec<T>, value: T) {
        runCatching {
            dir.mkdirs()
            val file = File(dir, "$key.json")
            val temporary = File(dir, "$key.json.tmp")
            temporary.writeText(codec.write(value).toString())
            temporary.renameTo(file)
        }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private fun <T> list(items: List<T>, write: (T) -> JSONObject) = JSONArray().apply { items.forEach { put(write(it)) } }
        private fun <T> items(array: JSONArray, read: (JSONObject) -> T) = (0 until array.length()).map { read(array.getJSONObject(it)) }

        val STORIES = Codec<List<ActivityStory>>(
            write = { stories ->
                list(stories) {
                    JSONObject().put("title", it.title).put("author", it.author).put("context", it.context)
                        .put("snippet", it.snippet).put("time", it.time).put("replies", it.replies).put("url", it.url)
                        .put("photo", it.photo).put("authorUrl", it.authorUrl).put("unread", it.unread)
                }
            },
            read = { array ->
                items(array) {
                    ActivityStory(
                        it.getString("title"), it.getString("author"), it.getString("context"), it.getString("snippet"),
                        it.getString("time"), it.getString("replies"), it.getString("url"), it.optString("photo"), it.optString("authorUrl"),
                        it.optBoolean("unread"),
                    )
                }
            },
        )

        val CATEGORIES = Codec<List<ForumCategory>>(
            write = { categories ->
                list(categories) { category ->
                    JSONObject().put("name", category.name).put("forums", list(category.forums) {
                        JSONObject().put("title", it.title).put("description", it.description).put("postCount", it.postCount)
                            .put("latestTopic", it.latestTopic).put("latestAuthor", it.latestAuthor).put("latestTime", it.latestTime)
                            .put("url", it.url).put("markReadUrl", it.markReadUrl)
                    })
                }
            },
            read = { array ->
                items(array) { category ->
                    ForumCategory(category.getString("name"), items(category.getJSONArray("forums")) {
                        ForumSummary(
                            it.getString("title"), it.getString("description"), it.getString("postCount"), it.getString("latestTopic"),
                            it.getString("latestAuthor"), it.getString("latestTime"), it.getString("url"), it.optString("markReadUrl"),
                        )
                    })
                }
            },
        )

        val CONVERSATIONS = Codec<List<Conversation>>(
            write = { conversations ->
                list(conversations) {
                    JSONObject().put("title", it.title).put("url", it.url).put("snippet", it.snippet)
                        .put("participants", it.participants).put("count", it.count).put("time", it.time)
                        .put("unread", it.unread).put("photo", it.photo).put("photoOwner", it.photoOwner)
                }
            },
            read = { array ->
                items(array) {
                    Conversation(
                        it.getString("title"), it.getString("url"), it.getString("snippet"), it.getString("participants"),
                        it.getString("count"), it.getString("time"), it.getBoolean("unread"), it.optString("photo"), it.optString("photoOwner"),
                    )
                }
            },
        )
    }
}
